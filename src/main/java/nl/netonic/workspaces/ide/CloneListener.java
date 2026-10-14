package nl.netonic.workspaces.ide;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.checkout.CheckoutListener;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.ide.ui.CloneIntoWorkspaceDialog;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs right after Get from VCS clones a repo, before PhpStorm opens it: offers to move the clone into a new or
 * existing workspace and open main instead. Off with Settings › Tools › Worktree Workspaces › Cloning.
 */
public final class CloneListener implements CheckoutListener {
  @Override
  public boolean processCheckedOutDirectory(@NotNull Project project, @NotNull Path directory) {
    if (!WorkspaceSettings.getInstance().isAskAfterClone()) return false;
    // the dialog needs the EDT; anywhere else, let PhpStorm open it as usual
    if (!ApplicationManager.getApplication().isDispatchThread()) return false;
    Path clone = directory.toAbsolutePath().normalize();
    // already inside a workspace (cloned into one by hand): leave it alone
    if (WorkspaceSettings.getInstance().workspaceRoots().stream().anyMatch(clone::startsWith)) return false;

    // a single-repo workspace can't take another repo
    List<Workspace> workspaces = WorkspaceModel.getInstance().snapshot().stream().filter(v -> !v.singleRepo())
        .map(WorkspaceModel.WorkspaceView::workspace).filter(Objects::nonNull).toList();
    Project reportTo = project.isDefault() ? null : project;
    CloneIntoWorkspaceDialog dialog = new CloneIntoWorkspaceDialog(reportTo, clone, workspaces,
        WorkspaceSettings.getInstance().defaultParentFolder());
    if (!dialog.showAndGet()) return false;

    boolean create = dialog.isNewWorkspace();
    String name = dialog.workspaceName();
    Path parent = dialog.parentFolder();
    Workspace existing = dialog.existingWorkspace();
    String repo = dialog.repoName();
    if (create) WorkspaceSettings.getInstance().setLastParentFolder(parent);

    AtomicReference<Path> current = new AtomicReference<>(clone); // moved aside if the workspace takes its name
    Background.run(reportTo, "Moving " + clone.getFileName() + " into a workspace", (ops, log) -> {
      try {
        Workspace ws;
        if (create) {
          current.set(ops.moveAsideForWorkspace(parent, name, clone, log));
          ws = ops.createWorkspace(parent, name);
          ws.getConfig().setBranchPattern(WorkspaceSettings.getInstance().defaultBranchPattern());
          ws.saveConfig();
          WorkspaceSettings.getInstance().addWorkspace(ws.getRoot());
        } else {
          ws = Workspace.load(existing.getRoot());
        }
        ops.adoptRepo(ws, repo, current.get(), clone, log);
        return Workspace.load(ws.getRoot());
      } catch (com.intellij.openapi.progress.ProcessCanceledException e) {
        throw e;
      } catch (Exception e) {
        // the clone stays where it is and opens as usual
        log.warn("Could not move " + current.get().getFileName() + " into a workspace: " + e.getMessage()
            + ". It opens as a normal project.");
        return null;
      }
    }, ws -> {
      if (ws != null) Tickets.open(ws.ticket(Workspace.MAIN));
      else ProjectUtil.openOrImport(current.get(), null, true);
    });
    return true; // opening is ours now
  }
}
