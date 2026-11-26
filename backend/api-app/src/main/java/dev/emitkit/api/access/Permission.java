package dev.emitkit.api.access;

/** An action an org member may be allowed to perform on an org or project resource. */
public enum Permission {
  ORG_VIEW,
  PROJECT_VIEW,
  RUN_VIEW,
  SPEC_VIEW,
  PROJECT_CREATE,
  PROJECT_DELETE,
  CONFIG_EDIT,
  SPEC_EDIT,
  RUN_TRIGGER,
  RUN_CANCEL,
  RUN_RERUN,
  RUN_APPROVE,
  MEMBER_ROLE_EDIT
}
