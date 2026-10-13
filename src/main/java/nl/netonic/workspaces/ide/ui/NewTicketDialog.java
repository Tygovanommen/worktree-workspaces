package nl.netonic.workspaces.ide.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.TicketRepo;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.core.WorkspaceConfig;
import nl.netonic.workspaces.core.WorkspaceOps;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Ticket key, branch (from the pattern), and which repos get a worktree. */
public final class NewTicketDialog extends DialogWrapper {
  private record RepoRow(String repo, JBCheckBox include, JBTextField base, boolean available) {}

  private final ComboBox<Workspace> workspaceCombo;
  private final JBTextField keyField = new JBTextField(24);
  private final JBTextField branchField = new JBTextField(32);
  private final JPanel reposPanel = new JPanel(new GridBagLayout());
  private final JBLabel hint = new JBLabel();
  private final List<RepoRow> rows = new ArrayList<>();
  private boolean branchEdited;
  private boolean settingBranch;

  public NewTicketDialog(@Nullable Project project, List<Workspace> workspaces, @Nullable Workspace preselected) {
    super(project, true);
    setTitle("New Ticket");
    setOKButtonText("Create");
    workspaceCombo = new ComboBox<>(workspaces.toArray(Workspace[]::new));
    workspaceCombo.setRenderer(new SimpleListCellRenderer<Workspace>() {
      @Override
      public void customize(@NotNull JList<? extends Workspace> list, Workspace w, int index, boolean selected,
                            boolean hasFocus) {
        setText(w == null ? "" : w.getName() + "   " + w.getRoot());
      }
    });
    if (preselected != null) workspaceCombo.setSelectedItem(preselected);
    workspaceCombo.addActionListener(e -> workspaceChanged());

    keyField.getEmptyText().setText("LOGIN-42");
    keyField.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        updateBranch();
      }
    });
    branchField.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        if (!settingBranch) branchEdited = !branchField.getText().isBlank();
      }
    });
    hint.setForeground(UIUtil.getContextHelpForeground());
    hint.setFont(JBUI.Fonts.smallFont());
    workspaceChanged();
    init();
  }

  public Workspace workspace() {
    return (Workspace) workspaceCombo.getSelectedItem();
  }

  public String ticketKey() {
    return keyField.getText().trim();
  }

  public String branch() {
    return branchField.getText().trim();
  }

  public List<WorkspaceOps.RepoChoice> choices() {
    List<WorkspaceOps.RepoChoice> out = new ArrayList<>();
    for (RepoRow r : rows) {
      out.add(new WorkspaceOps.RepoChoice(r.repo(), r.available() && r.include().isSelected(), r.base().getText().trim()));
    }
    return out;
  }

  private void workspaceChanged() {
    rows.clear();
    reposPanel.removeAll();
    Workspace ws = workspace();
    if (ws == null) return;
    GridBagConstraints c = new GridBagConstraints();
    c.gridy = 0;
    c.anchor = GridBagConstraints.WEST;
    c.insets = JBUI.insets(2, 0, 2, 12);
    JBLabel h1 = new JBLabel("Repo");
    JBLabel h2 = new JBLabel("Branch from");
    h1.setForeground(UIUtil.getContextHelpForeground());
    h2.setForeground(UIUtil.getContextHelpForeground());
    c.gridx = 0;
    reposPanel.add(h1, c);
    c.gridx = 1;
    reposPanel.add(h2, c);

    List<TicketRepo> mainRepos = ws.ticket(Workspace.MAIN).repos();
    for (TicketRepo r : mainRepos) {
      boolean available = r.kind() == TicketRepo.Kind.CLONE;
      WorkspaceConfig.RepoConfig rc = ws.getConfig().getRepos().get(r.name());
      JBCheckBox box = new JBCheckBox(available ? r.name() : r.name() + "  (not in main/)", available);
      box.setEnabled(available);
      JBTextField base = new JBTextField(rc != null && rc.getBaseBranch() != null ? rc.getBaseBranch() : "", 14);
      base.getEmptyText().setText("default branch");
      base.setEnabled(available);
      box.addItemListener(e -> base.setEnabled(box.isSelected()));
      c.gridy++;
      c.gridx = 0;
      reposPanel.add(box, c);
      c.gridx = 1;
      reposPanel.add(base, c);
      rows.add(new RepoRow(r.name(), box, base, available));
    }
    if (rows.isEmpty()) {
      c.gridy++;
      c.gridx = 0;
      c.gridwidth = 2;
      reposPanel.add(new JBLabel("This workspace has no repos yet. Use \"Add Repo\" first."), c);
    }
    reposPanel.revalidate();
    reposPanel.repaint();
    updateBranch();
  }

  private void updateBranch() {
    Workspace ws = workspace();
    String key = ticketKey();
    if (ws != null && !branchEdited) {
      settingBranch = true;
      try {
        branchField.setText(key.isEmpty() ? "" : ws.getConfig().branchFor(key));
      } finally {
        settingBranch = false;
      }
    }
    hint.setText(ws == null || key.isEmpty() ? " "
        : "<html>Creates <b>" + ws.getName() + "/" + key + "/</b> and opens it as tab <b>"
          + ws.getName() + " · " + key + "</b>.<br>.env, vendor/ and node_modules/ are copied from main/ (APFS clone).</html>");
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    FormBuilder fb = FormBuilder.createFormBuilder();
    if (workspaceCombo.getItemCount() > 1) fb.addLabeledComponent("Workspace:", workspaceCombo);
    else if (workspace() != null) fb.addLabeledComponent("Workspace:", new JBLabel(workspace().getName()));
    fb.addLabeledComponent("Ticket:", keyField)
        .addLabeledComponent("Branch:", branchField)
        .addVerticalGap(6)
        .addLabeledComponent("Repos:", reposPanel)
        .addVerticalGap(6)
        .addComponent(hint);
    JPanel panel = fb.getPanel();
    panel.setPreferredSize(JBUI.size(560, -1));
    return panel;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return keyField;
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    Workspace ws = workspace();
    if (ws == null) return new ValidationInfo("Pick a workspace", workspaceCombo);
    String key = ticketKey();
    if (key.isEmpty()) return new ValidationInfo("Enter a ticket key, e.g. LOGIN-42", keyField);
    if (!Workspace.isValidName(key)) {
      return new ValidationInfo("Use letters, digits, '.', '-' or '_' only", keyField);
    }
    if (key.equals(Workspace.MAIN)) return new ValidationInfo("'main' is reserved", keyField);
    Ticket t = ws.ticket(key);
    if (Files.exists(t.getDir())) return new ValidationInfo(key + " already exists in " + ws.getName(), keyField);
    String branch = branch();
    if (branch.isEmpty() || branch.contains(" ") || branch.contains("..") || branch.endsWith("/")
        || branch.endsWith(".lock") || branch.startsWith("-")) {
      return new ValidationInfo("Not a valid branch name", branchField);
    }
    if (rows.stream().noneMatch(r -> r.available() && r.include().isSelected())) {
      return new ValidationInfo("Tick at least one repo", reposPanel);
    }
    return null;
  }
}
