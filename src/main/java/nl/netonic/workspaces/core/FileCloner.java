package nl.netonic.workspaces.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Copies files and folders (vendor/, node_modules/, .env) into a new worktree.
 * On macOS it uses {@code cp -c}, i.e. APFS clones: instant and no extra disk space until a file changes.
 */
public final class FileCloner {
  public enum Method { APFS_CLONE, REFLINK_OR_COPY, COPY }

  private FileCloner() {}

  /** @return the method used, or null when nothing was copied (source missing or target exists). */
  public static Method copy(Path src, Path dst) throws IOException {
    if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) return null;
    if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) return null;
    Files.createDirectories(dst.getParent());

    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("mac")) {
      if (run(List.of("/bin/cp", "-c", "-R", src.toString(), dst.toString()))) return Method.APFS_CLONE;
      deleteQuietly(dst);
      if (run(List.of("/bin/cp", "-R", src.toString(), dst.toString()))) return Method.COPY;
      deleteQuietly(dst);
    } else if (os.contains("linux")) {
      if (run(List.of("cp", "-R", "--reflink=auto", src.toString(), dst.toString()))) return Method.REFLINK_OR_COPY;
      deleteQuietly(dst);
    }
    try {
      javaCopy(src, dst);
    } catch (IOException e) {
      deleteQuietly(dst); // no half-copied vendor/ or node_modules/
      throw e;
    }
    return Method.COPY;
  }

  private static boolean run(List<String> cmd) {
    try {
      // discard output instead of reading it, so a hung cp can't block us past the timeout
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start();
      p.getOutputStream().close();
      if (!p.waitFor(30, TimeUnit.MINUTES)) {
        p.destroyForcibly();
        return false;
      }
      return p.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void javaCopy(Path src, Path dst) throws IOException {
    if (!Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
      Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
      return;
    }
    Files.walkFileTree(src, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Path target = dst.resolve(src.relativize(file).toString());
        if (Files.isSymbolicLink(file)) {
          Files.createSymbolicLink(target, Files.readSymbolicLink(file));
        } else {
          Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /** Recursively deletes a file or folder; symlinks and Windows junctions are removed, never followed. */
  public static void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    if (isLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      Files.delete(path);
      return;
    }
    Files.walkFileTree(path, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        // a junction looks like a plain directory to Java (isOther on Windows): unlink it, don't enter it
        if (!dir.equals(path) && attrs.isOther()) {
          Files.delete(dir);
          return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        if (exc != null) throw exc;
        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static boolean isLink(Path p) throws IOException {
    return Files.isSymbolicLink(p)
        || Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther();
  }

  static void deleteQuietly(Path p) {
    try {
      deleteRecursively(p);
    } catch (IOException ignored) {
      // best effort
    }
  }
}
