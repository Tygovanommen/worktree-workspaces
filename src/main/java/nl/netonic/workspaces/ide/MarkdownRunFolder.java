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
 */
public final class MarkdownRunFolder {
  static final String KEY = "markdown.command.runner.use.file.directory";
  private static final Logger LOG = Logger.getInstance(MarkdownRunFolder.class);

  private MarkdownRunFolder() {}

  /** Brings the registry key in line with the option. */
  public static void apply() {
    WorkspaceSettings.SettingsState s = WorkspaceSettings.getInstance().getState();
    RegistryValue value = Registry.get(KEY);
    boolean on;
    try {
      on = value.asBoolean();
    } catch (MissingResourceException e) {
      return; // Markdown plugin disabled
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
