package nl.netonic.workspaces.ide;

import com.intellij.openapi.util.IconLoader;

import javax.swing.Icon;

/** The plugin's own icons (the tool window's, also on the toolbar widget so it doesn't look like Git's branch widget). */
public final class WsIcons {
  public static final Icon WORKSPACES = IconLoader.getIcon("/icons/workspaces.svg", WsIcons.class);

  private WsIcons() {}
}
