package nl.netonic.workspaces.ide.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.impl.PresentationFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.ui.popup.ActionPopupOptions;
import com.intellij.ui.popup.PopupFactoryImpl;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.ide.Tickets;
import nl.netonic.workspaces.ide.WorkspaceModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The ticket popups: {@link #switcher} for the toolbar widget (current workspace in full, the others as submenus;
 * typing searches every ticket) and {@link #allTickets} for Switch Ticket (every ticket in one searchable list).
 */
final class TicketPopups {
  private TicketPopups() {}

  static ListPopup switcher(@Nullable Project project, DataContext context) {
    Ticket current = Tickets.current(project);
    DefaultActionGroup group = new DefaultActionGroup();
    addLoadingRow(group);
    List<WorkspaceModel.WorkspaceView> views = ordered(current);
    // typing in the search field swaps the browse rows for every ticket as "workspace / ticket", like Git's popup
    Set<AnAction> browseOnly = new HashSet<>();
    Set<AnAction> searchOnly = new HashSet<>();
    for (WorkspaceModel.WorkspaceView wv : views) {
      for (WorkspaceModel.TicketView tv : wv.tickets()) {
        AnAction row = new OpenTicket(tv.ticket(), wv.name() + " / " + tv.ticket().getName(), current);
        searchOnly.add(row);
        group.add(row);
      }
    }
    boolean others = false;
    for (WorkspaceModel.WorkspaceView wv : views) {
      if (isCurrent(wv, current)) {
        group.addSeparator(wv.name());
        for (WorkspaceModel.TicketView tv : wv.tickets()) {
          AnAction row = new OpenTicket(tv.ticket(), tv.ticket().getName(), current);
          browseOnly.add(row);
          group.add(row);
        }
        continue;
      }
      if (!others) group.addSeparator(current != null ? "Other workspaces" : "Workspaces");
      others = true;
      AnAction menu = workspaceMenu(wv, current);
      browseOnly.add(menu);
      group.add(menu);
    }
    group.addSeparator();
    addFinishRows(group, project, views, current);
    ActionManager am = ActionManager.getInstance();
    AnAction newTicket = am.getAction("WorktreeWorkspaces.NewTicket");
    AnAction newWorkspace = am.getAction("WorktreeWorkspaces.NewWorkspace");
    if (newTicket != null) group.add(newTicket);
    if (newWorkspace != null) group.add(newWorkspace);
    return new SearchPopup(group, context, browseOnly, searchOnly);
  }

  /** "Finish LOGIN-42…" for this window's ticket, and a submenu with the workspace's other tickets. */
  private static void addFinishRows(DefaultActionGroup group, @Nullable Project project,
                                    List<WorkspaceModel.WorkspaceView> views, @Nullable Ticket current) {
    if (current == null) return;
    if (!current.isMain()) group.add(new FinishTicket(project, current, "Finish " + current.getName() + "…"));
    DefaultActionGroup others = new DefaultActionGroup();
    others.setPopup(true);
    others.getTemplatePresentation().setText(current.isMain() ? "Finish Ticket" : "Finish Other Ticket");
    others.getTemplatePresentation().setIcon(AllIcons.Actions.Commit);
    for (WorkspaceModel.WorkspaceView wv : views) {
      if (!isCurrent(wv, current)) continue;
      for (WorkspaceModel.TicketView tv : wv.tickets()) {
        Ticket t = tv.ticket();
        if (!t.isMain() && !t.equals(current)) others.add(new FinishTicket(project, t, t.getName() + "…"));
      }
    }
    if (others.getChildrenCount() > 0) group.add(others);
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
    return new SearchPopup(group, DataContext.EMPTY_CONTEXT, Set.of(), Set.of());
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

  /**
   * "Switch Ticket" popup with the search field always shown, like Git's branches popup. {@code browseOnly} rows
   * hide while the field has text, {@code searchOnly} rows show only then.
   */
  private static final class SearchPopup extends PopupFactoryImpl.ActionGroupPopup {
    private final Set<AnAction> browseOnly;
    private final Set<AnAction> searchOnly;

    SearchPopup(DefaultActionGroup group, DataContext context, Set<AnAction> browseOnly, Set<AnAction> searchOnly) {
      // same options as createActionGroupPopup(…, SPEEDSEARCH, showDisabledActions = true): the loading row shows
      super(null, "Switch Ticket", group, context, ActionPlaces.POPUP, new PresentationFactory(),
          ActionPopupOptions.create(false, false, true, false, -1, false, null), null);
      this.browseOnly = browseOnly;
      this.searchOnly = searchOnly;
      // the super constructor already filtered the rows, before these sets were assigned
      getListModel().refilter();
      setSpeedSearchAlwaysShown();
    }

    @Override
    public boolean shouldBeShowing(Object value) {
      if (!super.shouldBeShowing(value)) return false;
      if (browseOnly == null || !(value instanceof PopupFactoryImpl.ActionItem item)) return true;
      boolean searching = getSpeedSearch().isHoldingFilter();
      return !(searching ? browseOnly : searchOnly).contains(item.getAction());
    }
  }

  /** Opens the Finish dialog for one ticket, after the popup has closed. */
  private static final class FinishTicket extends DumbAwareAction {
    private final @Nullable Project project;
    private final Ticket ticket;

    FinishTicket(@Nullable Project project, Ticket ticket, String text) {
      getTemplatePresentation().setText(text, false);
      getTemplatePresentation().setDescription("Close the tab and remove the ticket's worktrees and folder");
      getTemplatePresentation().setIcon(AllIcons.Actions.Commit);
      this.project = project;
      this.ticket = ticket;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      ApplicationManager.getApplication().invokeLater(() -> {
        if (project == null || !project.isDisposed()) FinishTicketAction.finish(project, ticket);
      });
    }
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
