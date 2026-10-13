package nl.netonic.workspaces.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the git command line. Never prompts: credentials must come from ssh-agent / keychain. */
public final class GitRunner {
  public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

  private final String git;
  private final Map<String, String> env;

  public GitRunner(String gitExecutable, Map<String, String> environment) {
    this.git = gitExecutable == null || gitExecutable.isBlank() ? "git" : gitExecutable;
    this.env = environment == null ? Map.of() : new HashMap<>(environment);
  }

  public static GitRunner fromSystem() {
    return new GitRunner("git", System.getenv());
  }

  public Result run(Path dir, String... args) {
    return run(dir, DEFAULT_TIMEOUT, args);
  }

  public Result run(Path dir, Duration timeout, String... args) {
    List<String> cmd = new ArrayList<>();
    cmd.add(git);
    cmd.addAll(List.of(args));
    ProcessBuilder pb = new ProcessBuilder(cmd);
    if (dir != null) pb.directory(dir.toFile());
    pb.environment().putAll(env);
    pb.environment().put("GIT_TERMINAL_PROMPT", "0");
    pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
    try {
      Process p = pb.start();
      p.getOutputStream().close();
      StreamReader out = new StreamReader(p.getInputStream());
      StreamReader err = new StreamReader(p.getErrorStream());
      out.start();
      err.start();
      boolean done = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!done) {
        p.destroyForcibly();
        return new Result(cmd, -1, out.text(), "Timed out after " + timeout.toSeconds() + "s");
      }
      out.join(2000);
      err.join(2000);
      return new Result(cmd, p.exitValue(), out.text(), err.text());
    } catch (IOException e) {
      return new Result(cmd, -1, "", "Could not run git (" + git + "): " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(cmd, -1, "", "Interrupted");
    }
  }

  /** Output of one git call. */
  public record Result(List<String> command, int exitCode, String stdout, String stderr) {
    public boolean ok() { return exitCode == 0; }

    public String out() { return stdout.strip(); }

    public String message() {
      String e = stderr.strip();
      return e.isEmpty() ? stdout.strip() : e;
    }

    public Result orThrow(String what) throws GitException {
      if (!ok()) throw new GitException(what + ": " + message());
      return this;
    }
  }

  private static final class StreamReader extends Thread {
    private final InputStream in;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    StreamReader(InputStream in) {
      this.in = in;
      setDaemon(true);
    }

    @Override
    public void run() {
      try (in) {
        in.transferTo(buf);
      } catch (IOException ignored) {
        // process went away
      }
    }

    String text() {
      synchronized (buf) {
        return buf.toString(StandardCharsets.UTF_8);
      }
    }
  }
}
