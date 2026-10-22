package nl.netonic.workspaces.ide;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.projectImport.ProjectAttachProcessor;
import com.intellij.projectImport.ProjectOpenedCallback;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * "Merge in Current" in PhpStorm's Open Project dialog (This Window / New Window / …): opens the project in a new
 * window and merges that into the current window as a tab (macOS).
 *
 * <p>The dialog has one spare button, filled by the first enabled {@code projectAttachProcessor}; registered first,
 * this one takes the place of PhpStorm's Attach while the option is on. Without a path (module detach,
 * {@code canAttachToProject()}) it stays disabled so PhpStorm's own processor still handles those.
 */
public final class OpenAsTab extends ProjectAttachProcessor {
  private static final Logger LOG = Logger.getInstance(OpenAsTab.class);

  @Override
  public boolean isEnabled(@Nullable Project project, @Nullable Path path, @Nullable Project newProject) {
    return SystemInfo.isMac && project != null && path != null && newProject == null
        && WorkspaceSettings.getInstance().isOpenAsTabButton();
  }

  @Override
  public @NotNull String getActionText(@NotNull Project project) {
    return "&Merge in Current";
  }

  @Override
  public @NotNull String getDescription(@NotNull Project project) {
    return "<html><body>Open the project in this window (replacing the current project), in a new window, "
        + "or merge it into this window as a new tab?</body></html>";
  }

  @Override
  public boolean attachToProject(@NotNull Project project, @NotNull Path projectDir,
                                 @Nullable ProjectOpenedCallback callback) {
    // we're inside PhpStorm's own open: open the new window after it has finished, then merge it in
    ApplicationManager.getApplication().invokeLater(() -> {
      try {
        Project opened = ProjectUtil.openOrImport(projectDir, null, true);
        if (opened != null) Tickets.mergeIntoTabLater(opened);
      } catch (Exception e) {
        LOG.warn("Could not open " + projectDir + " as a tab", e);
      }
    }, ModalityState.nonModal());
    return true;
  }
}
