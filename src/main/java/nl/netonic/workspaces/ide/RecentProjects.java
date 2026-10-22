package nl.netonic.workspaces.ide;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.RecentProjectsManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.io.FileUtil;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.Workspace;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * One Recent Projects entry per workspace: {@code main}. The workspace folder itself (opened as a project before it
 * became one) and ticket projects are removed once closed; an open one keeps its entry, so PhpStorm still reopens
 * the ticket tabs that were open when it quit. Off: nothing is removed (entries removed earlier don't come back).
 */
public final class RecentProjects {
  private static final Logger LOG = Logger.getInstance(RecentProjects.class);
  /** Set when PhpStorm quits: the projects it closes then must stay in the list to be reopened. */
  private static volatile boolean exiting;

  private RecentProjects() {}

  /** Removes the workspace folder and its closed tickets from Recent Projects. Does file IO: not on the EDT. */
  static void tidy(Workspace ws) {
    if (!WorkspaceSettings.getInstance().isTidyRecentProjects()) return;
    RecentProjectsManager recent = RecentProjectsManager.getInstance();
    Set<Path> open = openProjectDirs();
    remove(recent, ws.getRoot(), open);
    for (Ticket t : ws.tickets()) {
      if (!t.isMain()) remove(recent, t.getDir(), open);
    }
  }

  private static void remove(RecentProjectsManager recent, Path dir, Set<Path> open) {
    if (open.contains(dir.toAbsolutePath().normalize())) return;
    String path = FileUtil.toSystemIndependentName(dir.toString());
    if (recent.hasPath(path)) recent.removePath(path);
  }

  private static Set<Path> openProjectDirs() {
    Set<Path> dirs = new HashSet<>();
    for (Project p : ProjectManager.getInstance().getOpenProjects()) {
      if (p.getBasePath() != null) dirs.add(Path.of(p.getBasePath()).toAbsolutePath().normalize());
    }
    return dirs;
  }

  /** After a ticket tab (or the workspace folder) is closed by hand; PhpStorm has just saved its entry. */
  static void projectClosed(Project project) {
    String base = project.getBasePath();
    if (exiting || base == null || !WorkspaceSettings.getInstance().isTidyRecentProjects()) return;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        Workspace ws = Workspace.findContaining(Path.of(base));
        if (ws != null) tidy(ws);
      } catch (RuntimeException e) {
        LOG.warn("Tidying Recent Projects failed", e);
      }
    });
  }

  public static final class OnExit implements AppLifecycleListener {
    @Override
    public void appWillBeClosed(boolean isRestart) {
      exiting = true;
    }
  }
}
