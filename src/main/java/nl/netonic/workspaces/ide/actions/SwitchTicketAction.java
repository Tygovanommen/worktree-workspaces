package nl.netonic.workspaces.ide.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/** Find Action / menu entry / shortcut: every ticket of every workspace in one searchable list. */
public final class SwitchTicketAction extends WsAction {
  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    TicketPopups.allTickets(e.getProject()).showInBestPositionFor(e.getDataContext());
  }
}
