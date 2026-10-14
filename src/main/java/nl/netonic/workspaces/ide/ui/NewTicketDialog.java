package nl.netonic.workspaces.ide.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.ComboboxSpeedSearch;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import nl.netonic.workspaces.core.GitException;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.TicketRepo;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.core.WorkspaceConfig;
import nl.netonic.workspaces.core.WorkspaceOps;
import nl.netonic.workspaces.ide.IdeGit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Ticket key, branch (from the pattern), and which repos get a worktree from which branch. */
public final class NewTicketDialog extends DialogWrapper {
  /** A base to branch from: a full ref, or (until the branches are loaded) a plain name or "" for the default. */
  private record Base(String ref, String label, boolean remote) {
    @Override
    public String toString() {
      return label;
    }
  }

  private record RepoRow(String repo, JBCheckBox include, ComboBox<Base> base, boolean available) {}

  private final ComboBox<Workspace> workspaceCombo;
  private final JBTextField keyField = new JBTextField(24);
  private final JBTextField branchField = new JBTextField(32);
  private final JPanel reposPanel = new JPanel(new GridBagLayout());
  private final JBLabel hint = new JBLabel();
  private final List<RepoRow> rows = new ArrayList<>();
  private boolean branchEdited;
  private boolean settingBranch;
  /** Bumped when the repo rows are rebuilt, so branches loaded for an earlier workspace are dropped. */
  private int rowsGeneration;

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

    keyField.getEmptyText().setText("LOGIN-42 or LOGIN-42/login-form");
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

  /** As typed, e.g. LOGIN-42/login-form; goes into the branch pattern. */
  public String ticketKey() {
    return keyField.getText().trim();
  }

  /** The ticket's folder and tab name, e.g. LOGIN-42-login-form. */
  public String ticketFolder() {
    return Workspace.ticketFolderName(ticketKey());
  }

  public String branch() {
    return branchField.getText().trim();
  }

  public List<WorkspaceOps.RepoChoice> choices() {
    List<WorkspaceOps.RepoChoice> out = new ArrayList<>();
    for (RepoRow r : rows) {
      Base base = (Base) r.base().getSelectedItem();
      out.add(new WorkspaceOps.RepoChoice(r.repo(), r.available() && r.include().isSelected(),
          base == null ? "" : base.ref()));
    }
    return out;
  }

  private void workspaceChanged() {
    rows.clear();
    reposPanel.removeAll();
    rowsGeneration++;
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
      String configured = rc != null && rc.getBaseBranch() != null ? rc.getBaseBranch() : "";
      JBCheckBox box = new JBCheckBox(available ? r.name() : r.name() + "  (not in main/)", available);
      box.setEnabled(available);
      // until the branches are loaded: the configured name, resolved by core (local first, then origin)
      ComboBox<Base> base = new ComboBox<>(new Base[] {
          new Base(configured, configured.isEmpty() ? "default branch" : configured, false)}, JBUI.scale(220));
      base.setRenderer(new BaseRenderer());
      ComboboxSpeedSearch.installOn(base);
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
    loadBranches(ws, List.copyOf(rows));
  }

  /** Fills each repo's dropdown off the EDT; preselects the local base branch, else origin's. */
  private void loadBranches(Workspace ws, List<RepoRow> forRows) {
    int generation = rowsGeneration;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      WorkspaceOps ops = IdeGit.ops();
      for (RepoRow row : forRows) {
        if (!row.available()) continue;
        WorkspaceOps.Branches branches;
        try {
          branches = IdeGit.branches(ws, row.repo());
        } catch (GitException e) {
          continue; // keep the configured name; core resolves it when creating
        }
        WorkspaceConfig.RepoConfig rc = ws.getConfig().getRepos().get(row.repo());
        String base = rc != null && rc.getBaseBranch() != null ? rc.getBaseBranch()
            : ops.detectDefaultBranch(ws.mainDir().resolve(row.repo()));
        ApplicationManager.getApplication().invokeLater(() -> {
          if (generation == rowsGeneration && !isDisposed()) fill(row.base(), branches, base);
        }, ModalityState.any());
      }
    });
  }

  private static void fill(ComboBox<Base> combo, WorkspaceOps.Branches branches, String base) {
    DefaultComboBoxModel<Base> model = new DefaultComboBoxModel<>();
    Base selected = null;
    Base originBase = null;
    for (String ref : branches.local()) {
      Base b = new Base(ref, ref.substring("refs/heads/".length()), false);
      model.addElement(b);
      if (ref.equals("refs/heads/" + base)) selected = b;
    }
    for (String ref : branches.remote()) {
      Base b = new Base(ref, ref.substring("refs/remotes/".length()), true);
      model.addElement(b);
      if (ref.equals("refs/remotes/origin/" + base)) originBase = b;
    }
    if (model.getSize() == 0) return;
    combo.setModel(model);
    // no match: nothing selected, so validation asks for a pick instead of guessing
    model.setSelectedItem(selected != null ? selected : originBase);
  }

  /** Adds a "Local" / "Remote" header above the first branch of each group in the popup. */
  private static final class BaseRenderer implements ListCellRenderer<Base> {
    private final ListCellRenderer<Base> label = new SimpleListCellRenderer<>() {
      @Override
      public void customize(@NotNull JList<? extends Base> list, Base b, int index, boolean selected, boolean hasFocus) {
        setText(b == null ? "" : b.label());
      }
    };

    @Override
    public Component getListCellRendererComponent(JList<? extends Base> list, Base value, int index, boolean selected,
                                                  boolean hasFocus) {
      Component c = label.getListCellRendererComponent(list, value, index, selected, hasFocus);
      if (index < 0 || value == null || !value.ref().startsWith("refs/")) return c;
      boolean first = index == 0 || list.getModel().getElementAt(index - 1).remote() != value.remote();
      if (!first) return c;
      JPanel panel = new JPanel(new BorderLayout());
      panel.setOpaque(false);
      panel.add(new TitledSeparator(value.remote() ? "Remote" : "Local"), BorderLayout.NORTH);
      panel.add(c, BorderLayout.CENTER);
      return panel;
    }
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
    String folder = ticketFolder();
    hint.setText(ws == null || key.isEmpty() ? " "
        : "<html>Creates <b>" + ws.getName() + "/" + folder + "/</b> and opens it as tab <b>"
          + ws.getName() + " · " + folder + "</b>.<br>.env, vendor/ and node_modules/ are copied from main/ (APFS clone).</html>");
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
    if (!Workspace.isValidTicketKey(key)) {
      return new ValidationInfo("Use letters, digits, '.', '-' or '_', with '/' between parts", keyField);
    }
    String folder = ticketFolder();
    if (folder.equalsIgnoreCase(Workspace.MAIN)) return new ValidationInfo("'main' is reserved", keyField);
    Ticket t = ws.ticket(folder);
    if (Files.exists(t.getDir())) return new ValidationInfo(folder + " already exists in " + ws.getName(), keyField);
    String branch = branch();
    if (branch.isEmpty() || branch.contains(" ") || branch.contains("..") || branch.endsWith("/")
        || branch.endsWith(".lock") || branch.startsWith("-")) {
      return new ValidationInfo("Not a valid branch name", branchField);
    }
    if (rows.stream().noneMatch(r -> r.available() && r.include().isSelected())) {
      return new ValidationInfo("Tick at least one repo", reposPanel);
    }
    for (RepoRow r : rows) {
      if (r.available() && r.include().isSelected() && r.base().getSelectedItem() == null) {
        return new ValidationInfo("Pick a branch to start " + r.repo() + " from", r.base());
      }
    }
    return null;
  }
}
