package nl.netonic.workspaces.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import nl.netonic.workspaces.core.WorkspaceOps;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Runs a {@link WorkspaceOps} job with a progress bar, then reports warnings and refreshes the model. */
public final class Background {
  private static final Logger LOG = Logger.getInstance(Background.class);

  private Background() {}

  public interface Job<T> {
    T run(WorkspaceOps ops, WorkspaceOps.Log log) throws Exception;
  }

  public static <T> void run(@Nullable Project project, String title, Job<T> job, Consumer<T> onSuccessEdt) {
    new Task.Backgroundable(project, title, true) {
      private final List<String> warnings = new ArrayList<>();
      private T result;

      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setIndeterminate(true);
        WorkspaceOps.Log log = new WorkspaceOps.Log() {
          @Override
          public void step(String text) {
            indicator.checkCanceled();
            indicator.setText2(text);
          }

          @Override
          public void warn(String text) {
            warnings.add(text);
          }
        };
        try {
          result = job.run(IdeGit.ops(), log);
        } catch (com.intellij.openapi.progress.ProcessCanceledException e) {
          throw e;
        } catch (Exception e) {
          LOG.warn(title + " failed", e);
          Notifier.error(project, title + " failed",
              e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + " (details in idea.log)");
          WorkspaceModel.getInstance().refresh();
          return;
        }
        if (!warnings.isEmpty()) Notifier.warn(project, title, String.join("\n", warnings));
        Tickets.invalidateCache();
        WorkspaceModel.getInstance().refresh();
        ApplicationManager.getApplication().invokeLater(() -> onSuccessEdt.accept(result), ModalityState.nonModal());
      }
    }.queue();
  }
}
