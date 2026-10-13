package nl.netonic.workspaces.core;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Git state of one checkout, from {@code git status --porcelain=v2 --branch}
 * plus {@code git rev-list --count HEAD --not --remotes}.
 *
 * @param branch    current branch, or "(detached)"
 * @param upstream  tracking branch or null
 * @param ahead     commits ahead of upstream (0 without upstream)
 * @param behind    commits behind upstream (0 without upstream)
 * @param changed   modified + staged + untracked files
 * @param unpushed  commits that exist on no remote at all
 * @param error     non-null when git failed
 */
public record RepoStatus(String branch, String upstream, int ahead, int behind, int changed, int unpushed,
                         String error) {

  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  public static RepoStatus error(String message) {
    return new RepoStatus(null, null, 0, 0, 0, 0, message);
  }

  public boolean isClean() {
    return error == null && changed == 0 && unpushed == 0;
  }

  public static RepoStatus read(GitRunner git, Path dir) {
    GitRunner.Result st = git.run(dir, TIMEOUT, "status", "--porcelain=v2", "--branch");
    if (!st.ok()) return error(st.message());
    RepoStatus parsed = parse(st.stdout());
    GitRunner.Result rl = git.run(dir, TIMEOUT, "rev-list", "--count", "HEAD", "--not", "--remotes");
    int unpushed = 0;
    if (rl.ok()) {
      try {
        unpushed = Integer.parseInt(rl.out());
      } catch (NumberFormatException ignored) {
        // keep 0
      }
    }
    return new RepoStatus(parsed.branch, parsed.upstream, parsed.ahead, parsed.behind, parsed.changed, unpushed, null);
  }

  /** Parses porcelain v2 output (visible for tests). */
  public static RepoStatus parse(String porcelain) {
    String branch = null;
    String upstream = null;
    int ahead = 0;
    int behind = 0;
    int changed = 0;
    for (String line : porcelain.split("\n")) {
      if (line.isBlank()) continue;
      if (line.startsWith("# branch.head ")) {
        branch = line.substring("# branch.head ".length()).trim();
      } else if (line.startsWith("# branch.upstream ")) {
        upstream = line.substring("# branch.upstream ".length()).trim();
      } else if (line.startsWith("# branch.ab ")) {
        String[] parts = line.substring("# branch.ab ".length()).trim().split(" ");
        for (String p : parts) {
          try {
            if (p.startsWith("+")) ahead = Integer.parseInt(p.substring(1));
            if (p.startsWith("-")) behind = Integer.parseInt(p.substring(1));
          } catch (NumberFormatException ignored) {
            // ignore
          }
        }
      } else if (!line.startsWith("#") && !line.startsWith("!")) {
        changed++;
      }
    }
    return new RepoStatus(branch, upstream, ahead, behind, changed, 0, null);
  }

  /** Short label for the tree: "3 changed  ↑1", "clean", ... */
  public String summary() {
    if (error != null) return "git error";
    StringBuilder b = new StringBuilder();
    if (changed > 0) b.append(changed).append(" changed");
    int push = Math.max(unpushed, ahead);
    if (push > 0) b.append(b.isEmpty() ? "" : "  ").append('↑').append(push);
    if (behind > 0) b.append(b.isEmpty() ? "" : "  ").append('↓').append(behind);
    return b.isEmpty() ? "clean" : b.toString();
  }
}
