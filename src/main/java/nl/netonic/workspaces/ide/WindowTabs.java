package nl.netonic.workspaces.ide;

import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.tabs.JBTabs;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JFrame;
import javax.swing.UIManager;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ContainerEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Makes the macOS window tabs (one per project window, Window › Merge All Project Windows) easy to see: a dot in each
 * project's toolbar color before its title, and a light fill on the unselected tabs so they read as tabs.
 *
 * <p>PhpStorm draws these tabs itself ({@code com.intellij.ui.mac.WindowTabsComponent}, a {@link JBTabs}; each
 * {@link TabInfo}'s object is the window). It paints them only with the theme's {@code MainWindow.Tab.*} colors, so
 * the fill is set there (again after every theme change, and put back when the option is off); the dot is a
 * {@link TabInfo#setIcon} per tab. The tab bar is rebuilt on every merge / move, so a container listener restyles it.
 */
public final class WindowTabs {
  private static final Logger LOG = Logger.getInstance(WindowTabs.class);
  private static final String TABS_CLASS = "com.intellij.ui.mac.WindowTabsComponent";
  private static final String CUSTOMIZER = "com.intellij.ide.ProjectWindowCustomizerService";
  private static final String[] KEYS = {"MainWindow.Tab.background", "MainWindow.Tab.hoverBackground"};

  private static boolean installed;
  private static boolean scheduled;
  /** The theme's own values of {@link #KEYS} while ours are set; empty when ours aren't. */
  private static final Map<String, Object> original = new HashMap<>();
  private static final Set<Container> tabBars = Collections.newSetFromMap(new WeakHashMap<>());

  private WindowTabs() {}

  /** Once, on the first project open; any thread. */
  static void installLater() {
    if (!SystemInfo.isMac) return;
    ApplicationManager.getApplication().invokeLater(WindowTabs::install);
  }

  private static void install() {
    if (installed) return;
    installed = true;
    Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
      // a tab bar gets its tab labels added whenever PhpStorm (re)builds it
      if (event instanceof ContainerEvent ce && isTabBar(ce.getContainer())) {
        tabBars.add(ce.getContainer());
        refreshLater();
      }
    }, AWTEvent.CONTAINER_EVENT_MASK);
    ApplicationManager.getApplication().getMessageBus().connect()
        .subscribe(LafManagerListener.TOPIC, (LafManagerListener) source -> {
          original.clear(); // the new theme brought its own values
          refreshLater();
        });
    for (Window w : Window.getWindows()) {
      if (w instanceof JFrame frame) findTabBars(frame.getRootPane(), 0);
    }
    refreshLater();
  }

  /** Re-applies fill and dots, e.g. after a project's color changed or the option was switched. EDT. */
  public static void refreshLater() {
    if (!SystemInfo.isMac || scheduled) return;
    scheduled = true;
    ApplicationManager.getApplication().invokeLater(() -> {
      scheduled = false;
      try {
        refresh();
      } catch (Throwable t) {
        LOG.info("Could not style the window tabs", t);
      }
    });
  }

  private static void refresh() {
    boolean on = WorkspaceSettings.getInstance().isStyleWindowTabs();
    if (on) applyFill();
    else restoreFill();
    for (Container bar : tabBars.toArray(Container[]::new)) {
      if (!bar.isDisplayable()) continue;
      for (TabInfo info : ((JBTabs) bar).getTabs()) {
        Color color = on ? projectColor(info.getObject()) : null;
        Icon icon = color == null ? null : new Dot(color);
        if (!Objects.equals(info.getIcon(), icon)) info.setIcon(icon); // equal dots: no relayout, no new event
      }
      bar.repaint();
    }
  }

  /** Unselected tabs a little lighter (dark theme) or darker (light theme) than the toolbar; the selected one keeps
   *  the theme's color. */
  private static void applyFill() {
    if (original.isEmpty()) {
      for (String key : KEYS) original.put(key, UIManager.get(key));
    }
    Color bar = UIManager.getColor("MainToolbar.background");
    if (bar == null) bar = UIManager.getColor("Panel.background");
    if (bar == null) return;
    Color towards = JBColor.isBright() ? Color.BLACK : Color.WHITE;
    UIManager.put(KEYS[0], ColorUtil.mix(bar, towards, 0.07));
    UIManager.put(KEYS[1], ColorUtil.mix(bar, towards, 0.13));
  }

  private static void restoreFill() {
    if (original.isEmpty()) return;
    original.forEach(UIManager::put);
    original.clear();
  }

  /** The toolbar color PhpStorm gives the window's project (Change Project Color); null if unknown. */
  private static @Nullable Color projectColor(@Nullable Object window) {
    if (window == null) return null;
    for (Project p : ProjectManager.getInstance().getOpenProjects()) {
      if (p.isDisposed() || WindowManager.getInstance().getFrame(p) != window) continue;
      try {
        // @ApiStatus.Internal, so by reflection (see TicketColors)
        Object service = ApplicationManager.getApplication().getService(Class.forName(CUSTOMIZER));
        Object c = service.getClass().getMethod("getGradientProjectColor", Project.class).invoke(service, p);
        return c instanceof Color color ? color : null;
      } catch (Throwable t) {
        LOG.debug("No project color for " + p.getName(), t);
        return null;
      }
    }
    return null;
  }

  private static boolean isTabBar(@Nullable Component c) {
    return c instanceof JBTabs && c.getClass().getName().equals(TABS_CLASS);
  }

  /** The tab bars sit near the top of the frame; no need to walk the editor and tool windows. */
  private static void findTabBars(Component c, int depth) {
    if (isTabBar(c)) {
      tabBars.add((Container) c);
      return;
    }
    if (depth > 8 || !(c instanceof Container container)) return;
    for (Component child : container.getComponents()) findTabBars(child, depth + 1);
  }

  /** A filled circle, as wide as a small icon. */
  private record Dot(Color color) implements Icon {
    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
      Graphics2D g2 = (Graphics2D) g.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color);
        int d = JBUI.scale(8);
        int pad = (getIconWidth() - d) / 2;
        g2.fillOval(x + pad, y + (getIconHeight() - d) / 2, d, d);
      } finally {
        g2.dispose();
      }
    }

    @Override
    public int getIconWidth() {
      return JBUI.scale(12);
    }

    @Override
    public int getIconHeight() {
      return JBUI.scale(12);
    }
  }
}
