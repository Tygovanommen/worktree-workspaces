package nl.netonic.workspaces.ide.actions;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.core.WorkspaceOps;
import nl.netonic.workspaces.ide.ActionContext;
import nl.netonic.workspaces.ide.Background;
import nl.netonic.workspaces.ide.Notifier;
import nl.netonic.workspaces.ide.Tickets;
import nl.netonic.workspaces.ide.WorkspaceModel;
import nl.netonic.workspaces.ide.WorkspaceSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reverses New Workspace: closes its tabs, removes the tickets' worktrees (branches stay), moves the repos back out of
 * main/ and removes the workspace folder. Refused while a ticket has uncommitted changes.
 */
public final class UndoWorkspaceAction extends WsAction {
  private record Check(List<WorkspaceOps.Problem> problems, Map<String, Path> destinations) {}

  @Override
  public void update(@NotNull AnActionEvent e) {
    Workspace ws = target(e);
    // in the panel's menu only on a workspace row, next to Remove from List
    boolean ticketRow = e.getData(ActionContext.SELECTED_TICKET) != null;
    e.getPresentation().setVisible(!(e.isFromContextMenu() && (ticketRow || ws == null)));
    e.getPresentation().setEnabled(ws != null);
    e.getPresentation().setText(ws != null ? "Undo Workspace " + ws.getName() + "…" : "Undo Workspace…", false);
  }

  /** The workspace selected in the panel, else this window's; never a guess (it moves folders). */
  private static @Nullable Workspace target(AnActionEvent e) {
    Workspace selected = e.getData(ActionContext.SELECTED_WORKSPACE);
    if (selected != null) return selected;
    Ticket t = e.getData(ActionContext.SELECTED_TICKET);
    if (t == null) t = Tickets.current(e.getProject());
    return t != null ? t.getWorkspace() : null;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    Workspace ws = target(e);
    if (ws == null) return;
    // unsaved editor changes must be on disk, or the check can't see them
    FileDocumentManager.getInstance().saveAllDocuments();

    Background.run(project, "Checking " + ws.getName(),
        (ops, log) -> new Check(ops.checkBeforeUndo(ws), WorkspaceOps.undoDestinations(ws)), check -> {
          if (!check.problems().isEmpty()) {
            Messages.showWarningDialog(project, "Commit or discard these changes first:\n\n" + check.problems().stream()
                .map(p -> p.repo() + ": " + p.text()).collect(Collectors.joining("\n")), "Undo Workspace");
            return;
          }
          String repos = check.destinations().entrySet().stream()
              .map(d -> d.getKey() + "  →  " + d.getValue()).collect(Collectors.joining("\n"));
          int ok = Messages.showOkCancelDialog(project, "Undo workspace " + ws.getName() + "?\n\n"
                  + "Its tabs close and every ticket's worktrees are removed; their branches and commits stay in the "
                  + "repo. The repos move back out of main/:\n\n" + (repos.isEmpty() ? "(none)" : repos)
                  + "\n\nThen " + ws.getRoot() + " is removed, unless it still holds files of yours.",
              "Undo Workspace", "Undo Workspace", "Cancel", Messages.getWarningIcon());
          if (ok != Messages.OK) return;
          run(project, ws);
        });
  }

  private static void run(@Nullable Project project, Workspace ws) {
    List<Project> toClose = projectsInside(List.of(ws.getRoot()));
    boolean closingThisWindow = toClose.contains(project);
    for (Project p : toClose) {
      if (!ProjectManager.getInstance().closeAndDispose(p)) return; // closing was cancelled: move nothing
    }
    Project reportTo = closingThisWindow ? null : project;
    Background.run(reportTo, "Undoing workspace " + ws.getName(), (ops, log) -> ops.undoWorkspace(ws, log), result -> {
      WorkspaceSettings.getInstance().removeWorkspace(ws.getRoot());
      WorkspaceModel.getInstance().refresh();
      String details = String.join("\n", result.messages());
      if (result.folderRemoved()) Notifier.info(reportTo, ws.getName() + " undone", details);
      else Notifier.warn(reportTo, ws.getName() + " undone, folder kept", details);
      // this window was the workspace: open the repos it held, as they were before
      if (closingThisWindow) result.repos().values().forEach(p -> ProjectUtil.openOrImport(p, null, true));
    });
  }
}
