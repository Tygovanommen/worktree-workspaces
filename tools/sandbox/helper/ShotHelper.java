package nl.netonic.workspaces.shots;

import com.intellij.ide.AppLifecycleListener;

import javax.swing.SwingUtilities;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test and screenshot helper, installed only in the sandboxed PhpStorm of tools/sandbox/demo.sh.
 * Polls {@code $wsdemo.dir/cmd/*.cmd} (one command per file), runs it on the EDT through {@link Commands} (loaded
 * fresh from helper-build/commands every time) and renames it to .done, output in .out.
 */
public final class ShotHelper implements AppLifecycleListener {
  private static final AtomicBoolean STARTED = new AtomicBoolean();

  @Override
  public void appFrameCreated(List<String> commandLineArgs) {
    start();
  }

  @Override
  public void appStarted() {
    start();
  }

  private static void start() {
    String dir = System.getProperty("wsdemo.dir");
    if (dir == null || !STARTED.compareAndSet(false, true)) return;
    Thread t = new Thread(() -> poll(Path.of(dir)), "wsdemo-helper");
    t.setDaemon(true);
    t.start();
  }

  private static void poll(Path root) {
    Path cmds = root.resolve("cmd");
    Path classes = root.resolve("helper-build/commands");
    while (true) {
      try {
        Files.createDirectories(cmds);
        File[] files = cmds.toFile().listFiles((d, n) -> n.endsWith(".cmd"));
        if (files != null && files.length > 0) {
          Arrays.sort(files);
          File f = files[0];
          String cmd = Files.readString(f.toPath()).trim();
          StringBuilder out = new StringBuilder();
          SwingUtilities.invokeAndWait(() -> {
            try (URLClassLoader cl = new URLClassLoader(new URL[]{classes.toUri().toURL()}, ShotHelper.class.getClassLoader())) {
              out.append(cl.loadClass("nl.netonic.workspaces.shots.Commands").getMethod("run", String.class).invoke(null, cmd));
            } catch (Throwable e) {
              Throwable cause = e instanceof InvocationTargetException ite ? ite.getCause() : e;
              out.append("ERROR ").append(cause).append('\n');
              for (StackTraceElement el : cause.getStackTrace()) out.append("  at ").append(el).append('\n');
            }
          });
          String base = f.getName().substring(0, f.getName().length() - 4);
          Files.writeString(cmds.resolve(base + ".out"), out.toString());
          Files.move(f.toPath(), cmds.resolve(base + ".done"));
        }
        Thread.sleep(200);
      } catch (Throwable e) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ignored) {
          return;
        }
      }
    }
  }
}
