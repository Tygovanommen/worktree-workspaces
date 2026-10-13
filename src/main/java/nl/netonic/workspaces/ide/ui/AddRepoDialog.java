package nl.netonic.workspaces.ide.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import nl.netonic.workspaces.core.TicketRepo;
import nl.netonic.workspaces.core.Workspace;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Adds one or more repos to an existing workspace's main/. */
public final class AddRepoDialog extends DialogWrapper {
  private final Workspace workspace;
  private final RepoSourcesPanel repos;
  private final Set<String> existingNames = new HashSet<>();

  public AddRepoDialog(@Nullable Project project, Workspace workspace) {
    super(project, true);
    this.workspace = workspace;
    setTitle("Add Repo to " + workspace.getName());
    setOKButtonText("Add");
    repos = new RepoSourcesPanel(project, List.of(""), true);
    existingNames.addAll(workspace.getConfig().getRepos().keySet());
    for (TicketRepo r : workspace.ticket(Workspace.MAIN).repos()) existingNames.add(r.name());
    init();
  }

  public List<RepoSourcesPanel.RepoSource> repoSources() {
    return repos.sources();
  }

  public List<Path> foldersToMove() {
    return repos.foldersToMove();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JBLabel note = new JBLabel("<html>Existing checkouts are <b>moved</b> into " + workspace.getName()
        + "/main/&lt;name&gt;. Existing tickets show the new repo from main/ until you add a worktree.</html>");
    note.setForeground(UIUtil.getContextHelpForeground());
    note.setFont(JBUI.Fonts.smallFont());
    var panel = FormBuilder.createFormBuilder()
        .addComponent(repos)
        .addVerticalGap(6)
        .addComponent(note)
        .getPanel();
    panel.setPreferredSize(JBUI.size(600, -1));
    return panel;
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    if (repoSources().isEmpty()) return new ValidationInfo("Enter a git URL or pick a folder");
    for (Path p : foldersToMove()) {
      if (workspace.getRoot().startsWith(p) || p.startsWith(workspace.getRoot())) {
        return new ValidationInfo(p + " is already inside the workspace");
      }
    }
    return repos.validate(existingNames);
  }
}
