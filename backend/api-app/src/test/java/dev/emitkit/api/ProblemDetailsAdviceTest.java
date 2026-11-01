package dev.emitkit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.emitkit.api.error.ProblemDetailsAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// addFilters = false: this slice checks only the advice mapping, so the security filters are off
// and the requests reach the controller. The real chains are covered by SecurityConfigTest.
@WebMvcTest(TestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ProblemDetailsAdvice.class)
class ProblemDetailsAdviceTest {

  @Autowired private MockMvc mvc;

  @Test
  void appException_mapsToProblemWithCode() throws Exception {
    mvc.perform(get("/test/conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SPEC_CONFLICT"))
        .andExpect(jsonPath("$.type").value("https://emitkit.dev/errors/SPEC_CONFLICT"))
        .andExpect(jsonPath("$.currentSha").value("abc")); // from extra
  }

  @Test
  void validationError_listsFields() throws Exception {
    mvc.perform(
            post("/test/items").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("name"));
  }

  @Test
  void accessDenied_mapsToForbidden() throws Exception {
    mvc.perform(get("/test/denied"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.type").value("https://emitkit.dev/errors/FORBIDDEN"));
  }

  @Test
  void unknownPath_isNotFoundWithCodeAndType() throws Exception {
    mvc.perform(get("/test/does-not-exist"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.type").value("https://emitkit.dev/errors/NOT_FOUND"));
  }

  @Test
  void malformedJson_isValidationFailedWithCodeAndType() throws Exception {
    mvc.perform(post("/test/items").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.type").value("https://emitkit.dev/errors/VALIDATION_FAILED"));
  }

  @Test
  void wrongMethod_keepsStatusAndGetsCodeAndType() throws Exception {
    mvc.perform(delete("/test/conflict"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.type").value("https://emitkit.dev/errors/VALIDATION_FAILED"));
  }

  @Test
  void handlerMethodValidation_listsFields() throws Exception {
    mvc.perform(get("/test/range").param("n", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("n"));
  }

  @Test
  void unexpectedError_hidesMessage() throws Exception {
    String body =
        mvc.perform(get("/test/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("password").doesNotContain("RuntimeException");
  }
}
