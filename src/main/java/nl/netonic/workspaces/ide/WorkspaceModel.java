package nl.netonic.workspaces.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.messages.Topic;
import nl.netonic.workspaces.core.GitRunner;
import nl.netonic.workspaces.core.RepoStatus;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.TicketRepo;
import nl.netonic.workspaces.core.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scans all registered workspaces (tickets, repos, git status) in the background and
 * publishes the result on {@link #TOPIC}. The UI only ever reads {@link #snapshot()}.
 */
@Service(Service.Level.APP)
public final class WorkspaceModel {
  private static final Logger LOG = Logger.getInstance(WorkspaceModel.class);

  public interface Listener {
    void modelChanged();
  }

  public static final Topic<Listener> TOPIC = Topic.create("Worktree workspaces changed", Listener.class);

  public record RepoView(TicketRepo repo, RepoStatus status) {}

  public record TicketView(Ticket ticket, List<RepoView> repos) {}

  public record WorkspaceView(Path root, Workspace workspace, String error, List<TicketView> tickets) {
    public String name() {
      return workspace != null ? workspace.getName() : root.getFileName().toString();
    }
  }

  private volatile List<WorkspaceView> snapshot = List.of();
  private volatile boolean loaded;
  private volatile long lastRefresh;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean again = new AtomicBoolean();

  public static WorkspaceModel getInstance() {
    return ApplicationManager.getApplication().getService(WorkspaceModel.class);
  }

  public List<WorkspaceView> snapshot() {
    return snapshot;
  }

  public boolean isLoaded() {
    return loaded;
  }

  /** Re-renders listeners without re-scanning (e.g. a tab was opened or closed). */
  public void notifyChanged() {
    ApplicationManager.getApplication().invokeLater(
        () -> ApplicationManager.getApplication().getMessageBus().syncPublisher(TOPIC).modelChanged(),
        ModalityState.any());
  }

  /** Refreshes unless that happened in the last {@code minIntervalMs}. */
  public void refreshIfStale(long minIntervalMs) {
    if (System.currentTimeMillis() - lastRefresh > minIntervalMs) refresh();
  }

  /** Re-scans in the background; coalesces calls made while a scan is running. */
  public void refresh() {
    if (!running.compareAndSet(false, true)) {
      again.set(true);
      return;
    }
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        do {
          again.set(false);
          try {
            snapshot = scan();
          } catch (Throwable t) {
            LOG.warn("Scanning workspaces failed", t);
          }
          loaded = true;
          lastRefresh = System.currentTimeMillis();
          notifyChanged();
        } while (again.get());
      } finally {
        running.set(false);
      }
      // a request that arrived between the loop check and running=false
      if (again.getAndSet(false)) refresh();
    });
  }

  private static List<WorkspaceView> scan() {
    GitRunner git = IdeGit.runner();
    List<WorkspaceView> result = new ArrayList<>();
    for (Path root : WorkspaceSettings.getInstance().workspaceRoots()) {
      Workspace ws;
      try {
        ws = Workspace.load(root);
      } catch (Exception e) {
        result.add(new WorkspaceView(root, null, e.getMessage(), List.of()));
        continue;
      }
      List<TicketView> tickets = new ArrayList<>();
      for (Ticket t : ws.tickets()) {
        List<RepoView> repos = new ArrayList<>();
        for (TicketRepo r : t.repos()) {
          RepoStatus st = r.kind() == TicketRepo.Kind.MISSING ? null : RepoStatus.read(git, r.dir());
          repos.add(new RepoView(r, st));
        }
        tickets.add(new TicketView(t, repos));
      }
      result.add(new WorkspaceView(root, ws, null, tickets));
    }
    return List.copyOf(result);
  }
}
