package nl.netonic.workspaces.ide.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/** Find Action / menu entry: the same searchable list as the toolbar widget. */
public final class SwitchTicketAction extends WsAction {
  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    TicketPopups.switcher(e.getProject(), e.getDataContext()).showInBestPositionFor(e.getDataContext());
  }
}
