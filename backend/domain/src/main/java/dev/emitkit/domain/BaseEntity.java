package dev.emitkit.domain;

import dev.emitkit.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Common root for entities with a ULID primary key.
 *
 * <p>The id is set at construction: a supplied id is kept, and {@code null} generates a new ULID. A
 * service can therefore read the id, and bind an encrypted secret to it, before the row is written.
 * {@link #isNew()} stays {@code true} until the entity is persisted or loaded, so {@code save()}
 * calls {@code persist()} on the same instance rather than {@code merge()}.
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<String> {

  @Id
  @Column(name = "id", length = 26, nullable = false, updatable = false)
  private String id;

  @Transient private boolean isNew = true;

  /** JPA constructor. Hibernate sets the id when it loads a row. */
  protected BaseEntity() {}

  /** Keeps {@code id}, or generates a new ULID when it is {@code null}. */
  protected BaseEntity(String id) {
    this.id = id != null ? id : Ids.newUlid();
  }

  /** Fills the id only when it is still missing, which happens only via the JPA constructor. */
  @PrePersist
  protected void assignIdIfMissing() {
    if (id == null) {
      id = Ids.newUlid();
    }
  }

  @PostPersist
  @PostLoad
  protected void markNotNew() {
    isNew = false;
  }

  /**
   * Returns the ULID. It is never {@code null} for an entity built through a public constructor.
   */
  @Override
  public String getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }
}
