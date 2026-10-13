package nl.netonic.workspaces.ide.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.wm.impl.ExpandableComboAction;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.ide.Tickets;
import nl.netonic.workspaces.ide.WorkspaceSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Main toolbar dropdown next to the project widget: "klant-x / LOGIN-42 ▾". */
public final class TicketSwitcherWidgetAction extends ExpandableComboAction implements DumbAware {
  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Ticket t = Tickets.current(e.getProject());
    boolean anyWorkspace = t != null || !WorkspaceSettings.getInstance().workspaceRoots().isEmpty();
    e.getPresentation().setEnabledAndVisible(anyWorkspace);
    if (!anyWorkspace) return;
    e.getPresentation().setText(t != null ? t.getWorkspace().getName() + " / " + t.getName() : "Workspaces");
    e.getPresentation().setDescription("Switch ticket");
    e.getPresentation().setIcon(t != null && t.isMain() ? AllIcons.Nodes.HomeFolder : AllIcons.Vcs.Branch);
  }

  @Override
  public @Nullable JBPopup createPopup(@NotNull AnActionEvent e) {
    return TicketPopups.switcher(e.getProject(), e.getDataContext());
  }
}
