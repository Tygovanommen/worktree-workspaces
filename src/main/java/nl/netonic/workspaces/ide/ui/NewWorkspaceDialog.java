package nl.netonic.workspaces.ide.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.core.WorkspaceConfig;
import nl.netonic.workspaces.core.WorkspaceOps;
import nl.netonic.workspaces.ide.WorkspaceSettings;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Name + location of a new workspace and the repos to put in main/. */
public final class NewWorkspaceDialog extends DialogWrapper {
  private final JBTextField nameField = new JBTextField(20);
  private final TextFieldWithBrowseButton locationField = new TextFieldWithBrowseButton();
  private final JBTextField patternField =
      new JBTextField(WorkspaceSettings.getInstance().defaultBranchPattern(), 20);
  private final RepoSourcesPanel repos;

  public NewWorkspaceDialog(@Nullable Project project, Path defaultParent) {
    super(project, true);
    setTitle("New Workspace");
    setOKButtonText("Create");
    nameField.getEmptyText().setText("klant-x");
    locationField.setText(defaultParent.toString());
    locationField.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Workspace Location"));
    repos = new RepoSourcesPanel(project, List.of("backend", "frontend"), true);
    init();
  }

  public String workspaceName() {
    return nameField.getText().trim();
  }

  public Path parentFolder() {
    String t = locationField.getText().trim();
    if (t.startsWith("~/")) t = System.getProperty("user.home") + t.substring(1);
    return Path.of(t).toAbsolutePath().normalize();
  }

  public String branchPattern() {
    return patternField.getText().trim();
  }

  public List<RepoSourcesPanel.RepoSource> repoSources() {
    return repos.sources();
  }

  public List<Path> foldersToMove() {
    return repos.foldersToMove();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JBLabel note = new JBLabel("<html>Existing checkouts are <b>moved</b> into &lt;workspace&gt;/main/&lt;name&gt;. "
        + "Git URLs are cloned there.<br>Each ticket later gets its own folder next to main/.</html>");
    note.setForeground(UIUtil.getContextHelpForeground());
    note.setFont(JBUI.Fonts.smallFont());
    patternField.getEmptyText().setText(WorkspaceConfig.DEFAULT_BRANCH_PATTERN);
    JPanel panel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Name:", nameField)
        .addLabeledComponent("Location:", locationField)
        .addLabeledComponent("Branch pattern:", patternField)
        .addTooltip("{ticket} is replaced by the ticket key, e.g. feature/LOGIN-42")
        .addVerticalGap(8)
        .addLabeledComponent("Repos:", repos, true)
        .addVerticalGap(6)
        .addComponent(note)
        .getPanel();
    panel.setPreferredSize(JBUI.size(640, -1));
    return panel;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return nameField;
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    String name = workspaceName();
    if (!Workspace.isValidName(name)) return new ValidationInfo("Use letters, digits, '.', '-' or '_'", nameField);
    String location = locationField.getText().trim();
    if (location.isEmpty()) return new ValidationInfo("Choose a folder", locationField);
    if (!location.startsWith("~/") && !Path.of(location).isAbsolute()) {
      return new ValidationInfo("Use a full path, e.g. ~/PhpstormProjects", locationField);
    }
    Path parent;
    try {
      parent = parentFolder();
    } catch (Exception e) {
      return new ValidationInfo("Invalid folder", locationField);
    }
    if (!Files.isDirectory(parent)) return new ValidationInfo("Folder does not exist", locationField);
    Path root = parent.resolve(name);
    // a checkout with the workspace's name is moved aside first, then into main/
    boolean rootIsMoved = foldersToMove().stream().anyMatch(p -> WorkspaceOps.isSameFolder(root, p));
    if (Files.exists(root) && !rootIsMoved) return new ValidationInfo(root + " already exists", nameField);
    if (!branchPattern().isEmpty() && !branchPattern().contains("{ticket}")) {
      return new ValidationInfo("The pattern must contain {ticket}", patternField);
    }
    for (Path p : foldersToMove()) {
      if (root.startsWith(p) && !WorkspaceOps.isSameFolder(root, p)) {
        return new ValidationInfo("The workspace can't be inside " + p, locationField);
      }
    }
    return repos.validate(Set.of());
  }
}
