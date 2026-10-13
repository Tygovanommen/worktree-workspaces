package nl.netonic.workspaces.core;

/** A git command (or a step around it) failed; the message is shown to the user as-is. */
public class GitException extends Exception {
  private static final long serialVersionUID = 1L;

  public GitException(String message) {
    super(message);
  }
}
