package nl.netonic.workspaces.ide.actions;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.core.WorkspaceOps;
import nl.netonic.workspaces.ide.ActionContext;
import nl.netonic.workspaces.ide.Background;
import nl.netonic.workspaces.ide.Feedback;
import nl.netonic.workspaces.ide.Tickets;
import nl.netonic.workspaces.ide.ui.NewTicketDialog;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Creates a ticket folder with a worktree per ticked repo and opens it as a tab. */
public final class NewTicketAction extends WsAction {
  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    List<Workspace> all = ActionContext.allWorkspaces();
    if (all.isEmpty()) {
      int answer = Messages.showYesNoDialog(project, "There is no workspace yet. Create one now?", "New Ticket",
          "Create Workspace…", "Cancel", Messages.getQuestionIcon());
      if (answer == Messages.YES) {
        AnAction create = ActionManager.getInstance().getAction("WorktreeWorkspaces.NewWorkspace");
        if (create != null) ActionManager.getInstance().tryToExecute(create, e.getInputEvent(), null, e.getPlace(), true);
      }
      return;
    }
    NewTicketDialog dialog = new NewTicketDialog(project, all, ActionContext.workspace(e));
    if (!dialog.showAndGet()) return;

    Workspace ws = dialog.workspace();
    String folder = dialog.ticketFolder();
    String branch = dialog.branch();
    List<WorkspaceOps.RepoChoice> choices = dialog.choices();
    Background.run(project, "Creating ticket " + folder,
        (ops, log) -> ops.createTicket(ws, folder, branch, choices, log),
        ticket -> {
          Tickets.open(ticket);
          Feedback.ticketCreated(project);
        });
  }
}
