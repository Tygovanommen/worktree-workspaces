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
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.TicketRepo;
import org.jetbrains.annotations.NotNull;

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
 *
 * <p>When every terminal tab is closed, the next time the Terminal window opens it gets the repo tabs again instead
 * of PhpStorm's single tab in the ticket folder.
 */
public final class RepoTerminals {
  private static final Logger LOG = Logger.getInstance(RepoTerminals.class);
  private static final String DONE_KEY = "nl.netonic.workspaces.repoTerminalsOpened";
  private static final String TERMINAL = "Terminal";
  private static final Key<List<RepoDir>> REPOS = Key.create("nl.netonic.workspaces.repoTerminalDirs");
  private static final Key<Boolean> EMPTIED = Key.create("nl.netonic.workspaces.terminalEmptied");
  private static final String TABS_MANAGER = "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager";
  private static final String TAB_BUILDER = "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabBuilder";

  private RepoTerminals() {}

  public record RepoDir(String name, Path dir) {}

  /**
   * The first time a ticket (or main) opens as a project: a terminal per repo. Later opens are left alone, PhpStorm
   * restores the tabs itself. Also starts watching for the Terminal window being emptied. Call off the EDT (reads the
   * ticket folder).
   */
  public static void openOnFirstOpen(Project project, Ticket ticket) {
    remember(project, ticket.repos());
    ToolWindowManager.getInstance(project).invokeLater(() -> watchForEmpty(project));
    if (!WorkspaceSettings.getInstance().isOpenRepoTerminals()) return;
    PropertiesComponent props = PropertiesComponent.getInstance(project);
    if (props.getBoolean(DONE_KEY)) return;
    List<RepoDir> repos = project.getUserData(REPOS);
    if (repos == null || repos.isEmpty()) return;
    props.setValue(DONE_KEY, true);
    open(project, repos, true);
  }

  /** The repo folders an emptied Terminal window reopens with; updated whenever the ticket's repos are re-read. */
  public static void remember(Project project, List<TicketRepo> repos) {
    List<RepoDir> dirs = new ArrayList<>();
    for (TicketRepo r : repos) {
      if (r.kind() != TicketRepo.Kind.MISSING) dirs.add(new RepoDir(r.name(), r.dir()));
    }
    project.putUserData(REPOS, List.copyOf(dirs));
  }

  /**
   * Notes when the last terminal tab is closed. Only then are tabs added on show: right after the window is created
   * PhpStorm is still restoring the saved tabs, and adding ours too would double them.
   */
  private static void watchForEmpty(Project project) {
    if (project.isDisposed()) return;
    ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL);
    if (tw == null) return;
    tw.getContentManager().addContentManagerListener(new ContentManagerListener() {
      @Override
      public void contentRemoved(@NotNull ContentManagerEvent event) {
        if (tw.getContentManager().isEmpty()) project.putUserData(EMPTIED, true);
      }
    });
  }

  /**
   * Opening the emptied Terminal window: the repo tabs again. Runs synchronously on show, so the tabs are there
   * before PhpStorm checks whether to add its own default tab.
   */
  public static final class OnTerminalShown implements ToolWindowManagerListener {
    private final Project project;

    public OnTerminalShown(Project project) {
      this.project = project;
    }

    @Override
    public void toolWindowShown(@NotNull ToolWindow toolWindow) {
      if (!TERMINAL.equals(toolWindow.getId()) || project.getUserData(EMPTIED) != Boolean.TRUE) return;
      project.putUserData(EMPTIED, null);
      List<RepoDir> repos = project.getUserData(REPOS);
      ContentManager cm = toolWindow.getContentManager();
      if (repos == null || !cm.isEmpty() || !WorkspaceSettings.getInstance().isOpenRepoTerminals()) return;
      for (RepoDir r : repos) {
        if (!openNamedTab(project, r)) break;
      }
      Content first = cm.getContent(0);
      if (first != null) cm.setSelectedContent(first);
    }
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
    ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL);
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
