package nl.netonic.workspaces.ide.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.core.WorkspaceConfig;
import nl.netonic.workspaces.ide.WorkspaceModel;
import nl.netonic.workspaces.ide.WorkspaceSettings;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/** Registers a folder that already holds a .workspace.json (e.g. made on another machine). */
public final class AddExistingWorkspaceAction extends WsAction {
  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    VirtualFile dir = FileChooser.chooseFile(
        FileChooserDescriptorFactory.singleDir().withTitle("Select Workspace Folder")
            .withDescription("The folder that contains " + WorkspaceConfig.FILE_NAME), project, null);
    if (dir == null) return;
    Path root = dir.toNioPath();
    if (!Workspace.isWorkspaceRoot(root)) {
      Workspace found = Workspace.findContaining(root);
      if (found == null) {
        Messages.showErrorDialog(project, root + " has no " + WorkspaceConfig.FILE_NAME + ".", "Add Existing Workspace");
        return;
      }
      root = found.getRoot();
    }
    WorkspaceSettings.getInstance().addWorkspace(root);
    WorkspaceModel.getInstance().refresh();
  }
}
