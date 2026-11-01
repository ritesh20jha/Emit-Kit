package dev.emitkit.api;

import static org.assertj.core.api.Assertions.assertThat;
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

// addFilters = false: the real SecurityConfig does not exist yet, and the default Boot security
// chain would answer 401 before the controller runs. Task 2 replaces this with the real chains.
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
