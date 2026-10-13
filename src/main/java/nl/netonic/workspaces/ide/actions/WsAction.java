package nl.netonic.workspaces.ide.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Base class: dumb-aware, updated on a background thread (update() may read small files). */
abstract class WsAction extends DumbAwareAction {
  WsAction() {}

  WsAction(String text, String description, Icon icon) {
    super(text, description, icon);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  /** Open projects whose folder is (inside) one of {@code folders}: they must close before a move. */
  static List<Project> projectsInside(List<Path> folders) {
    List<Project> out = new ArrayList<>();
    for (Project p : ProjectManager.getInstance().getOpenProjects()) {
      if (p.getBasePath() == null) continue;
      Path base = Path.of(p.getBasePath()).toAbsolutePath().normalize();
      for (Path f : folders) {
        if (base.startsWith(f.toAbsolutePath().normalize())) {
          out.add(p);
          break;
        }
      }
    }
    return out;
  }
}
