package dev.emitkit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A person who signed in with GitHub. Maps to {@code users}. */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

  @Column(name = "github_id", nullable = false, unique = true)
  private long githubId;

  @Column(name = "login", nullable = false, length = 255)
  private String login;

  /** Public GitHub display name. GitHub may omit it, so it is {@code null} when unset. */
  @Column(name = "name", length = 255)
  private String name;

  /** Email from the GitHub profile. It is {@code null} when the user keeps it private. */
  @Column(name = "email", length = 255)
  private String email;

  @Column(name = "avatar_url", nullable = false, length = 255)
  private String avatarUrl;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected User() {}

  /**
   * Creates a user from the GitHub profile.
   *
   * @param id the ULID to use, or {@code null} to generate one
   */
  public User(String id, long githubId, String login, String name, String email, String avatarUrl) {
    super(id);
    this.githubId = githubId;
    this.login = login;
    this.name = name;
    this.email = email;
    this.avatarUrl = avatarUrl;
  }

  public long getGithubId() {
    return githubId;
  }

  public String getLogin() {
    return login;
  }

  public String getName() {
    return name;
  }

  public String getEmail() {
    return email;
  }

  public String getAvatarUrl() {
    return avatarUrl;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
