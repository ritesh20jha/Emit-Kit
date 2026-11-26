package dev.emitkit.spec.diff;

/**
 * Kinds of change the differ can report. Operation-level add/remove is carried by {@link SpecDiff}.
 */
public enum ChangeKind {
  PARAM_ADDED,
  PARAM_REMOVED,
  PARAM_BECAME_REQUIRED,
  PARAM_BECAME_OPTIONAL,
  BODY_BECAME_REQUIRED,
  REQUEST_REQUIRED_PROPERTY_ADDED,
  REQUEST_PROPERTY_ADDED,
  RESPONSE_PROPERTY_REMOVED,
  RESPONSE_PROPERTY_ADDED,
  ENUM_VALUE_REMOVED,
  ENUM_VALUE_ADDED,
  TYPE_CHANGED,
  DESCRIPTION_CHANGED
}
