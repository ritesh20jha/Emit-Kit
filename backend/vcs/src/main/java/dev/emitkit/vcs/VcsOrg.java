package dev.emitkit.vcs;

/** An organization, or the personal account, that the identity belongs to. */
public record VcsOrg(String id, String login, String name, boolean isPersonal, VcsRole role) {

  /** The identity's role in the organization. */
  public enum VcsRole {
    OWNER,
    MEMBER
  }
}
