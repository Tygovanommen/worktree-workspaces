package nl.netonic.workspaces.ide.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import nl.netonic.workspaces.core.WorkspaceConfig;
import nl.netonic.workspaces.ide.MarkdownRunFolder;
import nl.netonic.workspaces.ide.WorkspaceModel;
import nl.netonic.workspaces.ide.WorkspaceSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.Objects;

/** Settings › Tools › Worktree Workspaces. */
public final class WorkspacesConfigurable implements SearchableConfigurable {
  private JBCheckBox mergeTabs;
  private JBCheckBox repoTerminals;
  private JBCheckBox markdownRun;
  private JBCheckBox askAfterClone;
  private JBCheckBox links;
  private TextFieldWithBrowseButton folder;
  private JBTextField pattern;
  private JBTextField repos;
  private JBTextField copy;
  private JBCheckBox deleteMerged;
  private JBCheckBox refreshOnFocus;

  @Override
  public @NotNull String getId() {
    return "nl.netonic.worktree-workspaces";
  }

  @Override
  public String getDisplayName() {
    return "Worktree Workspaces";
  }

  @Override
  public @Nullable JComponent createComponent() {
    mergeTabs = new JBCheckBox("Open tickets as tabs in one window");
    mergeTabs.setVisible(SystemInfo.isMac);
    repoTerminals = new JBCheckBox("Open a terminal per repo");
    markdownRun = new JBCheckBox("Run Markdown commands in the file's folder");
    askAfterClone = new JBCheckBox("Offer to put a cloned repo in a workspace");
    links = new JBCheckBox("Link every repo next to its workspace");
    folder = new TextFieldWithBrowseButton();
    folder.addBrowseFolderListener(null, FileChooserDescriptorFactory.singleDir().withTitle("Default Workspace Location"));
    folder.getTextField().setColumns(30);
    ((JBTextField) folder.getTextField()).getEmptyText().setText("folder used last, else ~/PhpstormProjects");
    pattern = new JBTextField(20);
    pattern.getEmptyText().setText(WorkspaceConfig.DEFAULT_BRANCH_PATTERN);
    repos = new JBTextField(30);
    repos.getEmptyText().setText("one repo, in main/ itself");
    copy = new JBTextField(30);
    deleteMerged = new JBCheckBox("Delete merged branches when finishing a ticket");
    refreshOnFocus = new JBCheckBox("Refresh git status when PhpStorm gets focus");

    return FormBuilder.createFormBuilder()
        .addComponent(new TitledSeparator("Tabs and Terminals"))
        .addComponent(mergeTabs)
        .addComponent(repoTerminals)
        .addComponent(below(repoTerminals, "The first time a ticket or main opens, each repo gets a terminal tab in its "
            + "own folder, so npm and composer work as usual. Repos in the Workspaces panel also have Open in Terminal."))
        .addComponent(markdownRun)
        .addComponent(below(markdownRun, "The ▶ next to npm install in frontend/README.md runs it in frontend/, not in "
            + "the ticket folder. This is a PhpStorm registry option (markdown.command.runner.use.file.directory), so "
            + "it applies to all projects; switching this off resets it."))
        .addComponent(new TitledSeparator("Cloning"))
        .addComponent(askAfterClone)
        .addComponent(below(askAfterClone, "When Get from VCS finishes, a dialog offers a new or existing workspace. "
            + "Otherwise the clone opens as usual."))
        .addComponent(new TitledSeparator("Folder Links"))
        .addComponent(links)
        .addComponent(below(links, "For example ~/PhpstormProjects/klant-x-backend → klant-x/main/backend, so Herd and "
            + "other tools that only look one folder deep find every repo and ticket. Switching this off removes them."))
        .addComponent(new TitledSeparator("New Workspaces"))
        .addLabeledComponent("Location:", left(folder))
        .addLabeledComponent("Branch pattern:", left(pattern))
        .addComponentToRightColumn(comment("{ticket} becomes the ticket key, e.g. feature/LOGIN-42"))
        .addLabeledComponent("Repos:", left(repos))
        .addComponentToRightColumn(comment("Comma separated; the repo rows New Workspace starts with, each one a "
            + "folder in main/. Rows can be removed there; a workspace with one repo has it in main/ itself."))
        .addLabeledComponent("Files to copy:", left(copy))
        .addComponentToRightColumn(comment("Comma separated; copied from main into every new ticket. Used for repos "
            + "added from now on, existing repos keep their list in .workspace.json."))
        .addComponent(new TitledSeparator("Finish Ticket"))
        .addComponent(deleteMerged)
        .addComponent(below(deleteMerged, "The default for the checkbox in Finish Ticket."))
        .addComponent(new TitledSeparator("Status"))
        .addComponent(refreshOnFocus)
        .addComponentFillVertically(new JPanel(), 0)
        .getPanel();
  }

  /** A grey help text that wraps at a fixed width instead of widening the page. */
  private static JComponent comment(String text) {
    String html = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    JBLabel label = new JBLabel("<html><body style='width:" + JBUI.scale(400) + "px'>" + html + "</body></html>");
    label.setForeground(UIUtil.getContextHelpForeground());
    label.setFont(JBUI.Fonts.smallFont());
    return label;
  }

  /** A help text under a checkbox, lined up with the checkbox's text. */
  private static JComponent below(JBCheckBox box, String text) {
    JComponent c = comment(text);
    c.setBorder(JBUI.Borders.emptyLeft(UIUtil.getCheckBoxTextHorizontalOffset(box)));
    return c;
  }

  /** Keeps a field at its own width instead of stretching to the page's. */
  private static JComponent left(JComponent c) {
    JPanel p = new JPanel(new BorderLayout());
    p.add(c, BorderLayout.WEST);
    return p;
  }

  @Override
  public boolean isModified() {
    WorkspaceSettings.SettingsState s = WorkspaceSettings.getInstance().getState();
    return mergeTabs.isSelected() != s.mergeWindowsIntoTabs
        || repoTerminals.isSelected() != s.openRepoTerminals
        || markdownRun.isSelected() != s.markdownRunInFileFolder
        || !Objects.equals(cloneValue(), s.afterClone == null ? WorkspaceSettings.CLONE_ASK : s.afterClone)
        || links.isSelected() != s.createLinks
        || !folder.getText().trim().equals(s.defaultWorkspaceFolder)
        || !pattern.getText().trim().equals(s.defaultBranchPattern)
        || !repos.getText().trim().equals(s.defaultRepos)
        || !copy.getText().trim().equals(s.defaultCopy)
        || deleteMerged.isSelected() != s.deleteMergedBranches
        || refreshOnFocus.isSelected() != s.refreshOnFocus;
  }

  @Override
  public void apply() throws ConfigurationException {
    String p = pattern.getText().trim();
    if (!p.isEmpty() && !p.contains("{ticket}")) {
      throw new ConfigurationException("The branch pattern needs {ticket}, e.g. feature/{ticket}");
    }
    WorkspaceSettings.SettingsState s = WorkspaceSettings.getInstance().getState();
    boolean linksChanged = links.isSelected() != s.createLinks;
    boolean markdownChanged = markdownRun.isSelected() != s.markdownRunInFileFolder;
    s.mergeWindowsIntoTabs = mergeTabs.isSelected();
    s.openRepoTerminals = repoTerminals.isSelected();
    s.markdownRunInFileFolder = markdownRun.isSelected();
    s.afterClone = cloneValue();
    s.createLinks = links.isSelected();
    s.defaultWorkspaceFolder = folder.getText().trim();
    s.defaultBranchPattern = p.isEmpty() ? WorkspaceConfig.DEFAULT_BRANCH_PATTERN : p;
    s.defaultRepos = String.join(", ", WorkspaceSettings.parseRepoNames(repos.getText()));
    s.defaultCopy = String.join(", ", WorkspaceSettings.parseCopyList(copy.getText()));
    s.deleteMergedBranches = deleteMerged.isSelected();
    s.refreshOnFocus = refreshOnFocus.isSelected();
    // the scan creates or removes the links
    if (linksChanged) WorkspaceModel.getInstance().refresh();
    if (markdownChanged) MarkdownRunFolder.apply();
    reset();
  }

  @Override
  public void reset() {
    WorkspaceSettings.SettingsState s = WorkspaceSettings.getInstance().getState();
    mergeTabs.setSelected(s.mergeWindowsIntoTabs);
    repoTerminals.setSelected(s.openRepoTerminals);
    markdownRun.setSelected(s.markdownRunInFileFolder);
    askAfterClone.setSelected(!WorkspaceSettings.CLONE_NEVER.equals(s.afterClone));
    links.setSelected(s.createLinks);
    folder.setText(s.defaultWorkspaceFolder);
    pattern.setText(s.defaultBranchPattern);
    repos.setText(s.defaultRepos);
    copy.setText(s.defaultCopy);
    deleteMerged.setSelected(s.deleteMergedBranches);
    refreshOnFocus.setSelected(s.refreshOnFocus);
  }

  private String cloneValue() {
    return askAfterClone.isSelected() ? WorkspaceSettings.CLONE_ASK : WorkspaceSettings.CLONE_NEVER;
  }
}
