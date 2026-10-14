package nl.netonic.workspaces.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.util.EnvironmentUtil;
import git4idea.GitReference;
import git4idea.config.GitExecutableManager;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import nl.netonic.workspaces.core.GitException;
import nl.netonic.workspaces.core.GitRunner;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.core.WorkspaceOps;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Git as configured in PhpStorm (Settings › Version Control › Git), with the login-shell environment. */
public final class IdeGit {
  private IdeGit() {}

  public static GitRunner runner() {
    String path = "git";
    try {
      String configured = GitExecutableManager.getInstance().getPathToGit();
      if (configured != null && !configured.isBlank()) path = configured;
    } catch (Throwable ignored) {
      // Git plugin unavailable: fall back to git on PATH
    }
    Map<String, String> env;
    try {
      env = EnvironmentUtil.getEnvironmentMap();
    } catch (Throwable t) {
      env = System.getenv();
    }
    return new GitRunner(path, env);
  }

  public static WorkspaceOps ops() {
    return new WorkspaceOps(runner(), WorkspaceSettings.getInstance().opsOptions());
  }

  /**
   * Branches of main/&lt;repo&gt; as PhpStorm's Git branches popup shows them: from the Git plugin when main/&lt;repo&gt;
   * or one of its worktrees (they share branches) is a Git root of an open project, else from git. Not on the EDT.
   */
  public static WorkspaceOps.Branches branches(Workspace ws, String repo) throws GitException {
    Path main = ws.mainRepoDir(repo);
    try {
      GitRepository exact = null;
      GitRepository worktree = null;
      projects:
      for (Project p : ProjectManager.getInstance().getOpenProjects()) {
        if (p.isDisposed()) continue;
        for (GitRepository r : GitRepositoryManager.getInstance(p).getRepositories()) {
          Path root = Path.of(r.getRoot().getPath());
          if (root.equals(main)) {
            exact = r;
            break projects;
          }
          // <workspace>/<ticket>/<repo>
          if (worktree == null && root.getFileName().toString().equals(repo) && root.getParent() != null
              && ws.getRoot().equals(root.getParent().getParent())) {
            worktree = r;
          }
        }
      }
      GitRepository found = exact != null ? exact : worktree;
      if (found != null) {
        return new WorkspaceOps.Branches(fullNames(found.getBranches().getLocalBranches()),
            fullNames(found.getBranches().getRemoteBranches()));
      }
    } catch (RuntimeException ignored) {
      // Git plugin unavailable or the repo is being disposed: ask git
    }
    return ops().listBranches(main);
  }

  private static List<String> fullNames(Collection<? extends GitReference> refs) {
    return refs.stream()
        .map(GitReference::getFullName)
        .filter(n -> !n.endsWith("/HEAD"))
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .toList();
  }
}
