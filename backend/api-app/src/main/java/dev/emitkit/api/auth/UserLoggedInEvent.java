package dev.emitkit.api.auth;

/** Published after a successful GitHub login. AUTH-02 listens for it to sync organizations. */
public record UserLoggedInEvent(String userId) {}
