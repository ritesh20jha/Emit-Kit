package dev.emitkit.gen.core;

/** Whether a generated file is fully owned by the generator or is a user-editable stub. */
public enum FileKind {
  /** Overwritten on every run. */
  GENERATED,
  /** Written once as a starting point; the user owns it afterwards. */
  CUSTOM_STUB
}
