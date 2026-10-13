package nl.netonic.workspaces.ide;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.Nullable;

/** Balloon notifications in the "Worktree Workspaces" group. */
public final class Notifier {
  public static final String GROUP = "Worktree Workspaces";

  private Notifier() {}

  public static void info(@Nullable Project project, String title, String content) {
    notify(project, title, content, NotificationType.INFORMATION);
  }

  public static void warn(@Nullable Project project, String title, String content) {
    notify(project, title, content, NotificationType.WARNING);
  }

  public static void error(@Nullable Project project, String title, String content) {
    notify(project, title, content, NotificationType.ERROR);
  }

  private static void notify(@Nullable Project project, String title, String content, NotificationType type) {
    String html = StringUtil.escapeXmlEntities(content == null ? "" : content).replace("\n", "<br>");
    NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
        .createNotification(title, html, type)
        .notify(project);
  }
}
