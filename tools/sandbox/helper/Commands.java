package nl.netonic.workspaces.shots;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationsManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.ide.Tickets;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The commands of tools/sandbox/cmd.sh, loaded fresh for every command by {@link ShotHelper} (so they can change
 * without restarting the sandbox: {@code demo.sh --probe}). Screens are painted from Swing into an image, so no
 * Screen Recording permission is needed.
 */
public final class Commands {
  private static final double SCALE = 2;

  private Commands() {}

  /** One command line from tools/sandbox/cmd.sh; called on the EDT, returns the output. */
  public static String run(String line) throws Exception {
    StringBuilder out = new StringBuilder();
    run(line, out);
    return out.toString();
  }

  private static void run(String line, StringBuilder out) throws Exception {
    String[] p = line.split(" ", 2);
    String arg = p.length > 1 ? p[1] : "";
    switch (p[0]) {
      case "windows" -> {
        for (Window w : Window.getWindows()) {
          if (!w.isShowing()) continue;
          out.append(w.getClass().getName()).append(" '").append(title(w)).append("' ")
              .append(w.getBounds()).append(w == activeFrame() ? " ACTIVE" : "").append('\n');
        }
        for (Project pr : ProjectManager.getInstance().getOpenProjects()) out.append("project ").append(pr.getName()).append('\n');
      }
      case "open" -> {
        // open <workspace root> <ticket>
        String[] a = arg.split(" ");
        Workspace ws = Workspace.load(Path.of(a[0]));
        Tickets.open(ws.ticket(a[1]));
      }
      case "focus" -> ProjectUtil.focusProjectWindow(project(arg), true);
      case "size" -> {
        String[] a = arg.split(" ");
        Window w = activeFrame();
        w.setBounds(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
        w.validate();
      }
      case "action" -> {
        AnAction action = ActionManager.getInstance().getAction(arg);
        if (action == null) throw new IllegalArgumentException("no action " + arg);
        Component focus = IdeFocusManager.getGlobalInstance().getFocusOwner();
        Component c = focus != null ? focus : ((JFrame) activeFrame()).getRootPane();
        // later: an action that shows a modal dialog only returns once it is closed
        later(() -> ActionManager.getInstance().tryToExecute(action, null, c, ActionPlaces.MAIN_TOOLBAR, true));
      }
      case "toolwindow" -> {
        ToolWindow tw = ToolWindowManager.getInstance(activeProject()).getToolWindow(arg);
        tw.activate(null);
      }
      case "hidetoolwindow" -> ToolWindowManager.getInstance(activeProject()).getToolWindow(arg).hide();
      case "file" -> {
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(arg);
        FileEditorManager.getInstance(activeProject()).openFile(vf, true);
      }
      case "closefiles" -> {
        FileEditorManager m = FileEditorManager.getInstance(activeProject());
        for (VirtualFile f : m.getOpenFiles()) m.closeFile(f);
      }
      case "notifications" -> {
        for (Project pr : ProjectManager.getInstance().getOpenProjects()) {
          for (Notification n : NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification.class, pr)) {
            n.expire();
          }
        }
      }
      case "click" -> {
        Component c = find(arg);
        if (c == null) {
          out.append("not found: ").append(arg);
          return;
        }
        // later: a button that shows a modal dialog only returns once it is closed
        if (c instanceof AbstractButton b) later(b::doClick);
        else if (hasDoClick(c)) later(() -> invokeDoClick(c));
        else click(c);
        out.append("clicked ").append(c.getClass().getName());
      }
      case "probe" -> out.append(Probe.run(arg));
      case "actionwith" -> {
        // actionwith <action id> <workspace root> [ticket] [repo]: as if that row were selected in the panel
        String[] a = arg.split(" ");
        AnAction action = ActionManager.getInstance().getAction(a[0]);
        Workspace ws = Workspace.load(Path.of(a[1]));
        com.intellij.openapi.actionSystem.impl.SimpleDataContext.Builder b =
            com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, activeProject());
        if (a.length > 2) {
          nl.netonic.workspaces.core.Ticket t = ws.ticket(a[2]);
          b.add(nl.netonic.workspaces.ide.ActionContext.SELECTED_TICKET, t);
          if (a.length > 3) {
            for (nl.netonic.workspaces.core.TicketRepo r : t.repos()) {
              if (r.name().equals(a[3])) b.add(nl.netonic.workspaces.ide.ActionContext.SELECTED_REPO, r);
            }
          }
        } else {
          b.add(nl.netonic.workspaces.ide.ActionContext.SELECTED_WORKSPACE, ws);
        }
        com.intellij.openapi.actionSystem.AnActionEvent e = com.intellij.openapi.actionSystem.AnActionEvent
            .createFromDataContext(ActionPlaces.POPUP, null, b.build());
        action.update(e);
        out.append("enabled=").append(e.getPresentation().isEnabled()).append(" text=").append(e.getPresentation().getText());
        if (e.getPresentation().isEnabled()) later(() -> action.actionPerformed(e));
      }
      case "list" -> {
        // the rows of the lists in showing popups / dialogs, selected one marked
        for (Window w : Window.getWindows()) {
          if (!w.isShowing() || w instanceof JFrame) continue;
          for (Component c : all(w)) {
            if (c instanceof javax.swing.JList<?> l && l.isShowing()) {
              for (int k = 0; k < l.getModel().getSize(); k++) {
                Object v = l.getModel().getElementAt(k);
                String text = v instanceof com.intellij.ui.popup.ActionPopupStep ? v.toString()
                    : v instanceof com.intellij.ui.popup.PopupFactoryImpl.ActionItem item ? item.getText() : String.valueOf(v);
                out.append(k == l.getSelectedIndex() ? "> " : "  ").append(text).append('\n');
              }
            }
          }
        }
      }
      case "popupsearch" -> {
        // what typing into the open popup's search field does (the sandbox has no keyboard focus)
        com.intellij.ui.popup.list.ListPopupImpl popup = topListPopup();
        JTextComponent field = null;
        for (Component c : all(SwingUtilities.getWindowAncestor(popup.getList()))) {
          if (c instanceof JTextComponent t && t.isShowing() && t.isEditable()) field = t;
        }
        popup.getSpeedSearch().updatePattern(arg);
        if (field != null) field.setText(arg);
        // the popup refilters from its key handling; do what it does after a key
        for (Class<?> k = popup.getClass(); k != null; k = k.getSuperclass()) {
          try {
            java.lang.reflect.Method m = k.getDeclaredMethod("onSpeedSearchPatternChanged");
            m.setAccessible(true);
            m.invoke(popup);
            break;
          } catch (NoSuchMethodException ignored) {
            // up
          }
        }
        out.append("searching ").append(popup.getSpeedSearch().getFilter());
      }
      case "popupselect" -> {
        com.intellij.ui.popup.list.ListPopupImpl popup = topListPopup();
        out.append("selected ").append(popup.getList().getSelectedValue());
        later(() -> popup.handleSelect(true));
      }
      case "esc" -> {
        // closes the topmost popup, else the topmost dialog (Cancel)
        com.intellij.ui.popup.list.ListPopupImpl popup = topListPopupOrNull();
        if (popup != null) {
          popup.cancel();
          out.append("popup closed");
          return;
        }
        Window d = topDialog();
        com.intellij.openapi.ui.DialogWrapper dw = com.intellij.openapi.ui.DialogWrapper.findInstance(((JDialog) d).getContentPane());
        if (dw != null) {
          later(() -> dw.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE));
          out.append("dialog cancelled");
        } else {
          d.dispose();
          out.append("dialog disposed");
        }
      }
      case "clone" -> {
        // clone <checkout dir>: what Get from VCS does after cloning: the checkout listeners in order, on a
        // background thread, until one takes it
        Path dir = Path.of(arg);
        Project project = activeProject();
        var listeners = com.intellij.openapi.vcs.checkout.CheckoutListener.EP_NAME.getExtensionList();
        for (var l : listeners) out.append("listener ").append(l.getClass().getName()).append('\n');
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
          for (var l : listeners) {
            if (l.processCheckedOutDirectory(project, dir)) {
              com.intellij.openapi.diagnostic.Logger.getInstance(Commands.class).info("wsdemo clone taken by " + l.getClass().getName());
              return;
            }
          }
        });
      }
      case "openfolder" -> {
        // File › Open of a folder while a project is open: PhpStorm asks This Window / New Window / <attach processor>
        Project project = activeProject();
        later(() -> ProjectUtil.openOrImport(Path.of(arg), project, false));
      }
      case "settingsdialog" -> {
        // Settings › Tools › Worktree Workspaces as its own dialog (same Configurable)
        Project project = activeProject();
        later(() -> com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .editConfigurable(project, new nl.netonic.workspaces.ide.ui.WorkspacesConfigurable()));
      }
      case "closeproject" -> {
        Project project = null;
        for (Project open : ProjectManager.getInstance().getOpenProjects()) if (open.getName().equals(arg)) project = open;
        if (project == null) throw new IllegalArgumentException("no open project " + arg);
        Project toClose = project;
        later(() -> ProjectManager.getInstance().closeAndDispose(toClose));
      }
      case "closeterminals" -> {
        ToolWindow tw = ToolWindowManager.getInstance(activeProject()).getToolWindow("Terminal");
        tw.getContentManager().removeAllContents(true);
        tw.hide();
      }
      case "quit" -> {
        // a normal quit, so settings and the open projects are saved (a kill saves nothing)
        later(() -> com.intellij.openapi.application.ApplicationManager.getApplication().exit(true, true, false));
        out.append("quitting");
      }
      case "run" -> {
        // run <classes dir> <class> [arg]: a check compiled against the platform + plugin, with
        // public static String run(String arg), called on the EDT
        String[] a = arg.split(" ", 3);
        ClassLoader cl = new java.net.URLClassLoader(new java.net.URL[]{Path.of(a[0]).toUri().toURL()},
            Commands.class.getClassLoader());
        Object r = cl.loadClass(a[1]).getMethod("run", String.class).invoke(null, a.length > 2 ? a[2] : "");
        out.append(r);
      }
      case "ui" -> {
        // the controls of the topmost dialog (or the active frame): what "field", "check" and "combo" index into
        int i = 0;
        for (Component c : controls(uiRoot())) {
          out.append(i++).append(' ').append(control(c)).append('\n');
        }
      }
      case "field" -> {
        String[] a = arg.split(" ", 2);
        JTextComponent t = (JTextComponent) controls(uiRoot()).get(Integer.parseInt(a[0]));
        t.requestFocusInWindow();
        t.setText(a.length > 1 ? a[1] : "");
      }
      case "check" -> {
        // check <text> true|false
        int sp = arg.lastIndexOf(' ');
        String text = arg.substring(0, sp);
        boolean on = Boolean.parseBoolean(arg.substring(sp + 1));
        for (Component c : controls(uiRoot())) {
          if (c instanceof javax.swing.JCheckBox cb && text.equals(cb.getText())) {
            if (cb.isSelected() != on) cb.doClick();
            out.append(text).append(" = ").append(cb.isSelected());
            return;
          }
        }
        out.append("no checkbox ").append(text);
      }
      case "combo" -> {
        // combo <index> <text>: select the first item whose toString contains text
        String[] a = arg.split(" ", 2);
        javax.swing.JComboBox<?> cb = (javax.swing.JComboBox<?>) controls(uiRoot()).get(Integer.parseInt(a[0]));
        for (int k = 0; k < cb.getItemCount(); k++) {
          if (String.valueOf(cb.getItemAt(k)).contains(a[1])) {
            cb.setSelectedIndex(k);
            out.append("selected ").append(cb.getItemAt(k));
            return;
          }
        }
        out.append("no item ").append(a[1]);
      }
      case "expand" -> {
        // expand all rows of every tree in the active frame
        for (Component c : all(activeFrame())) {
          if (c instanceof JTree t) for (int i = 0; i < t.getRowCount(); i++) t.expandRow(i);
        }
      }
      case "selectrow" -> {
        for (Component c : all(activeFrame())) {
          if (c instanceof JTree t && t.isShowing()) {
            for (int i = 0; i < t.getRowCount(); i++) {
              if (String.valueOf(t.getPathForRow(i).getLastPathComponent()).contains(arg)) t.setSelectionRow(i);
            }
          }
        }
      }
      case "type" -> {
        Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (c instanceof JTextComponent t) {
          t.setText("");
          for (char ch : arg.toCharArray()) {
            t.dispatchEvent(new KeyEvent(t, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ch));
          }
          out.append("typed into ").append(c.getClass().getName());
        } else {
          out.append("focus owner is ").append(c);
        }
      }
      case "key" -> {
        Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        int code = switch (arg) {
          case "esc" -> KeyEvent.VK_ESCAPE;
          case "down" -> KeyEvent.VK_DOWN;
          case "enter" -> KeyEvent.VK_ENTER;
          default -> throw new IllegalArgumentException(arg);
        };
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED));
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED));
      }
      case "dump" -> {
        // dump <substring>: components of all showing windows whose class or text contains it
        for (Window w : Window.getWindows()) {
          if (!w.isShowing()) continue;
          for (Component c : all(w)) {
            String d = describe(c);
            if (arg.isEmpty() || d.contains(arg)) out.append(d).append(' ').append(c.getBounds()).append('\n');
          }
        }
      }
      case "shot" -> {
        // shot <file> frame|dialog [x y w h crop, in frame coordinates]
        String[] a = arg.split(" ");
        Window target = a[1].equals("dialog") ? topDialog() : activeFrame();
        BufferedImage img = paintWithPopups(target);
        if (a.length >= 6) {
          int x = (int) (Integer.parseInt(a[2]) * SCALE), y = (int) (Integer.parseInt(a[3]) * SCALE);
          int w = (int) (Integer.parseInt(a[4]) * SCALE), h = (int) (Integer.parseInt(a[5]) * SCALE);
          img = img.getSubimage(x, y, Math.min(w, img.getWidth() - x), Math.min(h, img.getHeight() - y));
        } else {
          img = chrome(img, target instanceof JFrame, a[1].equals("dialog") ? title(target) : null);
        }
        ImageIO.write(img, "png", new File(a[0]));
        out.append("wrote ").append(a[0]).append(' ').append(img.getWidth()).append('x').append(img.getHeight());
      }
      default -> out.append("unknown command ").append(line);
    }
  }

  // ------------------------------------------------------------ painting

  /** The window and every showing popup / child window on top of it, as a 2x image. */
  private static BufferedImage paintWithPopups(Window target) {
    Point origin = target.getLocationOnScreen();
    BufferedImage img = new BufferedImage((int) (target.getWidth() * SCALE), (int) (target.getHeight() * SCALE),
        BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    hints(g);
    g.scale(SCALE, SCALE);
    paintWindow(g, target, 0, 0);
    for (Window w : Window.getWindows()) {
      if (w == target || !w.isShowing() || !isOwnedBy(w, target)) continue;
      Point p = w.getLocationOnScreen();
      shadow(g, p.x - origin.x, p.y - origin.y, w.getWidth(), w.getHeight());
      paintWindow(g, w, p.x - origin.x, p.y - origin.y);
    }
    g.dispose();
    return img;
  }

  /** A soft shadow under a popup, as macOS draws one. */
  private static void shadow(Graphics2D g, int x, int y, int w, int h) {
    Graphics2D g2 = (Graphics2D) g.create();
    for (int i = 12; i >= 1; i--) {
      g2.setColor(new Color(0, 0, 0, 7));
      g2.fill(new RoundRectangle2D.Double(x - i, y - i + 4, w + 2 * i, h + 2 * i, 16 + 2 * i, 16 + 2 * i));
    }
    g2.dispose();
  }

  private static void paintWindow(Graphics2D g, Window w, int x, int y) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.translate(x, y);
    JRootPane root = SwingUtilities.getRootPane(w);
    if (root != null) {
      Point r = SwingUtilities.convertPoint(root, 0, 0, w);
      g2.translate(r.x, r.y);
      root.paint(g2);
    } else {
      w.paint(g2);
    }
    g2.dispose();
  }

  private static boolean isOwnedBy(Window w, Window target) {
    for (Window o = w.getOwner(); o != null; o = o.getOwner()) if (o == target) return true;
    return false;
  }

  /** Rounded corners, macOS traffic lights (frames) or a title bar (dialogs), and a soft shadow. */
  private static BufferedImage chrome(BufferedImage content, boolean frame, String dialogTitle) {
    int s = (int) SCALE;
    if (dialogTitle != null) {
      // the root pane already spans the title bar (full-window content): draw into its top 28px
      Graphics2D g = content.createGraphics();
      hints(g);
      int bar = 28 * s;
      Color bg = new Color(content.getRGB(content.getWidth() / 2, 4 * s));
      lights(g, 14 * s, bar / 2, s);
      g.setColor(luminance(bg) > 0.5 ? new Color(0x30, 0x30, 0x30) : new Color(0xDF, 0xE1, 0xE5));
      g.setFont(new java.awt.Font(".AppleSystemUIFont", java.awt.Font.BOLD, 13 * s));
      int tw = g.getFontMetrics().stringWidth(dialogTitle);
      g.drawString(dialogTitle, (content.getWidth() - tw) / 2, bar / 2 + 5 * s);
      g.dispose();
    } else if (frame) {
      Graphics2D g = content.createGraphics();
      hints(g);
      lights(g, 20 * s, 20 * s, s);
      g.dispose();
    }
    int radius = 10 * s, pad = 40 * s, w = content.getWidth(), h = content.getHeight();
    BufferedImage out = new BufferedImage(w + 2 * pad, h + 2 * pad, BufferedImage.TYPE_INT_ARGB);
    // shadow: a blurred dark rounded rect, a little lower than the window
    BufferedImage shadow = new BufferedImage(out.getWidth(), out.getHeight(), BufferedImage.TYPE_INT_ARGB);
    Graphics2D sg = shadow.createGraphics();
    hints(sg);
    sg.setColor(new Color(0, 0, 0, 90));
    sg.fill(new RoundRectangle2D.Double(pad, pad + 8 * s, w, h, radius * 2, radius * 2));
    sg.dispose();
    shadow = blur(blur(shadow, 12 * s), 12 * s);
    Graphics2D g = out.createGraphics();
    hints(g);
    g.drawImage(shadow, 0, 0, null);
    BufferedImage clipped = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D cg = clipped.createGraphics();
    hints(cg);
    cg.fill(new RoundRectangle2D.Double(0, 0, w, h, radius * 2, radius * 2));
    cg.setComposite(AlphaComposite.SrcIn);
    cg.drawImage(content, 0, 0, null);
    cg.setComposite(AlphaComposite.SrcOver);
    cg.setColor(new Color(255, 255, 255, 40));
    cg.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, radius * 2, radius * 2));
    cg.dispose();
    g.drawImage(clipped, pad, pad, null);
    g.dispose();
    return out;
  }

  private static void lights(Graphics2D g, int x, int cy, int s) {
    Color[] colors = {new Color(0xFF5F57), new Color(0xFEBC2E), new Color(0x28C840)};
    for (int i = 0; i < 3; i++) {
      g.setColor(colors[i]);
      int cx = x + i * 20 * s;
      g.fillOval(cx - 6 * s, cy - 6 * s, 12 * s, 12 * s);
    }
  }

  private static BufferedImage blur(BufferedImage src, int radius) {
    int size = radius * 2 + 1;
    float[] k = new float[size];
    Arrays.fill(k, 1f / size);
    BufferedImage h = new ConvolveOp(new Kernel(size, 1, k), ConvolveOp.EDGE_NO_OP, null).filter(src, null);
    return new ConvolveOp(new Kernel(1, size, k), ConvolveOp.EDGE_NO_OP, null).filter(h, null);
  }

  private static double luminance(Color c) {
    return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255;
  }

  private static void hints(Graphics2D g) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
  }

  // ------------------------------------------------------------ lookup

  private static Window activeFrame() {
    IdeFrame f = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
    if (f != null) {
      Component c = f.getComponent();
      return c instanceof Window w ? w : SwingUtilities.getWindowAncestor(c);
    }
    for (Window w : Window.getWindows()) if (w.isShowing() && w instanceof JFrame) return w;
    throw new IllegalStateException("no frame");
  }

  private static Project activeProject() {
    IdeFrame f = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
    return f != null && f.getProject() != null ? f.getProject() : ProjectManager.getInstance().getOpenProjects()[0];
  }

  private static Project project(String name) {
    for (Project p : ProjectManager.getInstance().getOpenProjects()) if (p.getName().equals(name)) return p;
    throw new IllegalArgumentException("no open project " + name);
  }

  private static com.intellij.ui.popup.list.ListPopupImpl topListPopup() {
    com.intellij.ui.popup.list.ListPopupImpl p = topListPopupOrNull();
    if (p == null) throw new IllegalStateException("no list popup");
    return p;
  }

  private static com.intellij.ui.popup.list.ListPopupImpl topListPopupOrNull() {
    com.intellij.ui.popup.list.ListPopupImpl found = null;
    for (Window w : Window.getWindows()) {
      if (!w.isShowing() || w instanceof JFrame || w instanceof JDialog && w.getClass().getName().contains("MyDialog")) continue;
      for (Component c : all(w)) {
        if (c instanceof javax.swing.JList<?> l && l.isShowing()
            && com.intellij.openapi.ui.popup.util.PopupUtil.getPopupContainerFor(l) instanceof com.intellij.ui.popup.list.ListPopupImpl p) {
          found = p;
        }
      }
    }
    return found;
  }

  /**
   * Like a real click: on the EDT with the write-intent lock (a plain SwingUtilities.invokeLater has none), in the
   * modality of the topmost dialog, else non-modal. Never ModalityState.any(): PhpStorm treats that as write-unsafe
   * (saveAllDocuments, model changes are refused).
   */
  private static void later(Runnable r) {
    Window d = null;
    for (Window w : Window.getWindows()) {
      if (w.isShowing() && w instanceof JDialog && w.getClass().getName().contains("MyDialog")) d = w;
    }
    com.intellij.openapi.application.ModalityState state = d != null
        ? com.intellij.openapi.application.ModalityState.stateForComponent(d)
        : com.intellij.openapi.application.ModalityState.nonModal();
    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(r, state);
  }

  private static Window uiRoot() {
    for (Window w : Window.getWindows()) {
      if (w.isShowing() && w instanceof JDialog && w.getClass().getName().contains("MyDialog")) return topDialog();
    }
    return activeFrame();
  }

  private static List<Component> controls(Window w) {
    List<Component> out = new ArrayList<>();
    for (Component c : all(w)) {
      if (!c.isShowing()) continue;
      if (c instanceof AbstractButton || c instanceof JTextComponent && ((JTextComponent) c).isEditable()
          || c instanceof javax.swing.JComboBox || c instanceof javax.swing.JLabel l && l.getText() != null && !l.getText().isBlank()) {
        if (c instanceof JTextComponent && SwingUtilities.getAncestorOfClass(javax.swing.JComboBox.class, c) != null) continue;
        out.add(c);
      }
    }
    return out;
  }

  private static String control(Component c) {
    String kind = c.getClass().getSimpleName();
    if (c instanceof javax.swing.JCheckBox cb) return "checkbox '" + cb.getText() + "' " + cb.isSelected() + (cb.isEnabled() ? "" : " disabled");
    if (c instanceof javax.swing.JRadioButton rb) return "radio '" + rb.getText() + "' " + rb.isSelected();
    if (c instanceof AbstractButton b) return "button '" + b.getText() + "'" + (b.isEnabled() ? "" : " disabled");
    if (c instanceof JTextComponent t) return "field '" + t.getText() + "'";
    if (c instanceof javax.swing.JComboBox<?> cb) {
      StringBuilder items = new StringBuilder();
      for (int k = 0; k < cb.getItemCount(); k++) items.append(k == 0 ? "" : " | ").append(cb.getItemAt(k));
      return "combo '" + cb.getSelectedItem() + "' [" + items + "]";
    }
    if (c instanceof javax.swing.JLabel l) return "label '" + l.getText() + "'";
    return kind;
  }

  private static Window topDialog() {
    Window found = null;
    for (Window w : Window.getWindows()) {
      if (w.isShowing() && w instanceof JDialog && w.getClass().getName().contains("MyDialog")) found = w;
    }
    if (found == null) throw new IllegalStateException("no dialog");
    return found;
  }

  private static String title(Window w) {
    if (w instanceof JFrame f) return f.getTitle();
    if (w instanceof JDialog d) return d.getTitle();
    return "";
  }

  /** First showing component whose class name or text contains {@code what} (searching popups/dialogs first). */
  private static Component find(String what) {
    List<Window> windows = new ArrayList<>(Arrays.asList(Window.getWindows()));
    java.util.Collections.reverse(windows);
    for (Window w : windows) {
      if (!w.isShowing()) continue;
      for (Component c : all(w)) if (c.isShowing() && describe(c).contains(what)) return c;
    }
    return null;
  }

  private static String describe(Component c) {
    String text = c instanceof AbstractButton b ? b.getText() : c instanceof javax.swing.JLabel l ? l.getText() : null;
    String acc = c.getAccessibleContext() != null ? c.getAccessibleContext().getAccessibleName() : null;
    return c.getClass().getName() + " text=" + text + " acc=" + acc;
  }

  private static List<Component> all(Component root) {
    List<Component> out = new ArrayList<>();
    out.add(root);
    if (root instanceof Container ct) for (Component c : ct.getComponents()) out.addAll(all(c));
    return out;
  }

  private static boolean hasDoClick(Component c) {
    try {
      c.getClass().getMethod("doClick");
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private static void invokeDoClick(Component c) {
    try {
      c.getClass().getMethod("doClick").invoke(c);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void click(Component c) {
    Rectangle b = c.getBounds();
    int x = b.width / 2, y = b.height / 2;
    long now = System.currentTimeMillis();
    c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_ENTERED, now, 0, x, y, 0, false));
    c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, now, MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
    c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, now, 0, x, y, 1, false, MouseEvent.BUTTON1));
    c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, now, 0, x, y, 1, false, MouseEvent.BUTTON1));
  }
}
