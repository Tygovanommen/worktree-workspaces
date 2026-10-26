package nl.netonic.workspaces.shots;

import com.intellij.ide.RecentProjectsManager;
import com.intellij.ide.RecentProjectsManagerBase;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import nl.netonic.workspaces.ide.WorkspaceModel;
import nl.netonic.workspaces.ide.WorkspaceSettings;

/**
 * IDE state for tools/sandbox/e2e.sh, run by the helper's {@code run} command:
 * {@code run <dir> nl.netonic.workspaces.shots.Probe <what> [project]}. One fact per line, easy to grep.
 */
public final class Probe {
  private Probe() {}

  public static String run(String arg) {
    String[] a = arg.split(" ", 2);
    String rest = a.length > 1 ? a[1] : "";
    StringBuilder out = new StringBuilder();
    switch (a[0]) {
      case "projects" -> {
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
          out.append("project ").append(p.getName()).append(" @ ").append(p.getBasePath()).append('\n');
        }
      }
      case "roots" -> {
        Project p = project(rest);
        for (Module m : ModuleManager.getInstance(p).getModules()) {
          for (VirtualFile r : ModuleRootManager.getInstance(m).getContentRoots()) out.append("root ").append(r.getPath()).append('\n');
        }
        for (VcsDirectoryMapping m : ProjectLevelVcsManager.getInstance(p).getDirectoryMappings()) {
          out.append("vcs ").append(m.getVcs()).append(' ').append(m.getDirectory()).append('\n');
        }
      }
      case "terminals" -> {
        ToolWindow tw = ToolWindowManager.getInstance(project(rest)).getToolWindow("Terminal");
        if (tw == null) return "no terminal window";
        out.append("visible ").append(tw.isVisible()).append('\n');
        for (Content c : tw.getContentManager().getContents()) out.append("tab ").append(c.getDisplayName()).append('\n');
      }
      case "toolwindow" -> {
        ToolWindow tw = ToolWindowManager.getInstance(project(rest)).getToolWindow("Workspaces");
        out.append(tw == null ? "none" : "workspaces available=" + tw.isAvailable());
      }
      case "action" -> {
        var action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(rest);
        out.append(action == null ? "no action " + rest : "action " + action.getTemplatePresentation().getText());
      }
      case "tabgroup" -> {
        // the macOS window tabs the project's frame is in; "alone" for a window without tabs
        javax.swing.JFrame frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project(rest));
        java.util.List<String> tabs = new java.util.ArrayList<>();
        collectTabs(frame, tabs);
        out.append(tabs.isEmpty() ? "alone" : String.join(" | ", tabs));
      }
      case "registry" -> out.append(Registry.get(rest).asBoolean());
      case "recent" -> {
        for (String path : ((RecentProjectsManagerBase) RecentProjectsManager.getInstance()).getRecentPaths()) {
          out.append("recent ").append(path).append('\n');
        }
      }
      case "property" -> {
        // property <key> <project>
        String[] b = rest.split(" ", 2);
        out.append(PropertiesComponent.getInstance(project(b[1])).getValue(b[0]));
      }
      case "color" -> {
        try {
          Object service = com.intellij.openapi.application.ApplicationManager.getApplication()
              .getService(Class.forName("com.intellij.ide.ProjectWindowCustomizerService"));
          out.append(service.getClass().getMethod("getCurrentProjectColorIndex", Project.class).invoke(service, project(rest)));
        } catch (Exception e) {
          out.append("error ").append(e);
        }
      }
      case "setcolor" -> {
        // setcolor <index> <project>: what Change Project Color does
        String[] b = rest.split(" ", 2);
        try {
          Object service = com.intellij.openapi.application.ApplicationManager.getApplication()
              .getService(Class.forName("com.intellij.ide.ProjectWindowCustomizerService"));
          Project p = project(b[1]);
          service.getClass().getMethod("clearToolbarColorsAndInMemoryCache", Project.class).invoke(service, p);
          service.getClass().getMethod("setAssociatedColorsIndex", Project.class, int.class).invoke(service, p, Integer.parseInt(b[0]));
          out.append("color ").append(b[0]);
        } catch (Exception e) {
          out.append("error ").append(e);
        }
      }
      case "settings" -> {
        WorkspaceSettings.SettingsState s = WorkspaceSettings.getInstance().getState();
        out.append("workspaces ").append(s.workspaces).append('\n');
        out.append("createLinks ").append(s.createLinks).append('\n');
        out.append("tidyRecentProjects ").append(s.tidyRecentProjects).append('\n');
        out.append("ticketsCreated ").append(s.ticketsCreated).append('\n');
        out.append("markdownRunInFileFolder ").append(s.markdownRunInFileFolder).append('\n');
      }
      case "set" -> {
        // set <field> <true|false|text>: a WorkspaceSettings option, as the Settings page would
        String[] b = rest.split(" ", 2);
        try {
          var f = WorkspaceSettings.SettingsState.class.getField(b[0]);
          Object v = f.getType() == boolean.class ? (Object) Boolean.parseBoolean(b[1]) : b[1];
          f.set(WorkspaceSettings.getInstance().getState(), v);
          out.append(b[0]).append(" = ").append(v);
        } catch (Exception e) {
          out.append("error ").append(e);
        }
      }
      case "model" -> {
        for (WorkspaceModel.WorkspaceView wv : WorkspaceModel.getInstance().snapshot()) {
          out.append("workspace ").append(wv.name()).append(wv.error() != null ? " ERROR " + wv.error() : "").append('\n');
          for (WorkspaceModel.TicketView tv : wv.tickets()) {
            out.append("  ticket ").append(tv.ticket().getName());
            for (WorkspaceModel.RepoView rv : tv.repos()) {
              out.append(" | ").append(rv.repo().name()).append(' ').append(rv.repo().kind());
              if (rv.status() != null) out.append(' ').append(rv.status().branch()).append(" changed=").append(rv.status().changed())
                  .append(" unpushed=").append(rv.status().unpushed());
            }
            out.append('\n');
          }
        }
      }
      case "refresh" -> WorkspaceModel.getInstance().refresh();
      default -> out.append("unknown probe ").append(a[0]);
    }
    return out.toString();
  }

  private static void collectTabs(java.awt.Component c, java.util.List<String> tabs) {
    String n = c.getClass().getName();
    if (n.startsWith("com.intellij.ui.mac.WindowTabsComponent$") && n.indexOf('$') == n.lastIndexOf('$') && c.isShowing()) {
      tabs.add(String.valueOf(c.getAccessibleContext().getAccessibleName()).replaceAll(" – .*", ""));
    }
    if (c instanceof java.awt.Container ct) for (java.awt.Component k : ct.getComponents()) collectTabs(k, tabs);
  }

  private static Project project(String name) {
    for (Project p : ProjectManager.getInstance().getOpenProjects()) if (p.getName().equals(name)) return p;
    throw new IllegalArgumentException("no open project '" + name + "'");
  }
}
