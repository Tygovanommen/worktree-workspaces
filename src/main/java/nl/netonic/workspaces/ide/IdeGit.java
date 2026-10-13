package nl.netonic.workspaces.ide;

import com.intellij.util.EnvironmentUtil;
import git4idea.config.GitExecutableManager;
import nl.netonic.workspaces.core.GitRunner;
import nl.netonic.workspaces.core.WorkspaceOps;

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
    return new WorkspaceOps(runner());
  }
}
