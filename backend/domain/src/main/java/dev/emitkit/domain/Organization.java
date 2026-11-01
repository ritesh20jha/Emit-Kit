package dev.emitkit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A VCS organization (or a user's personal organization) that owns projects. Maps to {@code
 * organizations}. The table has no {@code updated_at}, so this entity does not track one.
 */
@Entity
@Table(name = "organizations")
public class Organization extends BaseEntity {

  /** Matches the {@code vcs_provider} column default in V1__identity.sql. */
  private static final String DEFAULT_VCS_PROVIDER = "github";

  @Column(name = "vcs_provider", nullable = false, length = 32)
  private String vcsProvider;

  @Column(name = "vcs_org_id", nullable = false, length = 255)
  private String vcsOrgId;

  @Column(name = "slug", nullable = false, length = 255)
  private String slug;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "is_personal", nullable = false)
  private boolean personal;

  @Column(name = "owner_user_id", length = 26)
  private String ownerUserId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Organization() {}

  /**
   * Creates an organization.
   *
   * @param id the ULID to use, or {@code null} to generate one
   * @param vcsProvider the VCS provider, or {@code null} for {@code "github"} (the column default)
   * @param ownerUserId the owning user's id, or {@code null} when no user owns it
   */
  public Organization(
      String id,
      String vcsProvider,
      String vcsOrgId,
      String slug,
      String name,
      boolean personal,
      String ownerUserId) {
    super(id);
    this.vcsProvider = vcsProvider != null ? vcsProvider : DEFAULT_VCS_PROVIDER;
    this.vcsOrgId = vcsOrgId;
    this.slug = slug;
    this.name = name;
    this.personal = personal;
    this.ownerUserId = ownerUserId;
  }

  public String getVcsProvider() {
    return vcsProvider;
  }

  public String getVcsOrgId() {
    return vcsOrgId;
  }

  public String getSlug() {
    return slug;
  }

  public String getName() {
    return name;
  }

  public boolean isPersonal() {
    return personal;
  }

  public String getOwnerUserId() {
    return ownerUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
