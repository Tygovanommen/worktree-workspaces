package nl.netonic.workspaces.ide.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import nl.netonic.workspaces.core.Workspace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JList;
import java.nio.file.Path;
import java.util.List;

/** Right after Get from VCS: put the fresh clone in a new or existing workspace, or open it as usual (Cancel). */
public final class CloneIntoWorkspaceDialog extends DialogWrapper {
  private final String folderName;
  private final JBRadioButton newWs = new JBRadioButton("New workspace");
  private final JBRadioButton existingWs = new JBRadioButton("Add to workspace");
  private final JBTextField nameField = new JBTextField(20);
  private final TextFieldWithBrowseButton locationField = new TextFieldWithBrowseButton();
  private final ComboBox<Workspace> workspaceCombo;
  private final JBTextField repoField = new JBTextField(20);

  public CloneIntoWorkspaceDialog(@Nullable Project project, Path clone, List<Workspace> workspaces, Path defaultParent) {
    super(project, true);
    folderName = clone.getFileName().toString();
    setTitle("Put " + folderName + " in a Workspace?");
    setOKButtonText("Put in Workspace");
    setCancelButtonText("Open as Usual");
    Workspace.CloneNames names = Workspace.suggestNames(folderName);
    nameField.setText(names.workspace());
    repoField.setText(names.repo());
    locationField.setText(defaultParent.toString());
    locationField.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Workspace Location"));

    workspaceCombo = new ComboBox<>(workspaces.toArray(Workspace[]::new));
    workspaceCombo.setRenderer(new SimpleListCellRenderer<Workspace>() {
      @Override
      public void customize(@NotNull JList<? extends Workspace> list, Workspace w, int index, boolean selected,
                            boolean hasFocus) {
        setText(w == null ? "" : w.getName() + "   " + w.getRoot());
      }
    });
    ButtonGroup group = new ButtonGroup();
    group.add(newWs);
    group.add(existingWs);
    // a workspace named like the clone's prefix (billebly-api → BIllebly) is the likely home
    Workspace match = workspaces.stream().filter(w -> w.getName().equalsIgnoreCase(names.workspace())).findFirst()
        .orElse(null);
    if (match != null) workspaceCombo.setSelectedItem(match);
    existingWs.setEnabled(!workspaces.isEmpty());
    (match != null ? existingWs : newWs).setSelected(true);
    newWs.addItemListener(e -> updateEnabled());
    existingWs.addItemListener(e -> updateEnabled());
    updateEnabled();
    init();
  }

  public boolean isNewWorkspace() {
    return newWs.isSelected();
  }

  public String workspaceName() {
    return nameField.getText().trim();
  }

  public Path parentFolder() {
    String t = locationField.getText().trim();
    if (t.startsWith("~/")) t = System.getProperty("user.home") + t.substring(1);
    return Path.of(t).toAbsolutePath().normalize();
  }

  public Workspace existingWorkspace() {
    return (Workspace) workspaceCombo.getSelectedItem();
  }

  public String repoName() {
    return repoField.getText().trim();
  }

  private void updateEnabled() {
    nameField.setEnabled(newWs.isSelected());
    locationField.setEnabled(newWs.isSelected());
    workspaceCombo.setEnabled(existingWs.isSelected());
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JBLabel note = new JBLabel("<html>The clone is <b>moved</b> into &lt;workspace&gt;/main/&lt;repo&gt; "
        + "(a link stays at its old place) and main opens.<br>“Open as Usual” leaves it where it is.</html>");
    note.setForeground(UIUtil.getContextHelpForeground());
    note.setFont(JBUI.Fonts.smallFont());
    return FormBuilder.createFormBuilder()
        .addComponent(newWs)
        .addLabeledComponent("    Name:", nameField)
        .addLabeledComponent("    Location:", locationField)
        .addComponent(existingWs)
        .addLabeledComponent("    Workspace:", workspaceCombo)
        .addVerticalGap(6)
        .addLabeledComponent("Repo name:", repoField)
        .addTooltip("Its folder in main/ and in every ticket, e.g. backend")
        .addVerticalGap(6)
        .addComponent(note)
        .getPanel();
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return isNewWorkspace() ? nameField : repoField;
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    if (isNewWorkspace()) {
      if (!Workspace.isValidName(workspaceName())) {
        return new ValidationInfo("Use letters, digits, '.', '-' or '_'", nameField);
      }
      if (locationField.getText().isBlank()) return new ValidationInfo("Pick a location", locationField);
    } else {
      Workspace ws = existingWorkspace();
      if (ws == null) return new ValidationInfo("Pick a workspace", workspaceCombo);
      if (ws.getConfig().getRepos().keySet().stream().anyMatch(r -> r.equalsIgnoreCase(repoName()))) {
        return new ValidationInfo(ws.getName() + " already has a repo called " + repoName(), repoField);
      }
    }
    if (!Workspace.isValidName(repoName()) || repoName().equalsIgnoreCase(Workspace.MAIN)) {
      return new ValidationInfo("Repo name: letters, digits, '.', '-' or '_'", repoField);
    }
    return null;
  }
}
