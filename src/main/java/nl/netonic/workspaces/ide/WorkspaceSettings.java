package nl.netonic.workspaces.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.core.WorkspaceConfig;
import nl.netonic.workspaces.core.WorkspaceOps;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Application-wide list of known workspaces + plugin options (stored in worktreeWorkspaces.xml). */
@Service(Service.Level.APP)
@State(name = "WorktreeWorkspaces", storages = @Storage("worktreeWorkspaces.xml"))
public final class WorkspaceSettings implements PersistentStateComponent<WorkspaceSettings.SettingsState> {

  /** Serialized state; public fields are persisted by the platform. */
  public static final class SettingsState {
    public List<String> workspaces = new ArrayList<>();
    public boolean mergeWindowsIntoTabs = true;
    /** See {@link TicketColors}. */
    public boolean colorTickets = true;
    /** See {@link OpenAsTab}. */
    public boolean openAsTabButton = true;
    /** A {@link Tickets.OpenIn} name: the choice New Workspace starts with for where main opens. */
    public String newWorkspaceOpenIn = Tickets.OpenIn.TAB.name();
    public boolean openRepoTerminals = true;
    /** {@link #CLONE_ASK} or {@link #CLONE_NEVER}. */
    public String afterClone = CLONE_ASK;
    public boolean createLinks = true;
    /** Empty: the folder used last, else ~/PhpstormProjects. */
    public String defaultWorkspaceFolder = "";
    public String defaultBranchPattern = WorkspaceConfig.DEFAULT_BRANCH_PATTERN;
    /** Repo rows New Workspace starts with, comma separated; empty: one row without a name. */
    public String defaultRepos = DEFAULT_REPOS;
    public String defaultCopy = String.join(", ", WorkspaceConfig.DEFAULT_COPY);
    public boolean deleteMergedBranches = true;
    public boolean refreshOnFocus = true;
    /** See {@link RecentProjects}. */
    public boolean tidyRecentProjects = true;
    /** See {@link MarkdownRunFolder}. */
    public boolean markdownRunInFileFolder = true;
    public boolean markdownRunFolderSetByUs = false;
    public String lastParentFolder = "";
    public int ticketsCreated = 0;
  }

  public static final String DEFAULT_REPOS = "backend, frontend";
  public static final String CLONE_ASK = "ask";
  public static final String CLONE_NEVER = "never";

  private SettingsState state = new SettingsState();

  public static WorkspaceSettings getInstance() {
    return ApplicationManager.getApplication().getService(WorkspaceSettings.class);
  }

  @Override
  public @NotNull SettingsState getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull SettingsState loaded) {
    state = loaded;
  }

  /** Registered workspace roots that still exist on disk. */
  public synchronized List<Path> workspaceRoots() {
    List<Path> out = new ArrayList<>();
    for (String s : state.workspaces) {
      Path p = Path.of(s);
      if (Workspace.isWorkspaceRoot(p)) out.add(p);
    }
    return out;
  }

  /** @return true when it was not registered yet */
  public synchronized boolean addWorkspace(Path root) {
    String s = root.toAbsolutePath().normalize().toString();
    if (state.workspaces.contains(s)) return false;
    state.workspaces.add(s);
    state.workspaces.sort(String.CASE_INSENSITIVE_ORDER);
    return true;
  }

  public synchronized void removeWorkspace(Path root) {
    state.workspaces.remove(root.toAbsolutePath().normalize().toString());
  }

  public boolean isMergeWindowsIntoTabs() {
    return state.mergeWindowsIntoTabs;
  }

  public void setMergeWindowsIntoTabs(boolean value) {
    state.mergeWindowsIntoTabs = value;
  }

  /** Folder where new workspaces are created by default: the configured one, else last used, else ~/PhpstormProjects. */
  public Path defaultParentFolder() {
    if (state.defaultWorkspaceFolder != null && !state.defaultWorkspaceFolder.isBlank()) {
      Path p = Path.of(state.defaultWorkspaceFolder.trim());
      if (Files.isDirectory(p)) return p;
    }
    if (state.lastParentFolder != null && !state.lastParentFolder.isBlank()) {
      Path p = Path.of(state.lastParentFolder);
      if (Files.isDirectory(p)) return p;
    }
    Path home = Path.of(System.getProperty("user.home"));
    Path projects = home.resolve("PhpstormProjects");
    return Files.isDirectory(projects) ? projects : home;
  }

  public String defaultBranchPattern() {
    String p = state.defaultBranchPattern;
    return p == null || !p.contains("{ticket}") ? WorkspaceConfig.DEFAULT_BRANCH_PATTERN : p.trim();
  }

  /** The repo names New Workspace starts with: valid names from {@code defaultRepos}, in order. */
  public List<String> defaultRepoNames() {
    return parseRepoNames(state.defaultRepos);
  }

  /** "backend, frontend" → [backend, frontend]; invalid names and repeats are dropped. */
  public static List<String> parseRepoNames(String text) {
    if (text == null) return List.of();
    return Arrays.stream(text.split("[,\\n]")).map(String::trim)
        .filter(n -> Workspace.isValidName(n) && !n.equalsIgnoreCase(Workspace.MAIN)).distinct().toList();
  }

  /** "a, b ,c" → [a, b, c]; entries with ".." or an absolute path are dropped (they're relative to the repo). */
  public static List<String> parseCopyList(String text) {
    if (text == null) return List.of();
    return Arrays.stream(text.split("[,\\n]")).map(String::trim)
        .filter(x -> !x.isEmpty() && !x.contains("..") && !x.startsWith("/"))
        .distinct().toList();
  }

  /** What core needs from the settings, for {@link IdeGit#ops()}. */
  public WorkspaceOps.Options opsOptions() {
    return new WorkspaceOps.Options(state.createLinks, parseCopyList(state.defaultCopy));
  }

  public boolean isOpenRepoTerminals() { return state.openRepoTerminals; }
  public boolean isAskAfterClone() { return !CLONE_NEVER.equals(state.afterClone); }
  public boolean isDeleteMergedBranches() { return state.deleteMergedBranches; }
  public boolean isRefreshOnFocus() { return state.refreshOnFocus; }
  public boolean isTidyRecentProjects() { return state.tidyRecentProjects; }
  public boolean isOpenAsTabButton() { return state.openAsTabButton; }
  public boolean isColorTickets() { return state.colorTickets; }

  /** Unknown or empty (older settings): a tab, as before the option existed. */
  public Tickets.OpenIn newWorkspaceOpenIn() {
    for (Tickets.OpenIn in : Tickets.OpenIn.values()) {
      if (in.name().equals(state.newWorkspaceOpenIn)) return in;
    }
    return Tickets.OpenIn.TAB;
  }

  /** @return the number of tickets created with the plugin so far, including this one */
  public synchronized int countTicketCreated() {
    return ++state.ticketsCreated;
  }

  public void setLastParentFolder(Path p) {
    state.lastParentFolder = p.toString();
  }
}
