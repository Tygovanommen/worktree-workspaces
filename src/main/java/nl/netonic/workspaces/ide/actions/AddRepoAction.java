package nl.netonic.workspaces.ide.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.core.WorkspaceOps;
import nl.netonic.workspaces.ide.ActionContext;
import nl.netonic.workspaces.ide.Background;
import nl.netonic.workspaces.ide.ui.AddRepoDialog;
import nl.netonic.workspaces.ide.ui.RepoSourcesPanel;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Clones a repo into main/, or moves an existing checkout there. */
public final class AddRepoAction extends WsAction {
  @Override
  public void update(@NotNull AnActionEvent e) {
    Workspace ws = ActionContext.workspace(e);
    // a single-repo workspace has its repo in main/ itself: there is no main/<name> for a second one
    e.getPresentation().setEnabled(ws != null && !ws.isSingleRepo());
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    Workspace ws = ActionContext.workspace(e);
    if (ws == null) return;
    AddRepoDialog dialog = new AddRepoDialog(project, ws);
    if (!dialog.showAndGet()) return;
    List<RepoSourcesPanel.RepoSource> sources = dialog.repoSources();

    List<Project> toClose = projectsInside(dialog.foldersToMove());
    if (!toClose.isEmpty()) {
      String names = toClose.stream().map(Project::getName).collect(Collectors.joining(", "));
      int ok = Messages.showOkCancelDialog(project, "These open projects will be closed and moved into "
          + ws.getName() + "/main/:\n\n" + names, "Add Repo", "Close and Move", "Cancel", Messages.getWarningIcon());
      if (ok != Messages.OK) return;
    }
    Project reportTo = toClose.contains(project) ? null : project;
    FileDocumentManager.getInstance().saveAllDocuments(); // e.g. an edited .workspace.json
    ApplicationManager.getApplication().invokeLater(() -> {
      for (Project p : toClose) {
        if (!ProjectManager.getInstance().closeAndDispose(p)) return; // closing was cancelled: move nothing
      }
      Background.run(reportTo, "Adding repo to " + ws.getName(), (ops, log) -> {
        addAll(ops, Workspace.load(ws.getRoot()), sources, Map.of(), log);
        return null;
      }, ignored -> {});
    });
  }

  /**
   * Clones or moves each source; failures become warnings so the others still get added. A null name: main/ itself.
   * {@code cameFrom}: original paths of checkouts that were moved aside first.
   */
  static void addAll(WorkspaceOps ops, Workspace ws, List<RepoSourcesPanel.RepoSource> sources,
                     Map<Path, Path> cameFrom, WorkspaceOps.Log log) {
    for (RepoSourcesPanel.RepoSource s : sources) {
      try {
        if (s.existing() != null) {
          ops.adoptRepo(ws, s.name(), s.existing(), cameFrom.getOrDefault(s.existing(), s.existing()), log);
        }
        else ops.addRepoByClone(ws, s.name(), s.url(), log);
      } catch (ProcessCanceledException ex) {
        throw ex;
      } catch (Exception ex) {
        log.warn((s.name() != null ? s.name() : "main") + ": " + ex.getMessage());
      }
    }
    VirtualFile cfg = LocalFileSystem.getInstance().findFileByNioFile(ws.configFile());
    if (cfg != null) cfg.refresh(true, false);
  }
}
