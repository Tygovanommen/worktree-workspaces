package nl.netonic.workspaces.ide;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.wm.WindowManager;
import nl.netonic.workspaces.core.Ticket;

import javax.swing.JFrame;
import java.util.HashSet;
import java.util.Set;

/**
 * Gives every open ticket (and main) its own toolbar color, so tabs side by side are easy to tell apart.
 *
 * <p>Uses PhpStorm's project color palette (Change Project Color in the project widget; light and dark variants).
 * A project keeps its color while no other open project has it, else it gets a free one. The color is remembered
 * per project; once the user picks another one themselves, it is never touched again.
 *
 * <p>{@code ProjectWindowCustomizerService}'s setters are {@code @ApiStatus.Internal}, so they are called by
 * reflection (like {@link RepoTerminals}); on any failure the toolbar keeps PhpStorm's own color.
 */
final class TicketColors {
  private static final Logger LOG = Logger.getInstance(TicketColors.class);
  private static final String SERVICE = "com.intellij.ide.ProjectWindowCustomizerService";
  /** PhpStorm's palette size (ProjectWindowCustomizerService: indexes 0..8). */
  private static final int PALETTE = 9;
  /** The palette index we gave this project; "user" once the user chose a color of their own. */
  private static final String KEY = "worktreeWorkspaces.toolbarColor";
  private static final String USER = "user";

  private TicketColors() {}

  /** On a ticket project's open; any thread. */
  static void applyLater(Project project, Ticket ticket) {
    if (!WorkspaceSettings.getInstance().isColorTickets()) return;
    ApplicationManager.getApplication().invokeLater(() -> apply(project, ticket), ModalityState.nonModal(),
        project.getDisposed());
  }

  private static void apply(Project project, Ticket ticket) {
    try {
      Object service = ApplicationManager.getApplication().getService(Class.forName(SERVICE));
      if (service == null) return;
      Integer current = (Integer) service.getClass().getMethod("getCurrentProjectColorIndex", Project.class)
          .invoke(service, project);
      PropertiesComponent props = PropertiesComponent.getInstance(project);
      String ours = props.getValue(KEY);
      if (USER.equals(ours)) return;
      if (ours != null && (current == null || !ours.equals(String.valueOf(current)))) {
        props.setValue(KEY, USER); // changed since we set it: the user's choice
        return;
      }
      Set<Integer> taken = takenByOthers(service, project);
      if (current != null && !taken.contains(current)) { // already unique: keep it
        props.setValue(KEY, String.valueOf(current));
        return;
      }

      int start = Math.floorMod((ticket.getWorkspace().getName() + "/" + ticket.getName()).hashCode(), PALETTE);
      int pick = start;
      for (int i = 0; i < PALETTE; i++) {
        int candidate = (start + i) % PALETTE;
        if (!taken.contains(candidate)) {
          pick = candidate;
          break;
        }
      }
      // same steps as PhpStorm's own Change Project Color
      service.getClass().getMethod("clearToolbarColorsAndInMemoryCache", Project.class).invoke(service, project);
      service.getClass().getMethod("setAssociatedColorsIndex", Project.class, int.class)
          .invoke(service, project, pick);
      props.setValue(KEY, String.valueOf(pick));
      JFrame frame = WindowManager.getInstance().getFrame(project);
      if (frame != null) frame.repaint();
    } catch (Throwable t) {
      LOG.info("Could not set the toolbar color of " + project.getName(), t);
    }
  }

  private static Set<Integer> takenByOthers(Object service, Project project) throws Exception {
    Set<Integer> taken = new HashSet<>();
    for (Project other : ProjectManager.getInstance().getOpenProjects()) {
      if (other == project || other.isDisposed()) continue;
      Object index = service.getClass().getMethod("getCurrentProjectColorIndex", Project.class).invoke(service, other);
      if (index instanceof Integer i) taken.add(i);
    }
    return taken;
  }
}
