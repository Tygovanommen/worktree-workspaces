package nl.netonic.workspaces.ide.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.ide.ActionContext;
import nl.netonic.workspaces.ide.Background;
import nl.netonic.workspaces.ide.Notifier;
import nl.netonic.workspaces.ide.Tickets;
import nl.netonic.workspaces.ide.ui.FinishTicketDialog;
import org.jetbrains.annotations.NotNull;

/** Checks for uncommitted / unpushed work, closes the tab, removes the worktrees and the ticket folder. */
public final class FinishTicketAction extends WsAction {
  @Override
  public void update(@NotNull AnActionEvent e) {
    Ticket t = ActionContext.ticket(e);
    boolean enabled = t != null && !t.isMain();
    e.getPresentation().setEnabled(enabled);
    e.getPresentation().setText(enabled ? "Finish Ticket " + t.getName() + "…" : "Finish Ticket…");
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    Ticket ticket = ActionContext.ticket(e);
    if (ticket == null || ticket.isMain()) return;
    // unsaved editor changes must be on disk, or the check below can't see them
    FileDocumentManager.getInstance().saveAllDocuments();

    Background.run(project, "Checking " + ticket.displayName(), (ops, log) -> ops.checkBeforeFinish(ticket), problems -> {
      FinishTicketDialog dialog = new FinishTicketDialog(project, ticket, problems);
      if (!dialog.showAndGet()) return;
      Project open = Tickets.findOpenProject(ticket);
      boolean finishingThisWindow = open != null && open == project;
      if (open != null && !ProjectManager.getInstance().closeAndDispose(open)) return; // closing was cancelled
      Project reportTo = finishingThisWindow ? null : project;

      Background.run(reportTo, "Finishing " + ticket.displayName(),
          (ops, log) -> ops.finishTicket(ticket, dialog.force(), dialog.deleteMergedBranches(), log),
          result -> {
            String details = String.join("\n", result.messages());
            if (result.folderRemoved()) Notifier.info(reportTo, ticket.displayName() + " finished", details);
            else Notifier.warn(reportTo, ticket.displayName() + " not fully removed", details);
            if (finishingThisWindow) Tickets.open(ticket.getWorkspace().ticket(Workspace.MAIN));
          });
    });
  }
}
