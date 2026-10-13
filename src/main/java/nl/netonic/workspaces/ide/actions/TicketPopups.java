package nl.netonic.workspaces.ide.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.ide.Tickets;
import nl.netonic.workspaces.ide.WorkspaceModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The searchable "switch ticket" list used by the toolbar widget and the Switch Ticket action. */
final class TicketPopups {
  private TicketPopups() {}

  static ListPopup switcher(@Nullable Project project, DataContext context) {
    Ticket current = Tickets.current(project);
    DefaultActionGroup group = new DefaultActionGroup();
    WorkspaceModel model = WorkspaceModel.getInstance();
    if (!model.isLoaded()) model.refresh();
    for (WorkspaceModel.WorkspaceView wv : model.snapshot()) {
      if (wv.workspace() == null) continue;
      group.addSeparator(wv.name());
      for (WorkspaceModel.TicketView tv : wv.tickets()) group.add(new OpenTicket(tv.ticket(), tv.ticket().equals(current)));
    }
    group.addSeparator();
    ActionManager am = ActionManager.getInstance();
    AnAction newTicket = am.getAction("WorktreeWorkspaces.NewTicket");
    AnAction newWorkspace = am.getAction("WorktreeWorkspaces.NewWorkspace");
    if (newTicket != null) group.add(newTicket);
    if (newWorkspace != null) group.add(newWorkspace);
    return JBPopupFactory.getInstance().createActionGroupPopup(
        "Switch Ticket", group, context, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true);
  }

  /** One ticket row in the popup. */
  private static final class OpenTicket extends DumbAwareAction {
    private final Ticket ticket;

    OpenTicket(Ticket ticket, boolean current) {
      super(ticket.getName(),
          ticket.getDir().toString(),
          current ? AllIcons.Actions.Checked : ticket.isMain() ? AllIcons.Nodes.HomeFolder : AllIcons.Vcs.Branch);
      this.ticket = ticket;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Tickets.open(ticket);
    }
  }
}
