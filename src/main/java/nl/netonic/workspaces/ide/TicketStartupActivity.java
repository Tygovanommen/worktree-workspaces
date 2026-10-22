package nl.netonic.workspaces.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import nl.netonic.workspaces.core.Ticket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * When a ticket project opens: remember its workspace, make sure attached repos + Git roots are set, and (the first
 * time, if enabled) open a terminal per repo; give it a toolbar color no other open tab has; let Markdown ▶ commands
 * run in the README's folder.
 */
public final class TicketStartupActivity implements ProjectActivity {
  @Override
  public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
    Tickets.invalidateCache();
    Ticket ticket = Tickets.current(project);
    if (ticket != null) {
      WorkspaceSettings.getInstance().addWorkspace(ticket.getWorkspace().getRoot());
      ProjectRoots.syncLater(project, ticket);
      RepoTerminals.openOnFirstOpen(project, ticket);
      TicketColors.applyLater(project, ticket);
      MarkdownRunFolder.apply();
    }
    WorkspaceModel.getInstance().refreshIfStale(2_000);
    WorkspaceModel.getInstance().notifyChanged();
    return Unit.INSTANCE;
  }
}
