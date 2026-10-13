package nl.netonic.workspaces.ide.actions;

import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.TicketRepo;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.ide.ActionContext;
import nl.netonic.workspaces.ide.Background;
import nl.netonic.workspaces.ide.IdeGit;
import nl.netonic.workspaces.ide.ProjectRoots;
import nl.netonic.workspaces.ide.Tickets;
import nl.netonic.workspaces.ide.WorkspaceModel;
import nl.netonic.workspaces.ide.WorkspaceSettings;
import nl.netonic.workspaces.core.IdeaFiles;
import nl.netonic.workspaces.core.RepoStatus;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/** Small actions used by the panel toolbar and context menu. */
public final class SimpleActions {
  private SimpleActions() {}

  /** Re-scan all workspaces and git states. */
  public static final class Refresh extends WsAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Tickets.invalidateCache();
      WorkspaceModel.getInstance().refresh();
    }
  }

  /** Open (or focus) the selected ticket. */
  public static final class OpenSelected extends WsAction {
    @Override
    public void update(@NotNull AnActionEvent e) {
      Ticket t = e.getData(ActionContext.SELECTED_TICKET);
      e.getPresentation().setEnabledAndVisible(t != null);
      if (t != null) e.getPresentation().setText(Tickets.isOpen(t) ? "Switch to Tab" : "Open as Tab");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Ticket t = e.getData(ActionContext.SELECTED_TICKET);
      if (t != null) Tickets.open(t);
    }
  }

  /** Reveal the selected repo / ticket / workspace folder in Finder. */
  public static final class Reveal extends WsAction {
    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setText(RevealFileAction.getActionName());
      e.getPresentation().setEnabledAndVisible(RevealFileAction.isSupported() && target(e) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Path p = target(e);
      if (p != null) RevealFileAction.openDirectory(p);
    }

    private static Path target(AnActionEvent e) {
      TicketRepo r = e.getData(ActionContext.SELECTED_REPO);
      if (r != null) return r.dir();
      Ticket t = e.getData(ActionContext.SELECTED_TICKET);
      if (t != null) return t.getDir();
      Workspace w = e.getData(ActionContext.SELECTED_WORKSPACE);
      return w != null ? w.getRoot() : null;
    }
  }

  /** Open .workspace.json in the editor. */
  public static final class EditConfig extends WsAction {
    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(e.getProject() != null && ActionContext.workspace(e) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Project project = e.getProject();
      Workspace ws = ActionContext.workspace(e);
      if (project == null || ws == null) return;
      VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(ws.configFile());
      if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true);
    }
  }

  /** Forget a workspace (files stay on disk). */
  public static final class Remove extends WsAction {
    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabledAndVisible(e.getData(ActionContext.SELECTED_WORKSPACE) != null
          && e.getData(ActionContext.SELECTED_TICKET) == null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Workspace ws = e.getData(ActionContext.SELECTED_WORKSPACE);
      if (ws == null) return;
      int ok = Messages.showOkCancelDialog(e.getProject(),
          "Remove " + ws.getName() + " from the list? Nothing is deleted on disk.", "Remove Workspace",
          "Remove", "Cancel", Messages.getQuestionIcon());
      if (ok != Messages.OK) return;
      WorkspaceSettings.getInstance().removeWorkspace(ws.getRoot());
      WorkspaceModel.getInstance().refresh();
    }
  }

  /**
   * On an attached repo row: give this ticket its own worktree of that repo too
   * (e.g. PAY-7 turns out to need a frontend change as well).
   */
  public static final class AddWorktreeToTicket extends WsAction {
    @Override
    public void update(@NotNull AnActionEvent e) {
      TicketRepo r = e.getData(ActionContext.SELECTED_REPO);
      Ticket t = e.getData(ActionContext.SELECTED_TICKET);
      boolean ok = r != null && t != null && !t.isMain() && r.kind() == TicketRepo.Kind.ATTACHED;
      e.getPresentation().setEnabledAndVisible(ok);
      if (ok) e.getPresentation().setText("Add " + r.name() + " to " + t.getName());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      TicketRepo r = e.getData(ActionContext.SELECTED_REPO);
      Ticket t = e.getData(ActionContext.SELECTED_TICKET);
      if (r == null || t == null) return;
      Background.run(e.getProject(), "Adding " + r.name() + " to " + t.getName(), (ops, log) -> {
        ops.addWorktree(t.getWorkspace(), t, r.name(), ticketBranch(t), null, log);
        // an open project is updated in memory instead; rewriting its .idea underneath it would clash
        if (Tickets.findOpenProject(t) == null) IdeaFiles.prepare(t);
        return t;
      }, ticket -> {
        Project open = Tickets.findOpenProject(ticket);
        if (open != null) ProjectRoots.syncLater(open, ticket);
      });
    }

    /** The branch the ticket's other worktrees are on, else the pattern. */
    private static String ticketBranch(Ticket t) {
      for (TicketRepo other : t.repos()) {
        if (other.kind() != TicketRepo.Kind.WORKTREE) continue;
        RepoStatus st = RepoStatus.read(IdeGit.runner(), other.dir());
        if (st.branch() != null && !st.branch().startsWith("(")) return st.branch();
      }
      return t.getWorkspace().getConfig().branchFor(t.getName());
    }
  }

  /** macOS: merge new ticket windows into tabs of one window. */
  public static final class MergeIntoTabs extends ToggleAction implements DumbAware {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      super.update(e);
      e.getPresentation().setVisible(SystemInfo.isMac);
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
      return WorkspaceSettings.getInstance().isMergeWindowsIntoTabs();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
      WorkspaceSettings.getInstance().setMergeWindowsIntoTabs(state);
    }
  }
}
