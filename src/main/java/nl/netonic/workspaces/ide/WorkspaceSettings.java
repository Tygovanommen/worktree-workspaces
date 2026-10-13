package nl.netonic.workspaces.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import nl.netonic.workspaces.core.Workspace;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Application-wide list of known workspaces + plugin options (stored in worktreeWorkspaces.xml). */
@Service(Service.Level.APP)
@State(name = "WorktreeWorkspaces", storages = @Storage("worktreeWorkspaces.xml"))
public final class WorkspaceSettings implements PersistentStateComponent<WorkspaceSettings.SettingsState> {

  /** Serialized state; public fields are persisted by the platform. */
  public static final class SettingsState {
    public List<String> workspaces = new ArrayList<>();
    public boolean mergeWindowsIntoTabs = true;
    public String lastParentFolder = "";
    public int ticketsCreated = 0;
  }

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

  /** Folder where new workspaces are created by default: last used, else ~/PhpstormProjects, else home. */
  public Path defaultParentFolder() {
    if (state.lastParentFolder != null && !state.lastParentFolder.isBlank()) {
      Path p = Path.of(state.lastParentFolder);
      if (Files.isDirectory(p)) return p;
    }
    Path home = Path.of(System.getProperty("user.home"));
    Path projects = home.resolve("PhpstormProjects");
    return Files.isDirectory(projects) ? projects : home;
  }

  /** @return the number of tickets created with the plugin so far, including this one */
  public synchronized int countTicketCreated() {
    return ++state.ticketsCreated;
  }

  public void setLastParentFolder(Path p) {
    state.lastParentFolder = p.toString();
  }
}
