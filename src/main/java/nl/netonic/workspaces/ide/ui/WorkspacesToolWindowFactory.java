package nl.netonic.workspaces.ide.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class WorkspacesToolWindowFactory implements ToolWindowFactory, DumbAware {
  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    WorkspacesPanel panel = new WorkspacesPanel(project);
    Content content = ContentFactory.getInstance().createContent(panel, "", false);
    Disposer.register(content, panel);
    toolWindow.getContentManager().addContent(content);
  }
}
