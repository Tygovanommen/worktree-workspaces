package nl.netonic.workspaces.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vfs.VfsUtilCore;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.TicketRepo;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps an open ticket project in line with the folders on disk: repos the ticket doesn't touch are
 * attached from main/ as content roots, and every repo is registered as a Git root. Runs on the EDT.
 */
public final class ProjectRoots {
  private static final Logger LOG = Logger.getInstance(ProjectRoots.class);

  private ProjectRoots() {}

  /** Reads the folders in the background, then updates the project model on the EDT. */
  public static void syncLater(Project project, Ticket ticket) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      List<TicketRepo> repos = ticket.repos();
      RepoTerminals.remember(project, repos);
      ApplicationManager.getApplication().invokeLater(() -> sync(project, ticket, repos), project.getDisposed());
    });
  }

  private static void sync(Project project, Ticket ticket, List<TicketRepo> repos) {
    if (project.isDisposed()) return;
    try {
      syncContentRoots(project, ticket, repos);
      syncVcsMappings(project, ticket, repos);
    } catch (Throwable t) {
      LOG.warn("Could not sync roots of " + ticket.displayName(), t);
    }
  }

  private static void syncContentRoots(Project project, Ticket ticket, List<TicketRepo> repos) {
    if (ticket.isMain()) return;
    Module[] modules = ModuleManager.getInstance(project).getModules();
    if (modules.length == 0) return;
    Module module = modules[0];

    Set<String> wanted = new LinkedHashSet<>();
    for (TicketRepo r : repos) {
      if (r.kind() == TicketRepo.Kind.ATTACHED) wanted.add(url(r.dir()));
    }
    String mainPrefix = url(ticket.getWorkspace().mainDir()) + "/";
    Set<String> existing = new HashSet<>(Arrays.asList(ModuleRootManager.getInstance(module).getContentRootUrls()));
    boolean needsAdd = wanted.stream().anyMatch(u -> !existing.contains(u));
    boolean needsRemove = existing.stream().anyMatch(u -> u.startsWith(mainPrefix) && !wanted.contains(u));
    if (!needsAdd && !needsRemove) return;

    ModuleRootModificationUtil.updateModel(module, model -> {
      Set<String> present = new LinkedHashSet<>();
      for (ContentEntry ce : model.getContentEntries()) {
        if (ce.getUrl().startsWith(mainPrefix) && !wanted.contains(ce.getUrl())) model.removeContentEntry(ce);
        else present.add(ce.getUrl());
      }
      for (String u : wanted) if (!present.contains(u)) model.addContentEntry(u);
    });
  }

  private static void syncVcsMappings(Project project, Ticket ticket, List<TicketRepo> repos) {
    ProjectLevelVcsManager vcs = ProjectLevelVcsManager.getInstance(project);
    String wsRoot = FileUtil.toSystemIndependentName(ticket.getWorkspace().getRoot().toString());
    String projectDir = FileUtil.toSystemIndependentName(ticket.getDir().toString());

    Set<String> wanted = new LinkedHashSet<>();
    for (TicketRepo r : repos) {
      if (r.kind() != TicketRepo.Kind.MISSING) wanted.add(FileUtil.toSystemIndependentName(r.dir().toString()));
    }

    List<VcsDirectoryMapping> result = new ArrayList<>();
    Set<String> have = new LinkedHashSet<>();
    boolean changed = false;
    for (VcsDirectoryMapping m : vcs.getDirectoryMappings()) {
      String dir = m.getDirectory().isEmpty() ? projectDir : FileUtil.toSystemIndependentName(m.getDirectory());
      if (isManaged(dir, projectDir, wsRoot) && !wanted.contains(dir)) {
        changed = true; // e.g. the "<Project>" root mapping: the ticket folder itself is no repo
        continue;
      }
      result.add(m);
      have.add(dir);
    }
    for (String dir : wanted) {
      if (!have.contains(dir)) {
        result.add(new VcsDirectoryMapping(dir, "Git"));
        changed = true;
      }
    }
    if (changed) vcs.setDirectoryMappings(result);
  }

  /**
   * Mappings the plugin owns: the ticket folder, a ticket or main/ folder, or a repo inside one. Deeper roots the
   * user added (a composer path package with its own .git) are left alone.
   */
  private static boolean isManaged(String dir, String projectDir, String wsRoot) {
    if (dir.equals(projectDir)) return true;
    String parent = parent(dir);
    return parent != null && (parent.equals(wsRoot) || wsRoot.equals(parent(parent)));
  }

  private static @Nullable String parent(String dir) {
    int slash = dir.lastIndexOf('/');
    return slash > 0 ? dir.substring(0, slash) : null;
  }

  private static String url(Path p) {
    return VfsUtilCore.pathToUrl(FileUtil.toSystemIndependentName(p.toAbsolutePath().normalize().toString()));
  }
}
