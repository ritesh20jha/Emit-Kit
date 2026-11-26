package dev.emitkit.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

  @Test
  void everyCodeHasAnHttpStatus() {
    for (ErrorCode c : ErrorCode.values()) {
      assertThat(c.httpStatus()).isBetween(400, 599);
    }
  }

  @Test
  void specConflictIs409() {
    assertThat(ErrorCode.SPEC_CONFLICT.httpStatus()).isEqualTo(409);
  }

  @Test
  void transientIsAnAppException() {
    var e = new TransientException(ErrorCode.STORAGE_ERROR, "s3 down");
    assertThat(e).isInstanceOf(AppException.class);
    assertThat(e.code()).isEqualTo(ErrorCode.STORAGE_ERROR);
    assertThat(e.extra()).isEmpty();
  }

  @Test
  void twoArgCtorGivesEmptyExtraAndNoCause() {
    var e = new AppException(ErrorCode.NOT_FOUND, "missing");
    assertThat(e.getMessage()).isEqualTo("missing");
    assertThat(e.extra()).isNotNull().isEmpty();
    assertThat(e.getCause()).isNull();
  }

  @Test
  void extraIsUnmodifiable() {
    var e = new AppException(ErrorCode.VALIDATION_FAILED, "bad", Map.of("field", "name"), null);
    assertThatThrownBy(() -> e.extra().put("other", "x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void extraIsADefensiveCopy() {
    var source = new HashMap<String, Object>();
    source.put("field", "name");
    var e = new AppException(ErrorCode.VALIDATION_FAILED, "bad", source, null);
    source.put("field", "changed");
    assertThat(e.extra()).containsEntry("field", "name");
  }

  @Test
  void nullExtraBecomesEmpty() {
    var e = new AppException(ErrorCode.INTERNAL, "boom", null, null);
    assertThat(e.extra()).isNotNull().isEmpty();
  }

  @Test
  void causeIsPreserved() {
    var cause = new IllegalStateException("root");
    var e = new TransientException(ErrorCode.STORAGE_ERROR, "s3 down", Map.of(), cause);
    assertThat(e.getCause()).isSameAs(cause);
  }
}
