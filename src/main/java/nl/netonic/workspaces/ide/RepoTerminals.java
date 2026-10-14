package nl.netonic.workspaces.ide;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.TicketRepo;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One terminal tab per repo ("backend", "frontend"), opened in that repo's folder, so npm / composer / git
 * work like in a single-repo project.
 *
 * <p>Uses the terminal's tab builder, which names tabs and starts the shell only when the Terminal window is shown.
 * That API is {@code @ApiStatus.Experimental} and lives in a terminal content module this plugin can't depend on,
 * so it's called by name; when it's missing or changed, PhpStorm's own "Open In › Terminal" action is used instead
 * (stable, but it shows the Terminal window and names the tab itself).
 */
public final class RepoTerminals {
  private static final Logger LOG = Logger.getInstance(RepoTerminals.class);
  private static final String DONE_KEY = "nl.netonic.workspaces.repoTerminalsOpened";
  private static final String TABS_MANAGER = "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager";
  private static final String TAB_BUILDER = "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabBuilder";

  private RepoTerminals() {}

  public record RepoDir(String name, Path dir) {}

  /**
   * The first time a ticket (or main) opens as a project: a terminal per repo. Later opens are left alone, PhpStorm
   * restores the tabs itself. Call off the EDT (reads the ticket folder).
   */
  public static void openOnFirstOpen(Project project, Ticket ticket) {
    if (!WorkspaceSettings.getInstance().isOpenRepoTerminals()) return;
    PropertiesComponent props = PropertiesComponent.getInstance(project);
    if (props.getBoolean(DONE_KEY)) return;
    List<RepoDir> repos = new ArrayList<>();
    for (TicketRepo r : ticket.repos()) {
      if (r.kind() != TicketRepo.Kind.MISSING) repos.add(new RepoDir(r.name(), r.dir()));
    }
    if (repos.isEmpty()) return;
    props.setValue(DONE_KEY, true);
    open(project, repos, true);
  }

  /** Opens a terminal tab per repo, optionally skipping names that already have a tab. Any thread. */
  public static void open(Project project, List<RepoDir> repos, boolean skipExisting) {
    List<VirtualFile> dirs = new ArrayList<>();
    for (RepoDir r : repos) dirs.add(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(r.dir()));
    ApplicationManager.getApplication().invokeLater(() -> {
      if (project.isDisposed()) return;
      List<String> existing = skipExisting ? existingTabNames(project) : List.of();
      for (int i = 0; i < repos.size(); i++) {
        RepoDir r = repos.get(i);
        if (existing.contains(r.name())) continue;
        if (!openNamedTab(project, r) && dirs.get(i) != null) openWithAction(project, dirs.get(i));
      }
    }, ModalityState.nonModal(), project.getDisposed());
  }

  private static List<String> existingTabNames(Project project) {
    List<String> names = new ArrayList<>();
    ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow("Terminal");
    if (tw == null) return names;
    for (Content c : tw.getContentManager().getContents()) {
      if (c.getDisplayName() != null) names.add(c.getDisplayName());
    }
    return names;
  }

  /** The terminal's tab builder, found through the class loader of one of its own actions. */
  private static boolean openNamedTab(Project project, RepoDir r) {
    AnAction anchor = ActionManager.getInstance().getAction("Terminal.OpenInReworkedTerminal");
    if (anchor == null) return false;
    try {
      ClassLoader cl = anchor.getClass().getClassLoader();
      Class<?> managerClass = Class.forName(TABS_MANAGER, true, cl);
      Class<?> builderClass = Class.forName(TAB_BUILDER, true, cl);
      Object manager = managerClass.getMethod("getInstance", Project.class).invoke(null, project);
      Object builder = managerClass.getMethod("createTabBuilder").invoke(manager);
      builder = call(builderClass, builder, "workingDirectory", String.class, r.dir().toString());
      builder = call(builderClass, builder, "tabName", String.class, r.name());
      builder = call(builderClass, builder, "requestFocus", boolean.class, false);
      builder = call(builderClass, builder, "deferSessionStartUntilUiShown", boolean.class, true);
      builderClass.getMethod("createTab").invoke(builder);
      return true;
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      LOG.info("Terminal tab builder unavailable, using Open In Terminal: " + e);
      return false;
    }
  }

  private static Object call(Class<?> type, Object target, String name, Class<?> param, Object arg)
      throws ReflectiveOperationException {
    Method m = type.getMethod(name, param);
    return m.invoke(target, arg);
  }

  /** PhpStorm's "Open In › Terminal" on the repo folder. */
  private static void openWithAction(Project project, VirtualFile dir) {
    AnAction action = ActionManager.getInstance().getAction("Terminal.OpenInTerminal");
    if (action == null) return;
    var context = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.VIRTUAL_FILE, dir)
        .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, new VirtualFile[] {dir})
        .build();
    AnActionEvent event = AnActionEvent.createEvent(context, action.getTemplatePresentation().clone(),
        ActionPlaces.UNKNOWN, ActionUiKind.NONE, null);
    try {
      ActionUtil.performAction(action, event);
    } catch (RuntimeException e) {
      LOG.warn("Open In Terminal failed for " + dir, e);
    }
  }
}
