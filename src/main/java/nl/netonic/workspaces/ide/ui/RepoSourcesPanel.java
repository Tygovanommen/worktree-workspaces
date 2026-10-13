package nl.netonic.workspaces.ide.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
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
 * checkout folder (moved into main/&lt;name&gt;). Empty rows are ignored.
 */
public final class RepoSourcesPanel extends JPanel {

  /** One repo to add. {@code existing} is set for folders, {@code url} for clones. */
  public record RepoSource(String name, @Nullable Path existing, @Nullable String url) {}

  private record Row(JBTextField name, TextFieldWithBrowseButton source) {}

  private final Project project;
  private final List<Row> rows = new ArrayList<>();
  private final JPanel grid = new JPanel(new GridBagLayout());
  private int y;

  public RepoSourcesPanel(@Nullable Project project, List<String> initialNames, boolean allowMore) {
    super(new GridBagLayout());
    this.project = project;
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = 0;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1;
    add(grid, c);

    GridBagConstraints h = new GridBagConstraints();
    h.gridy = y++;
    h.anchor = GridBagConstraints.WEST;
    h.insets = JBUI.insets(0, 0, 2, 8);
    h.gridx = 0;
    grid.add(muted("Name in workspace"), h);
    h.gridx = 1;
    grid.add(muted("Git URL to clone, or an existing checkout folder"), h);

    for (String n : initialNames) addRow(n);
    if (allowMore) {
      ActionLink more = new ActionLink("Add another repo", e -> {
        addRow("");
        revalidate();
        repaint();
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

  private void addRow(String name) {
    JBTextField nameField = new JBTextField(name, 10);
    TextFieldWithBrowseButton source = new TextFieldWithBrowseButton();
    source.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Existing Checkout"));
    source.getTextField().setColumns(34);
    GridBagConstraints c = new GridBagConstraints();
    c.gridy = y++;
    c.insets = JBUI.insets(2, 0, 2, 8);
    c.gridx = 0;
    c.anchor = GridBagConstraints.WEST;
    grid.add(nameField, c);
    c.gridx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1;
    grid.add(source, c);
    rows.add(new Row(nameField, source));
  }

  public List<RepoSource> sources() {
    List<RepoSource> out = new ArrayList<>();
    for (Row r : rows) {
      String name = r.name().getText().trim();
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
    for (Row r : rows) {
      String name = r.name().getText().trim();
      String src = r.source().getText().trim();
      if (src.isEmpty()) continue;
      if (!Workspace.isValidName(name)) return new ValidationInfo("Repo name: letters, digits, '.', '-', '_'", r.name());
      if (!seen.add(name)) return new ValidationInfo("'" + name + "' is used twice", r.name());
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
