package nl.netonic.workspaces.ide;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataKey;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.TicketRepo;
import nl.netonic.workspaces.core.Workspace;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Works out which workspace / ticket an action applies to: the panel selection first, then the current window. */
public final class ActionContext {
  public static final DataKey<Ticket> SELECTED_TICKET = DataKey.create("nl.netonic.workspaces.selectedTicket");
  public static final DataKey<Workspace> SELECTED_WORKSPACE = DataKey.create("nl.netonic.workspaces.selectedWorkspace");
  public static final DataKey<TicketRepo> SELECTED_REPO = DataKey.create("nl.netonic.workspaces.selectedRepo");

  private ActionContext() {}

  public static @Nullable Ticket ticket(AnActionEvent e) {
    Ticket selected = e.getData(SELECTED_TICKET);
    if (selected != null) return selected;
    return Tickets.current(e.getProject());
  }

  public static @Nullable Workspace workspace(AnActionEvent e) {
    Workspace selected = e.getData(SELECTED_WORKSPACE);
    if (selected != null) return selected;
    Ticket t = ticket(e);
    if (t != null) return t.getWorkspace();
    List<Workspace> all = allWorkspaces();
    return all.size() == 1 ? all.get(0) : null;
  }

  public static List<Workspace> allWorkspaces() {
    List<Workspace> out = new ArrayList<>();
    for (Path root : WorkspaceSettings.getInstance().workspaceRoots()) {
      try {
        out.add(Workspace.load(root));
      } catch (Exception ignored) {
        // broken config: shown as an error node in the panel
      }
    }
    return out;
  }
}
