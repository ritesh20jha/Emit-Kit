package fixtures.bad.common;

import fixtures.bad.api.SomeController;

/** Deliberate violation: common depends on api. Used by the negative ArchUnit test. */
class BadCommon {
  @SuppressWarnings("unused")
  private SomeController controller;
}
