package dev.emitkit.api.orgs;

import dev.emitkit.api.auth.UserLoggedInEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Syncs a user's organizations after each GitHub login. The sync runs on the {@code
 * orgSyncExecutor} bean (virtual threads), so the login redirect never waits for GitHub. A failure
 * is logged and swallowed: a missed sync is retried by the next login or by {@code POST
 * /me/sync-orgs}. This path does not use that endpoint's 60-second throttle.
 */
@Component
public class OrgSyncListener {

  private static final Logger LOG = LoggerFactory.getLogger(OrgSyncListener.class);

  private final OrgSyncService sync;

  public OrgSyncListener(OrgSyncService sync) {
    this.sync = sync;
  }

  @Async(OrgSyncConfig.ORG_SYNC_EXECUTOR)
  @EventListener
  public void onLogin(UserLoggedInEvent event) {
    try {
      sync.sync(event.userId());
    } catch (RuntimeException e) {
      LOG.warn("Organization sync after login failed for user {}", event.userId(), e);
    }
  }
}
