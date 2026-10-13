package nl.netonic.workspaces.ide.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.treeStructure.Tree;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.ide.ActionContext;
import nl.netonic.workspaces.ide.Tickets;
import nl.netonic.workspaces.ide.WorkspaceModel;
import nl.netonic.workspaces.ide.WorkspaceModel.RepoView;
import nl.netonic.workspaces.ide.WorkspaceModel.TicketView;
import nl.netonic.workspaces.ide.WorkspaceModel.WorkspaceView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

/** The "Workspaces" tool window: workspaces › tickets › repos with branch and git state. */
public final class WorkspacesPanel extends SimpleToolWindowPanel implements Disposable {
  public static final String PLACE = "WorktreeWorkspacesPanel";

  private final Project project;
  private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
  private final DefaultTreeModel model = new DefaultTreeModel(root);
  private final Tree tree = new Tree(model);
  private boolean firstBuild = true;
  private volatile Ticket currentTicket;

  public WorkspacesPanel(Project project) {
    super(true, true);
    this.project = project;
    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.setCellRenderer(new WorkspacesTreeRenderer(() -> currentTicket));
    tree.getEmptyText().setText("Loading workspaces…");

    ActionManager am = ActionManager.getInstance();
    ActionGroup toolbarGroup = (ActionGroup) am.getAction("WorktreeWorkspaces.Toolbar");
    ActionToolbar toolbar = am.createActionToolbar(PLACE, toolbarGroup, true);
    toolbar.setTargetComponent(tree);
    setToolbar(toolbar.getComponent());
    setContent(ScrollPaneFactory.createScrollPane(tree, true));

    PopupHandler.installPopupMenu(tree, "WorktreeWorkspaces.TreePopup", PLACE);
    new DoubleClickListener() {
      @Override
      protected boolean onDoubleClick(@NotNull MouseEvent event) {
        return openSelected();
      }
    }.installOn(tree);
    tree.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER && openSelected()) e.consume();
      }
    });

    ApplicationManager.getApplication().getMessageBus().connect(this)
        .subscribe(WorkspaceModel.TOPIC, (WorkspaceModel.Listener) this::rebuild);
    rebuild();
    WorkspaceModel.getInstance().refresh();
  }

  private boolean openSelected() {
    Ticket t = selectedTicket();
    if (t == null) return false;
    Tickets.open(t);
    return true;
  }

  // ------------------------------------------------------------- selection

  /** The selected ticket, or the ticket of the selected repo row. */
  private @Nullable Ticket selectedTicket() {
    TreePath path = tree.getSelectionPath();
    if (path == null) return null;
    for (Object o : path.getPath()) {
      if (((DefaultMutableTreeNode) o).getUserObject() instanceof TicketView tv) return tv.ticket();
    }
    return null;
  }

  private @Nullable Workspace selectedWorkspace() {
    TreePath path = tree.getSelectionPath();
    if (path == null) return null;
    for (Object o : path.getPath()) {
      if (((DefaultMutableTreeNode) o).getUserObject() instanceof WorkspaceView wv) return wv.workspace();
    }
    return null;
  }

  @Override
  public void uiDataSnapshot(@NotNull DataSink sink) {
    super.uiDataSnapshot(sink);
    Ticket t = selectedTicket();
    Workspace w = selectedWorkspace();
    if (t != null) sink.set(ActionContext.SELECTED_TICKET, t);
    if (w != null) sink.set(ActionContext.SELECTED_WORKSPACE, w);
    TreePath path = tree.getSelectionPath();
    if (path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof RepoView rv) {
      sink.set(ActionContext.SELECTED_REPO, rv.repo());
    }
  }

  // ----------------------------------------------------------------- build

  private void rebuild() {
    WorkspaceModel wm = WorkspaceModel.getInstance();
    Set<String> expanded = new HashSet<>();
    Enumeration<TreePath> e = tree.getExpandedDescendants(new TreePath(root));
    if (e != null) while (e.hasMoreElements()) expanded.add(key(e.nextElement().getLastPathComponent()));
    String selected = tree.getSelectionPath() == null ? null : key(tree.getSelectionPath().getLastPathComponent());

    root.removeAllChildren();
    for (WorkspaceView wv : wm.snapshot()) {
      DefaultMutableTreeNode wn = new DefaultMutableTreeNode(wv);
      root.add(wn);
      for (TicketView tv : wv.tickets()) {
        DefaultMutableTreeNode tn = new DefaultMutableTreeNode(tv);
        wn.add(tn);
        for (RepoView rv : tv.repos()) tn.add(new DefaultMutableTreeNode(rv, false));
      }
    }
    model.reload();

    Ticket current = Tickets.current(project);
    currentTicket = current;
    TreePath toSelect = null;
    for (int i = 0; i < root.getChildCount(); i++) {
      DefaultMutableTreeNode wn = (DefaultMutableTreeNode) root.getChildAt(i);
      TreePath wp = new TreePath(new Object[]{root, wn});
      if (firstBuild || expanded.contains(key(wn))) tree.expandPath(wp);
      for (int j = 0; j < wn.getChildCount(); j++) {
        DefaultMutableTreeNode tn = (DefaultMutableTreeNode) wn.getChildAt(j);
        TreePath tp = wp.pathByAddingChild(tn);
        Ticket t = ((TicketView) tn.getUserObject()).ticket();
        boolean isCurrent = t.equals(current);
        if (expanded.contains(key(tn)) || (firstBuild && (isCurrent || Tickets.isOpen(t)))) tree.expandPath(tp);
        if (key(tn).equals(selected) || (selected == null && isCurrent)) toSelect = tp;
      }
      if (key(wn).equals(selected)) toSelect = wp;
    }
    if (toSelect != null) tree.setSelectionPath(toSelect);

    tree.getEmptyText().clear();
    if (!wm.isLoaded()) {
      tree.getEmptyText().setText("Loading workspaces…");
    } else if (root.getChildCount() == 0) {
      tree.getEmptyText().setText("No workspaces yet");
      tree.getEmptyText().appendLine("Create workspace…", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
          ev -> run("WorktreeWorkspaces.NewWorkspace"));
      tree.getEmptyText().appendLine("Add existing workspace…", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
          ev -> run("WorktreeWorkspaces.AddExistingWorkspace"));
    }
    if (wm.isLoaded()) firstBuild = false;
  }

  private void run(String actionId) {
    AnAction a = ActionManager.getInstance().getAction(actionId);
    if (a != null) ActionManager.getInstance().tryToExecute(a, null, tree, PLACE, true);
  }

  private static String key(Object node) {
    Object u = ((DefaultMutableTreeNode) node).getUserObject();
    if (u instanceof WorkspaceView wv) return "w:" + wv.root();
    if (u instanceof TicketView tv) return "t:" + tv.ticket().getDir();
    if (u instanceof RepoView rv) return "r:" + rv.repo().dir() + "@" + rv.repo().kind();
    return "?";
  }

  @Override
  public void dispose() {
    // message bus connection is bound to this disposable
  }
}
