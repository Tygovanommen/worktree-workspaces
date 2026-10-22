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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  /** Ticket folders being prepared / opened right now. */
  private static final Set<Path> OPENING = ConcurrentHashMap.newKeySet();

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

  /** Where a ticket that isn't open yet goes. */
  public enum OpenIn {
    /** A new window, merged into the current one as a tab (macOS; elsewhere a new window). */
    TAB("New tab"),
    NEW_WINDOW("New window"),
    /** Closes the given project first; its window is then reused, or the new one becomes a tab next to the others. */
    THIS_WINDOW("This window");

    private final String label;

    OpenIn(String label) {
      this.label = label;
    }

    /** The choices that make sense here: a tab only on macOS, this window only with a project open. */
    public static List<OpenIn> available(boolean projectOpen) {
      List<OpenIn> out = new ArrayList<>(List.of(values()));
      if (!SystemInfo.isMac) out.remove(TAB);
      if (!projectOpen) out.remove(THIS_WINDOW);
      return out;
    }

    /** Shown in the New Workspace dialog and the settings. */
    @Override
    public String toString() {
      return label;
    }
  }

  /** Focuses the ticket's window/tab, or prepares its .idea and opens it as a new tab. Call on the EDT. */
  public static void open(Ticket ticket) {
    open(ticket, null, null);
  }

  /**
   * Like {@link #open(Ticket)}, opening a closed ticket {@code in} the given place; null: as a tab when the
   * "open tickets as tabs" option is on. {@code current} is the project {@link OpenIn#THIS_WINDOW} replaces.
   */
  public static void open(Ticket ticket, @Nullable OpenIn in, @Nullable Project current) {
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
    // double-click + Enter, or the switcher while it is still loading: prepare and open only once
    Path dir = ticket.getDir().toAbsolutePath().normalize();
    if (!OPENING.add(dir)) return;
    WorkspaceSettings.getInstance().addWorkspace(ticket.getWorkspace().getRoot());
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        IdeaFiles.prepare(ticket);
      } catch (Exception e) {
        OPENING.remove(dir);
        LOG.warn(e);
        Notifier.error(null, "Couldn't prepare " + ticket.displayName(), e.getMessage());
        return;
      }
      ApplicationManager.getApplication().invokeLater(() -> {
        try {
          openPrepared(ticket, in, current);
        } finally {
          OPENING.remove(dir);
        }
      }, ModalityState.nonModal());
    });
  }

  private static void openPrepared(Ticket ticket, @Nullable OpenIn in, @Nullable Project current) {
    if (in == OpenIn.THIS_WINDOW) {
      // reusing the frame itself needs internal API (OpenProjectTaskBuilder): close, then open as usual
      // (current is null or disposed when it was already closed, e.g. its folder was moved into main/)
      if (current != null && !current.isDisposed() && !ProjectManager.getInstance().closeAndDispose(current)) return;
      in = null;
    }
    // (path, projectToClose = none, forceOpenInNewFrame = true): never replaces the current window
    Project project = ProjectUtil.openOrImport(ticket.getDir(), null, true);
    if (project == null) return;
    if (in == OpenIn.TAB) mergeIntoTabLater(project);
    else if (in == null) mergeIntoTabsLater(project);
  }

  /** macOS: turn the new window into a tab of the existing window (Window › Merge All Project Windows). */
  static void mergeIntoTabsLater(Project project) {
    if (!WorkspaceSettings.getInstance().isMergeWindowsIntoTabs()) return;
    mergeIntoTabLater(project);
  }

  /** Like {@link #mergeIntoTabsLater} without the setting: for an explicit "as a tab" choice. */
  static void mergeIntoTabLater(Project project) {
    if (!SystemInfo.isMac) return;
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
