package nl.netonic.workspaces.ide.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import nl.netonic.workspaces.core.RepoStatus;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.TicketRepo;
import nl.netonic.workspaces.ide.Tickets;
import nl.netonic.workspaces.ide.WorkspaceModel.RepoView;
import nl.netonic.workspaces.ide.WorkspaceModel.TicketView;
import nl.netonic.workspaces.ide.WorkspaceModel.WorkspaceView;
import org.jetbrains.annotations.NotNull;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Color;
import java.util.function.Supplier;

/** Rendering of workspace, ticket and repo rows. */
final class WorkspacesTreeRenderer extends ColoredTreeCellRenderer {
  private static final SimpleTextAttributes CHANGED =
      new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new JBColor(new Color(0xB2_6B_00), new Color(0xE0_A0_40)));
  private static final SimpleTextAttributes SMALL_GRAY = SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES;

  private final Supplier<Ticket> currentTicket;

  WorkspacesTreeRenderer(Supplier<Ticket> currentTicket) {
    this.currentTicket = currentTicket;
  }

  @Override
  public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded,
                                    boolean leaf, int row, boolean hasFocus) {
    Object u = ((DefaultMutableTreeNode) value).getUserObject();
    if (u instanceof WorkspaceView wv) renderWorkspace(wv);
    else if (u instanceof TicketView tv) renderTicket(tv);
    else if (u instanceof RepoView rv) renderRepo(rv);
  }

  private void renderWorkspace(WorkspaceView wv) {
    setIcon(AllIcons.Nodes.ModuleGroup);
    append(wv.name(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
    append("  " + tildePath(wv.root().toString()), SMALL_GRAY);
    if (wv.error() != null) append("  " + wv.error(), SimpleTextAttributes.ERROR_ATTRIBUTES);
  }

  private void renderTicket(TicketView tv) {
    Ticket t = tv.ticket();
    boolean current = t.equals(currentTicket.get());
    boolean open = current || Tickets.isOpen(t);
    setIcon(t.isMain() ? AllIcons.Nodes.HomeFolder : AllIcons.Vcs.Branch);
    append(t.getName(), current ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
        : open ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES);
    if (current) append("  this tab", SMALL_GRAY);
    else if (open) append("  open", SMALL_GRAY);

    int changed = 0;
    int unpushed = 0;
    for (RepoView rv : tv.repos()) {
      if (rv.status() == null || rv.repo().kind() == TicketRepo.Kind.ATTACHED) continue;
      changed += rv.status().changed();
      unpushed += Math.max(rv.status().unpushed(), rv.status().ahead());
    }
    if (changed > 0) append("  " + changed + " changed", CHANGED);
    if (unpushed > 0) append("  ↑" + unpushed, SMALL_GRAY);
    setToolTipText(t.getDir().toString());
  }

  private void renderRepo(RepoView rv) {
    TicketRepo r = rv.repo();
    RepoStatus st = rv.status();
    switch (r.kind()) {
      case MISSING -> {
        setIcon(AllIcons.Nodes.Folder);
        append(r.name(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        append("  not in main/", SimpleTextAttributes.ERROR_ATTRIBUTES);
      }
      case ATTACHED -> {
        setIcon(AllIcons.Nodes.Folder);
        append(r.name(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        append("  main checkout" + (st != null && st.branch() != null ? " · " + st.branch() : ""), SMALL_GRAY);
      }
      default -> {
        setIcon(AllIcons.Nodes.Module);
        append(r.name());
        if (st == null) return;
        if (st.error() != null) {
          append("  " + st.error(), SimpleTextAttributes.ERROR_ATTRIBUTES);
          return;
        }
        append("  " + (st.branch() == null ? "?" : st.branch()), SMALL_GRAY);
        String summary = st.summary();
        append("  " + summary, st.changed() > 0 ? CHANGED : SMALL_GRAY);
        if (st.upstream() == null && r.kind() == TicketRepo.Kind.WORKTREE) append("  no upstream", SMALL_GRAY);
      }
    }
    setToolTipText(r.dir().toString());
  }

  private static String tildePath(String p) {
    String home = System.getProperty("user.home");
    return home != null && p.startsWith(home) ? "~" + p.substring(home.length()) : p;
  }
}
