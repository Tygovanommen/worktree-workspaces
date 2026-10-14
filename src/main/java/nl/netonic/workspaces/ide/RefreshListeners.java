package nl.netonic.workspaces.ide;

import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectCloseListener;
import com.intellij.openapi.wm.IdeFrame;
import org.jetbrains.annotations.NotNull;

/** Keeps the panel current: re-scan when PhpStorm gets focus, re-render when a tab closes. */
public final class RefreshListeners {
  private RefreshListeners() {}

  public static final class OnActivation implements ApplicationActivationListener {
    @Override
    public void applicationActivated(@NotNull IdeFrame ideFrame) {
      if (WorkspaceSettings.getInstance().isRefreshOnFocus()) WorkspaceModel.getInstance().refreshIfStale(10_000);
    }
  }

  public static final class OnProjectClose implements ProjectCloseListener {
    @Override
    public void projectClosed(@NotNull Project project) {
      WorkspaceModel.getInstance().notifyChanged();
    }
  }
}
