package dev.emitkit.domain;

import dev.emitkit.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

/**
 * Common root for entities with a ULID primary key. The id is generated in Java, so a service can
 * read it before the row is written (for example, to bind an encrypted secret to the row).
 */
@MappedSuperclass
public abstract class BaseEntity {

  @Id
  @Column(name = "id", length = 26, nullable = false, updatable = false)
  private String id;

  /** Assigns a new ULID on first persist, unless the id was already set. */
  @PrePersist
  protected void assignIdIfMissing() {
    if (id == null) {
      id = Ids.newUlid();
    }
  }

  /** Returns the ULID, or {@code null} until the entity is first persisted. */
  public String getId() {
    return id;
  }
}
