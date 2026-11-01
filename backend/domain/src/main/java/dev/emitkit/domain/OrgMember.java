package dev.emitkit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * A user's membership in an organization, with its role. Maps to {@code org_members}, which has no
 * timestamps. The composite key is {@code (orgId, userId)}.
 */
@Entity
@Table(name = "org_members")
@IdClass(OrgMemberId.class)
public class OrgMember {

  @Id
  @Column(name = "org_id", nullable = false, length = 26)
  private String orgId;

  @Id
  @Column(name = "user_id", nullable = false, length = 26)
  private String userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 16)
  private Role role;

  @Enumerated(EnumType.STRING)
  @Column(name = "role_source", nullable = false, length = 16)
  private RoleSource roleSource;

  protected OrgMember() {}

  public OrgMember(String orgId, String userId, Role role, RoleSource roleSource) {
    this.orgId = orgId;
    this.userId = userId;
    this.role = role;
    this.roleSource = roleSource;
  }

  public String getOrgId() {
    return orgId;
  }

  public String getUserId() {
    return userId;
  }

  public Role getRole() {
    return role;
  }

  public RoleSource getRoleSource() {
    return roleSource;
  }
}
