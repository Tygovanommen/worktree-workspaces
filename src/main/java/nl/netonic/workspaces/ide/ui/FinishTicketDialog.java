package nl.netonic.workspaces.ide.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.WorkspaceOps;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.List;

/** Confirms removing a ticket's worktrees and folder, listing anything that would be lost. */
public final class FinishTicketDialog extends DialogWrapper {
  private final Ticket ticket;
  private final List<WorkspaceOps.Problem> problems;
  private final boolean hasUncommitted;
  private final JBCheckBox deleteBranches = new JBCheckBox("Delete branches that are fully merged", true);
  private final JBCheckBox discard = new JBCheckBox("Discard uncommitted changes");

  public FinishTicketDialog(@Nullable Project project, Ticket ticket, List<WorkspaceOps.Problem> problems) {
    super(project, true);
    this.ticket = ticket;
    this.problems = problems;
    this.hasUncommitted = problems.stream().anyMatch(p -> p.text().contains("uncommitted") || p.text().startsWith("git error"));
    setTitle("Finish " + ticket.displayName());
    setOKButtonText("Finish Ticket");
    init();
  }

  public boolean deleteMergedBranches() {
    return deleteBranches.isSelected();
  }

  public boolean force() {
    return discard.isSelected();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    FormBuilder fb = FormBuilder.createFormBuilder();
    fb.addComponent(new JBLabel("<html>Removes the worktrees and the folder <b>" + ticket.getWorkspace().getName() + "/"
        + ticket.getName() + "/</b>.<br>The tab is closed. main/ and other tickets are not touched.</html>"));
    fb.addVerticalGap(8);
    if (problems.isEmpty()) {
      JBLabel ok = new JBLabel("Everything is committed and pushed.", AllIcons.General.InspectionsOK, JBLabel.LEFT);
      fb.addComponent(ok);
    } else {
      for (WorkspaceOps.Problem p : problems) {
        fb.addComponent(new JBLabel(p.repo() + ": " + p.text(), AllIcons.General.Warning, JBLabel.LEFT));
      }
      if (problems.stream().anyMatch(p -> p.text().contains("not pushed"))) {
        JBLabel n = new JBLabel("Unpushed commits stay on their branch; only the folder goes away.");
        n.setForeground(UIUtil.getContextHelpForeground());
        n.setFont(JBUI.Fonts.smallFont());
        fb.addComponent(n);
      }
    }
    fb.addVerticalGap(8);
    fb.addComponent(deleteBranches);
    if (hasUncommitted) fb.addComponent(discard);
    var panel = fb.getPanel();
    panel.setPreferredSize(JBUI.size(480, -1));
    return panel;
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    if (hasUncommitted && !discard.isSelected()) {
      return new ValidationInfo("Commit or stash first, or tick \"Discard uncommitted changes\"", discard);
    }
    return null;
  }
}
