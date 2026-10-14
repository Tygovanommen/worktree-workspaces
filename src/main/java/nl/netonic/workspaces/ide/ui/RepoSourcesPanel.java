package nl.netonic.workspaces.ide.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.popup.IconButton;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import nl.netonic.workspaces.core.Workspace;
import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rows of "repo name  +  source", where the source is either a git URL (cloned) or an existing
 * checkout folder (moved into main/&lt;name&gt;). Empty rows are ignored; each row can be removed while more than one is
 * left. With {@code oneRepoInMain}, a single repo needs no name: it goes in main/ itself, so a lone row hides its name.
 */
public final class RepoSourcesPanel extends JPanel {

  /** One repo to add. {@code existing} is set for folders, {@code url} for clones. */
  public record RepoSource(String name, @Nullable Path existing, @Nullable String url) {}

  private record Row(JBTextField name, TextFieldWithBrowseButton source, InplaceButton remove) {}

  private final Project project;
  private final boolean oneRepoInMain;
  private final List<Row> rows = new ArrayList<>();
  private final JPanel grid = new JPanel(new GridBagLayout());

  public RepoSourcesPanel(@Nullable Project project, List<String> initialNames, boolean allowMore, boolean oneRepoInMain) {
    super(new GridBagLayout());
    this.project = project;
    this.oneRepoInMain = oneRepoInMain;
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = 0;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1;
    add(grid, c);

    for (String n : initialNames.isEmpty() ? List.of("") : initialNames) rows.add(newRow(n));
    layoutRows();
    if (allowMore) {
      ActionLink more = new ActionLink("Add another repo", e -> {
        rows.add(newRow(""));
        layoutRows();
      });
      more.setIcon(AllIcons.General.Add);
      c.gridy = 1;
      c.fill = GridBagConstraints.NONE;
      c.anchor = GridBagConstraints.WEST;
      c.insets = JBUI.insetsTop(4);
      add(more, c);
    }
  }

  private static JBLabel muted(String text) {
    JBLabel l = new JBLabel(text);
    l.setForeground(UIUtil.getContextHelpForeground());
    return l;
  }

  private Row newRow(String name) {
    JBTextField nameField = new JBTextField(name, 10);
    TextFieldWithBrowseButton source = new TextFieldWithBrowseButton();
    source.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Existing Checkout"));
    source.getTextField().setColumns(34);
    Row[] self = new Row[1];
    InplaceButton remove = new InplaceButton(
        new IconButton("Remove this repo", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered), e -> {
          rows.remove(self[0]);
          layoutRows();
        });
    self[0] = new Row(nameField, source, remove);
    return self[0];
  }

  /** Re-adds the rows: names hidden for a lone row in main/, remove buttons only while there are several. */
  private void layoutRows() {
    grid.removeAll();
    boolean names = showNames();
    GridBagConstraints h = new GridBagConstraints();
    h.gridy = 0;
    h.anchor = GridBagConstraints.WEST;
    h.insets = JBUI.insets(0, 0, 2, 8);
    if (names) {
      h.gridx = 0;
      grid.add(muted("Name in workspace"), h);
    }
    h.gridx = 1;
    grid.add(muted(names ? "Git URL to clone, or an existing checkout folder"
        : "Git URL to clone, or an existing checkout folder (goes in main/ itself)"), h);

    for (int i = 0; i < rows.size(); i++) {
      Row r = rows.get(i);
      GridBagConstraints c = new GridBagConstraints();
      c.gridy = i + 1;
      c.insets = JBUI.insets(2, 0, 2, 8);
      c.anchor = GridBagConstraints.WEST;
      if (names) {
        c.gridx = 0;
        grid.add(r.name(), c);
      }
      c.gridx = 1;
      c.fill = GridBagConstraints.HORIZONTAL;
      c.weightx = 1;
      grid.add(r.source(), c);
      if (rows.size() > 1) {
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.insets = JBUI.insets(2, 0);
        grid.add(r.remove(), c);
      }
    }
    revalidate();
    repaint();
  }

  private boolean showNames() {
    return !(oneRepoInMain && rows.size() == 1);
  }

  /**
   * True when the repos go in main/ itself: allowed by the dialog and at most one row has a source.
   * Its {@link #sources()} entry then has a null name.
   */
  public boolean isOneRepoInMain() {
    if (!oneRepoInMain) return false;
    int filled = 0;
    for (Row r : rows) if (!r.source().getText().isBlank()) filled++;
    return filled <= 1;
  }

  public List<RepoSource> sources() {
    List<RepoSource> out = new ArrayList<>();
    boolean inMain = isOneRepoInMain();
    for (Row r : rows) {
      String name = inMain ? null : r.name().getText().trim();
      String src = r.source().getText().trim();
      if (src.isEmpty()) continue;
      Path p = asExistingFolder(src);
      out.add(p != null ? new RepoSource(name, p, null) : new RepoSource(name, null, src));
    }
    return out;
  }

  private static @Nullable Path asExistingFolder(String text) {
    if (text.contains("://") || text.startsWith("git@")) return null;
    try {
      String expanded = text.startsWith("~/") ? System.getProperty("user.home") + text.substring(1) : text;
      Path p = Path.of(expanded).toAbsolutePath().normalize();
      return Files.isDirectory(p) ? p : null;
    } catch (Exception e) {
      return null;
    }
  }

  /** Checks names and sources; {@code existingNames} are repos already in the workspace. */
  public @Nullable ValidationInfo validate(Set<String> existingNames) {
    Set<String> seen = new HashSet<>(existingNames);
    boolean inMain = isOneRepoInMain();
    for (Row r : rows) {
      String name = r.name().getText().trim();
      String src = r.source().getText().trim();
      if (src.isEmpty()) continue;
      if (!inMain) {
        if (!Workspace.isValidName(name)) return new ValidationInfo("Repo name: letters, digits, '.', '-', '_'", r.name());
        if (!seen.add(name)) return new ValidationInfo("'" + name + "' is used twice", r.name());
      }
      Path p = asExistingFolder(src);
      if (p != null) {
        if (!Files.exists(p.resolve(".git"))) return new ValidationInfo("Not a git checkout", r.source());
        if (Files.isRegularFile(p.resolve(".git"))) {
          return new ValidationInfo("This is a worktree; pick the main clone", r.source());
        }
      } else if (!(src.contains("://") || src.startsWith("git@") || src.endsWith(".git"))) {
        return new ValidationInfo("Folder not found, and it doesn't look like a git URL", r.source());
      }
    }
    return null;
  }

  /** Existing folders that are moved; used to warn about open projects. */
  public List<Path> foldersToMove() {
    List<Path> out = new ArrayList<>();
    for (RepoSource s : sources()) if (s.existing() != null) out.add(s.existing());
    return out;
  }
}
