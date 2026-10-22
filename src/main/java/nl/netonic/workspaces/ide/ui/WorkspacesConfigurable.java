package nl.netonic.workspaces.ide.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.ui.ComboBox;
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
import nl.netonic.workspaces.ide.Tickets;
import nl.netonic.workspaces.ide.WindowTabs;
import nl.netonic.workspaces.ide.WorkspaceModel;
import nl.netonic.workspaces.ide.WorkspaceSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Objects;

/** Settings › Tools › Worktree Workspaces. */
public final class WorkspacesConfigurable implements SearchableConfigurable {
  private JBCheckBox mergeTabs;
  private JBCheckBox openAsTab;
  private JBCheckBox colorTickets;
  private JBCheckBox styleTabs;
  private JBCheckBox repoTerminals;
  private JBCheckBox markdownRun;
  private JBCheckBox tidyRecent;
  private JBCheckBox askAfterClone;
  private JBCheckBox links;
  private TextFieldWithBrowseButton folder;
  private JBTextField pattern;
  private JBTextField repos;
  private JBTextField copy;
  private ComboBox<Tickets.OpenIn> openIn;
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
    openAsTab = new JBCheckBox("Add Merge in Current to PhpStorm's Open Project dialog");
    openAsTab.setVisible(SystemInfo.isMac);
    colorTickets = new JBCheckBox("Give each open ticket its own toolbar color");
    styleTabs = new JBCheckBox("Make window tabs easier to see");
    styleTabs.setVisible(SystemInfo.isMac);
    repoTerminals = new JBCheckBox("Open a terminal per repo");
    markdownRun = new JBCheckBox("Run Markdown commands in the file's folder");
    tidyRecent = new JBCheckBox("Show only main of each workspace in Recent Projects");
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
    openIn = new ComboBox<>(Tickets.OpenIn.available(true).toArray(Tickets.OpenIn[]::new));
    deleteMerged = new JBCheckBox("Delete merged branches when finishing a ticket");
    refreshOnFocus = new JBCheckBox("Refresh git status when PhpStorm gets focus");

    return FormBuilder.createFormBuilder()
        .addComponent(new TitledSeparator("Tabs and Terminals"))
        .addComponent(mergeTabs)
        .addComponent(openAsTab)
        .addComponent(below(openAsTab, "Next to This Window and New Window: opens any project as a tab of the "
            + "current window. The dialog has one spare button, so this takes the place of Attach."))
        .addComponent(colorTickets)
        .addComponent(below(colorTickets, "When a ticket or main opens, it gets a project color (as in the project "
            + "widget's Change Project Color) that no other open tab has. A color you pick yourself is kept."))
        .addComponent(styleTabs)
        .addComponent(below(styleTabs, "Each tab in the bar above the toolbar shows a dot in its project's color, and "
            + "unselected tabs get a light background so they stand out from the bar. Applies to every project."))
        .addComponent(repoTerminals)
        .addComponent(below(repoTerminals, "The first time a ticket or main opens, each repo gets a terminal tab in its "
            + "own folder, so npm and composer work as usual. Repos in the Workspaces panel also have Open in Terminal."))
        .addComponent(markdownRun)
        .addComponent(below(markdownRun, "The ▶ next to npm install in frontend/README.md runs it in frontend/, not in "
            + "the ticket folder. This is a PhpStorm registry option (markdown.command.runner.use.file.directory), so "
            + "it applies to all projects; switching this off resets it."))
        .addComponent(tidyRecent)
        .addComponent(below(tidyRecent, "Closed ticket tabs and the workspace folder itself are removed from the "
            + "Recent Projects list; tickets are in the switcher. Tabs open when PhpStorm quits still reopen."))
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
        .addLabeledComponent("Open in:", left(openIn))
        .addComponentToRightColumn(comment("Where main opens once the workspace is created; the New Workspace "
            + "dialog starts with this choice. This window closes the current project first."))
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
        || openAsTab.isSelected() != s.openAsTabButton
        || colorTickets.isSelected() != s.colorTickets
        || styleTabs.isSelected() != s.styleWindowTabs
        || repoTerminals.isSelected() != s.openRepoTerminals
        || markdownRun.isSelected() != s.markdownRunInFileFolder
        || tidyRecent.isSelected() != s.tidyRecentProjects
        || !Objects.equals(cloneValue(), s.afterClone == null ? WorkspaceSettings.CLONE_ASK : s.afterClone)
        || links.isSelected() != s.createLinks
        || !folder.getText().trim().equals(s.defaultWorkspaceFolder)
        || !pattern.getText().trim().equals(s.defaultBranchPattern)
        || !repos.getText().trim().equals(s.defaultRepos)
        || !copy.getText().trim().equals(s.defaultCopy)
        || selectedOpenIn() != savedOpenIn()
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
    boolean tidyTurnedOn = tidyRecent.isSelected() && !s.tidyRecentProjects;
    s.mergeWindowsIntoTabs = mergeTabs.isSelected();
    s.openAsTabButton = openAsTab.isSelected();
    s.colorTickets = colorTickets.isSelected();
    boolean tabsChanged = styleTabs.isSelected() != s.styleWindowTabs;
    s.styleWindowTabs = styleTabs.isSelected();
    s.openRepoTerminals = repoTerminals.isSelected();
    s.markdownRunInFileFolder = markdownRun.isSelected();
    s.tidyRecentProjects = tidyRecent.isSelected();
    s.afterClone = cloneValue();
    s.createLinks = links.isSelected();
    s.defaultWorkspaceFolder = folder.getText().trim();
    s.defaultBranchPattern = p.isEmpty() ? WorkspaceConfig.DEFAULT_BRANCH_PATTERN : p;
    s.defaultRepos = String.join(", ", WorkspaceSettings.parseRepoNames(repos.getText()));
    s.defaultCopy = String.join(", ", WorkspaceSettings.parseCopyList(copy.getText()));
    s.newWorkspaceOpenIn = selectedOpenIn().name();
    s.deleteMergedBranches = deleteMerged.isSelected();
    s.refreshOnFocus = refreshOnFocus.isSelected();
    // the scan creates or removes the links, and tidies Recent Projects
    if (linksChanged || tidyTurnedOn) WorkspaceModel.getInstance().refresh();
    if (markdownChanged) MarkdownRunFolder.apply();
    if (tabsChanged) WindowTabs.refreshLater();
    reset();
  }

  @Override
  public void reset() {
    WorkspaceSettings.SettingsState s = WorkspaceSettings.getInstance().getState();
    mergeTabs.setSelected(s.mergeWindowsIntoTabs);
    openAsTab.setSelected(s.openAsTabButton);
    colorTickets.setSelected(s.colorTickets);
    styleTabs.setSelected(s.styleWindowTabs);
    repoTerminals.setSelected(s.openRepoTerminals);
    markdownRun.setSelected(s.markdownRunInFileFolder);
    tidyRecent.setSelected(s.tidyRecentProjects);
    askAfterClone.setSelected(!WorkspaceSettings.CLONE_NEVER.equals(s.afterClone));
    links.setSelected(s.createLinks);
    folder.setText(s.defaultWorkspaceFolder);
    pattern.setText(s.defaultBranchPattern);
    repos.setText(s.defaultRepos);
    copy.setText(s.defaultCopy);
    openIn.setSelectedItem(savedOpenIn());
    deleteMerged.setSelected(s.deleteMergedBranches);
    refreshOnFocus.setSelected(s.refreshOnFocus);
  }

  /** The saved choice, or the first one when it isn't offered here (a tab outside macOS). */
  private static Tickets.OpenIn savedOpenIn() {
    List<Tickets.OpenIn> choices = Tickets.OpenIn.available(true);
    Tickets.OpenIn saved = WorkspaceSettings.getInstance().newWorkspaceOpenIn();
    return choices.contains(saved) ? saved : choices.get(0);
  }

  private Tickets.OpenIn selectedOpenIn() {
    Object selected = openIn.getSelectedItem();
    return selected instanceof Tickets.OpenIn in ? in : Tickets.OpenIn.available(true).get(0);
  }

  private String cloneValue() {
    return askAfterClone.isSelected() ? WorkspaceSettings.CLONE_ASK : WorkspaceSettings.CLONE_NEVER;
  }
}
