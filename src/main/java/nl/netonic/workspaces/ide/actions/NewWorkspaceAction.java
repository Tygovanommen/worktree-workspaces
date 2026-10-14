package nl.netonic.workspaces.ide.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.ide.Background;
import nl.netonic.workspaces.ide.Tickets;
import nl.netonic.workspaces.ide.WorkspaceSettings;
import nl.netonic.workspaces.ide.ui.NewWorkspaceDialog;
import nl.netonic.workspaces.ide.ui.RepoSourcesPanel;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Creates &lt;location&gt;/&lt;name&gt;/ with main/ holding the cloned or moved repos, then opens main as a tab. */
public final class NewWorkspaceAction extends WsAction {
  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    WorkspaceSettings settings = WorkspaceSettings.getInstance();
    NewWorkspaceDialog dialog = new NewWorkspaceDialog(project, settings.defaultParentFolder());
    if (!dialog.showAndGet()) return;

    String name = dialog.workspaceName();
    Path parent = dialog.parentFolder();
    String pattern = dialog.branchPattern();
    List<RepoSourcesPanel.RepoSource> sources = dialog.repoSources();
    settings.setLastParentFolder(parent);

    List<Project> toClose = projectsInside(dialog.foldersToMove());
    if (!toClose.isEmpty()) {
      String names = toClose.stream().map(Project::getName).collect(Collectors.joining(", "));
      int ok = Messages.showOkCancelDialog(project,
          "These projects are open and their folders will be moved into " + name + "/main/:\n\n" + names
              + "\n\nThey will be closed first.", "New Workspace", "Close and Move", "Cancel", Messages.getWarningIcon());
      if (ok != Messages.OK) return;
    }
    Project reportTo = toClose.contains(project) ? null : project;

    ApplicationManager.getApplication().invokeLater(() -> {
      for (Project p : toClose) {
        if (!ProjectManager.getInstance().closeAndDispose(p)) return; // closing was cancelled: move nothing
      }
      Background.run(reportTo, "Creating workspace " + name, (ops, log) -> {
        List<RepoSourcesPanel.RepoSource> moved = new ArrayList<>();
        Map<Path, Path> cameFrom = new HashMap<>(); // moved aside → original path, remembered for Undo Workspace
        for (RepoSourcesPanel.RepoSource s : sources) {
          if (s.existing() == null) {
            moved.add(s);
            continue;
          }
          Path now = ops.moveAsideForWorkspace(parent, name, s.existing(), log);
          cameFrom.put(now, s.existing());
          moved.add(new RepoSourcesPanel.RepoSource(s.name(), now, null));
        }
        Workspace ws = ops.createWorkspace(parent, name);
        if (!pattern.isEmpty()) {
          ws.getConfig().setBranchPattern(pattern);
          ws.saveConfig();
        }
        WorkspaceSettings.getInstance().addWorkspace(ws.getRoot());
        AddRepoAction.addAll(ops, ws, moved, cameFrom, log);
        return Workspace.load(ws.getRoot());
      }, ws -> Tickets.open(ws.ticket(Workspace.MAIN)));
    });
  }
}
