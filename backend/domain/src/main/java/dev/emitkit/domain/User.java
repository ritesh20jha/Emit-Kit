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

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "email", nullable = false, length = 255)
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

  /** Creates a user from the GitHub profile. The id is assigned on persist. */
  public User(long githubId, String login, String name, String email, String avatarUrl) {
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
