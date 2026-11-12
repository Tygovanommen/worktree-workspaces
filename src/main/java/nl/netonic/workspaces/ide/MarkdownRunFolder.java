package nl.netonic.workspaces.ide;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.registry.RegistryValue;

import java.util.MissingResourceException;

/**
 * The ▶ next to a command in a Markdown file runs it in the topmost content root, which in a ticket is the ticket
 * folder, not the repo the README is in ({@code npm install} lands in {@code KEY/}). The Markdown plugin's registry
 * key {@value #KEY} makes it use the file's folder instead. The registry is IDE-wide, so we only turn it on while
 * the option is on, and only reset it if we were the ones who turned it on.
 *
 * <p>2026.3 dropped the key: its ▶ offers the file's folder itself when that differs from the content root, so
 * there is nothing to set ({@link #isAvailable()} is false and the option is hidden).
 */
public final class MarkdownRunFolder {
  static final String KEY = "markdown.command.runner.use.file.directory";
  private static final Logger LOG = Logger.getInstance(MarkdownRunFolder.class);

  private MarkdownRunFolder() {}

  /** Whether this PhpStorm has the key (Markdown plugin on, before 2026.3). */
  public static boolean isAvailable() {
    try {
      Registry.get(KEY).asBoolean();
      return true;
    } catch (MissingResourceException e) {
      return false;
    }
  }

  /** Brings the registry key in line with the option. */
  public static void apply() {
    WorkspaceSettings.SettingsState s = WorkspaceSettings.getInstance().getState();
    RegistryValue value = Registry.get(KEY);
    boolean on;
    try {
      on = value.asBoolean();
    } catch (MissingResourceException e) {
      return; // Markdown plugin disabled, or 2026.3+ (key gone)
    }
    try {
      if (s.markdownRunInFileFolder && !on) {
        value.setValue(true);
        s.markdownRunFolderSetByUs = true;
      } else if (!s.markdownRunInFileFolder && on && s.markdownRunFolderSetByUs) {
        value.resetToDefault();
        s.markdownRunFolderSetByUs = false;
      }
    } catch (RuntimeException e) {
      LOG.warn("Could not set " + KEY, e);
    }
  }
}
