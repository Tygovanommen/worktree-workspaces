package nl.netonic.workspaces.ide;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.wm.WindowManager;
import nl.netonic.workspaces.core.IdeaFiles;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.Workspace;
import org.jetbrains.annotations.Nullable;

import javax.swing.JFrame;
import javax.swing.Timer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Finding, opening and focusing ticket projects. */
public final class Tickets {
  private static final Logger LOG = Logger.getInstance(Tickets.class);

  private Tickets() {}

  private record Cached(@Nullable Ticket ticket, long checkedAt) {}

  private static final Map<String, Cached> CURRENT = new ConcurrentHashMap<>();
  private static final long CACHE_MS = 5_000;

  /** The ticket this project window shows, or null when it isn't inside a workspace. Cached for 5s. */
  public static @Nullable Ticket current(@Nullable Project project) {
    if (project == null || project.isDefault() || project.getBasePath() == null) return null;
    String key = project.getBasePath();
    Cached c = CURRENT.get(key);
    long now = System.currentTimeMillis();
    if (c != null && now - c.checkedAt() < CACHE_MS) return c.ticket();
    Ticket t = resolve(Path.of(key));
    CURRENT.put(key, new Cached(t, now));
    return t;
  }

  public static void invalidateCache() {
    CURRENT.clear();
  }

  private static @Nullable Ticket resolve(Path base) {
    Workspace ws = Workspace.findContaining(base);
    if (ws == null) return null;
    Ticket t = ws.ticketFor(base);
    // only the ticket folder itself is a ticket project, not a repo opened on its own or a docs folder
    if (t == null || !t.getDir().equals(base.toAbsolutePath().normalize())) return null;
    return t.isMain() || Workspace.looksLikeTicket(t.getDir()) ? t : null;
  }

  public static @Nullable Project findOpenProject(Ticket ticket) {
    Path dir = ticket.getDir().toAbsolutePath().normalize();
    for (Project p : ProjectManager.getInstance().getOpenProjects()) {
      if (p.isDisposed() || p.getBasePath() == null) continue;
      if (Path.of(p.getBasePath()).toAbsolutePath().normalize().equals(dir)) return p;
    }
    return null;
  }

  public static boolean isOpen(Ticket ticket) {
    return findOpenProject(ticket) != null;
  }

  /** Focuses the ticket's window/tab, or prepares its .idea and opens it as a new tab. Call on the EDT. */
  public static void open(Ticket ticket) {
    if (!ticket.exists()) {
      Notifier.warn(null, ticket.displayName(), "This ticket folder no longer exists.");
      WorkspaceModel.getInstance().refresh();
      return;
    }
    Project existing = findOpenProject(ticket);
    if (existing != null) {
      ProjectUtil.focusProjectWindow(existing, true);
      return;
    }
    WorkspaceSettings.getInstance().addWorkspace(ticket.getWorkspace().getRoot());
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        IdeaFiles.prepare(ticket);
      } catch (Exception e) {
        LOG.warn(e);
        Notifier.error(null, "Couldn't prepare " + ticket.displayName(), e.getMessage());
        return;
      }
      ApplicationManager.getApplication().invokeLater(() -> openPrepared(ticket), ModalityState.nonModal());
    });
  }

  private static void openPrepared(Ticket ticket) {
    // (path, projectToClose = none, forceOpenInNewFrame = true): never replaces the current window
    Project project = ProjectUtil.openOrImport(ticket.getDir(), null, true);
    if (project != null) mergeIntoTabsLater(project);
  }

  /** macOS: turn the new window into a tab of the existing window (Window › Merge All Project Windows). */
  static void mergeIntoTabsLater(Project project) {
    if (!SystemInfo.isMac || !WorkspaceSettings.getInstance().isMergeWindowsIntoTabs()) return;
    Timer timer = new Timer(800, e -> {
      if (project.isDisposed()) return;
      try {
        JFrame frame = WindowManager.getInstance().getFrame(project);
        AnAction merge = ActionManager.getInstance().getAction("MergeAllWindowsAction");
        if (frame != null && merge != null) {
          ActionManager.getInstance().tryToExecute(merge, null, frame.getRootPane(), ActionPlaces.UNKNOWN, true);
        }
      } catch (Throwable t) {
        LOG.info("Could not merge windows into tabs", t);
      }
    });
    timer.setRepeats(false);
    timer.start();
  }
}
