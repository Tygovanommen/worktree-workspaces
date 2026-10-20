package nl.netonic.workspaces.ide.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.ide.Tickets;
import nl.netonic.workspaces.ide.WorkspaceModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The ticket popups: {@link #switcher} for the toolbar widget (current workspace in full, the others as submenus)
 * and {@link #allTickets} for Switch Ticket (every ticket in one searchable list).
 */
final class TicketPopups {
  private TicketPopups() {}

  static ListPopup switcher(@Nullable Project project, DataContext context) {
    Ticket current = Tickets.current(project);
    DefaultActionGroup group = new DefaultActionGroup();
    addLoadingRow(group);
    List<WorkspaceModel.WorkspaceView> views = ordered(current);
    boolean others = false;
    for (WorkspaceModel.WorkspaceView wv : views) {
      if (isCurrent(wv, current)) {
        group.addSeparator(wv.name());
        for (WorkspaceModel.TicketView tv : wv.tickets()) group.add(new OpenTicket(tv.ticket(), tv.ticket().getName(), current));
        continue;
      }
      if (!others) group.addSeparator(current != null ? "Other workspaces" : "Workspaces");
      others = true;
      group.add(workspaceMenu(wv, current));
    }
    group.addSeparator();
    group.add(new DumbAwareAction("Search All Tickets…", "Every ticket in every workspace", AllIcons.Actions.Search) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        // after this popup has closed, so the new one gets the focus
        ApplicationManager.getApplication().invokeLater(() -> {
          ListPopup all = allTickets(project);
          if (project != null && !project.isDisposed()) all.showCenteredInCurrentWindow(project);
          else all.showInFocusCenter();
        });
      }
    });
    ActionManager am = ActionManager.getInstance();
    AnAction newTicket = am.getAction("WorktreeWorkspaces.NewTicket");
    AnAction newWorkspace = am.getAction("WorktreeWorkspaces.NewWorkspace");
    if (newTicket != null) group.add(newTicket);
    if (newWorkspace != null) group.add(newWorkspace);
    return JBPopupFactory.getInstance().createActionGroupPopup(
        "Switch Ticket", group, context, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true);
  }

  /** Every ticket as "workspace / ticket", so typing part of either name finds it. */
  static ListPopup allTickets(@Nullable Project project) {
    Ticket current = Tickets.current(project);
    DefaultActionGroup group = new DefaultActionGroup();
    addLoadingRow(group);
    for (WorkspaceModel.WorkspaceView wv : ordered(current)) {
      for (WorkspaceModel.TicketView tv : wv.tickets()) {
        group.add(new OpenTicket(tv.ticket(), wv.name() + " / " + tv.ticket().getName(), current));
      }
    }
    return JBPopupFactory.getInstance().createActionGroupPopup(
        "Switch Ticket", group, DataContext.EMPTY_CONTEXT, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true);
  }

  private static void addLoadingRow(DefaultActionGroup group) {
    WorkspaceModel model = WorkspaceModel.getInstance();
    if (model.isLoaded()) return;
    model.refresh();
    AnAction loading = new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {}
    };
    loading.getTemplatePresentation().setText("Loading tickets…");
    loading.getTemplatePresentation().setEnabled(false);
    group.add(loading);
  }

  /** Loaded workspaces: the current one, then those with an open tab, then A–Z. */
  private static List<WorkspaceModel.WorkspaceView> ordered(@Nullable Ticket current) {
    List<WorkspaceModel.WorkspaceView> out = new ArrayList<>();
    for (WorkspaceModel.WorkspaceView wv : WorkspaceModel.getInstance().snapshot()) {
      if (wv.workspace() != null) out.add(wv);
    }
    out.sort(Comparator.<WorkspaceModel.WorkspaceView>comparingInt(wv -> isCurrent(wv, current) ? 0 : 1)
        .thenComparingInt(wv -> openTabs(wv) > 0 ? 0 : 1)
        .thenComparing(WorkspaceModel.WorkspaceView::name, String.CASE_INSENSITIVE_ORDER));
    return out;
  }

  private static boolean isCurrent(WorkspaceModel.WorkspaceView wv, @Nullable Ticket current) {
    return current != null && current.getWorkspace().equals(wv.workspace());
  }

  private static int openTabs(WorkspaceModel.WorkspaceView wv) {
    int n = 0;
    for (WorkspaceModel.TicketView tv : wv.tickets()) if (Tickets.isOpen(tv.ticket())) n++;
    return n;
  }

  /** A workspace row whose submenu lists its tickets. */
  private static AnAction workspaceMenu(WorkspaceModel.WorkspaceView wv, @Nullable Ticket current) {
    DefaultActionGroup menu = new DefaultActionGroup();
    menu.setPopup(true);
    int open = openTabs(wv);
    menu.getTemplatePresentation().setText(wv.name() + (open > 0 ? "  (" + open + " open)" : ""), false);
    menu.getTemplatePresentation().setIcon(AllIcons.Nodes.Folder);
    for (WorkspaceModel.TicketView tv : wv.tickets()) menu.add(new OpenTicket(tv.ticket(), tv.ticket().getName(), current));
    return menu;
  }

  /** One ticket row in the popup. */
  private static final class OpenTicket extends DumbAwareAction {
    private final Ticket ticket;

    OpenTicket(Ticket ticket, String text, @Nullable Ticket current) {
      // setText(…, false): an underscore in FIX_LOGIN is part of the name, not a mnemonic
      getTemplatePresentation().setText(text, false);
      getTemplatePresentation().setDescription(ticket.getDir().toString());
      getTemplatePresentation().setIcon(
          ticket.equals(current) ? AllIcons.Actions.Checked : ticket.isMain() ? AllIcons.Nodes.HomeFolder : AllIcons.Vcs.Branch);
      this.ticket = ticket;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Tickets.open(ticket);
    }
  }
}
