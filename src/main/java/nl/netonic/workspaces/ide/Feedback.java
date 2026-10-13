package nl.netonic.workspaces.ide;

import com.intellij.ide.BrowserUtil;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Links to the GitHub repo: report an issue (pre-filled with versions), star it, and a one-time nudge. */
public final class Feedback {
  public static final String REPO = "https://github.com/Tygovanommen/worktree-workspaces";
  private static final String PLUGIN_ID = "nl.netonic.worktree-workspaces";
  /** Ask for a star once, after this many tickets: by then the plugin has proven itself (or not). */
  private static final int NUDGE_AFTER_TICKETS = 3;

  private Feedback() {}

  public static void openRepo() {
    BrowserUtil.browse(REPO);
  }

  /** Bug report form with PhpStorm, plugin and OS versions filled in (ids match .github/ISSUE_TEMPLATE/bug_report.yml). */
  public static void reportIssue() {
    ApplicationInfo app = ApplicationInfo.getInstance();
    String url = REPO + "/issues/new?template=bug_report.yml"
        + "&phpstorm=" + enc(app.getFullApplicationName() + " (" + app.getBuild().asString() + ")")
        + "&plugin=" + enc(pluginVersion())
        + "&os=" + enc(System.getProperty("os.name") + " " + System.getProperty("os.version")
            + " (" + System.getProperty("os.arch") + ")");
    BrowserUtil.browse(url);
  }

  /** Call after a ticket was created; shows the star nudge once, on the Nth ticket. */
  public static void ticketCreated(@Nullable Project project) {
    WorkspaceSettings settings = WorkspaceSettings.getInstance();
    if (settings.countTicketCreated() != NUDGE_AFTER_TICKETS) return;
    NotificationGroupManager.getInstance().getNotificationGroup(Notifier.GROUP)
        .createNotification("Is Worktree Workspaces saving you time?",
            "It's free and open source. A star on GitHub helps other developers find it; "
                + "an issue helps make it better.", NotificationType.INFORMATION)
        .addAction(NotificationAction.createSimpleExpiring("Star on GitHub", Feedback::openRepo))
        .addAction(NotificationAction.createSimpleExpiring("Report an issue", Feedback::reportIssue))
        .notify(project);
  }

  /**
   * From our own plugin.xml (the platform's descriptor lookups are all marked internal). The class loader also sees
   * Git4Idea's plugin.xml, so pick the one with our id.
   */
  private static String pluginVersion() {
    try {
      Enumeration<URL> urls = Feedback.class.getClassLoader().getResources("META-INF/plugin.xml");
      while (urls.hasMoreElements()) {
        String xml;
        try (InputStream in = urls.nextElement().openStream()) {
          xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!xml.contains("<id>" + PLUGIN_ID + "</id>")) continue;
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        return m.find() ? m.group(1).trim() : "?";
      }
    } catch (IOException ignored) {
      // fall through: the issue form still works without it
    }
    return "?";
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
