package nl.netonic.workspaces.core;

import java.nio.file.Path;

/** A repo as it appears inside one ticket. */
public record TicketRepo(String name, Path dir, Kind kind) {
  public enum Kind {
    /** The real clone, living in main/. */
    CLONE,
    /** A git worktree inside the ticket folder. */
    WORKTREE,
    /** Not part of this ticket; main/&lt;repo&gt; is attached as an extra root. */
    ATTACHED,
    /** Configured, but neither the ticket nor main has it. */
    MISSING
  }

  public boolean isOwnCheckout() {
    return kind == Kind.CLONE || kind == Kind.WORKTREE;
  }
}
