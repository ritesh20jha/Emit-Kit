package dev.emitkit.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.worker.sandbox.DockerSandboxRunner.Extracted;
import dev.emitkit.worker.sandbox.DockerSandboxRunner.OutputLimits;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;

/** Size and name limits on the untrusted output archive. Needs no Docker daemon. */
class SandboxOutputExtractionTest {

  private static final OutputLimits LIMITS = new OutputLimits(16, 16, 16);

  @Test
  void readsExactTopLevelOutputs() throws IOException {
    Extracted out = extract(LIMITS, "out/out.tar", "payload", "out/report.json", "{}");
    try {
      assertThat(Files.readString(out.outputTar())).isEqualTo("payload");
      assertThat(out.reportJson()).isEqualTo("{}");
    } finally {
      Files.deleteIfExists(out.outputTar());
    }
  }

  @Test
  void oversizedReportIsMissingAndOutputTarIsKept() throws IOException {
    Extracted out =
        extract(new OutputLimits(4, 16, 16), "out/report.json", "12345", "out/out.tar", "ok");
    try {
      assertThat(out.reportJson()).isNull();
      assertThat(Files.readString(out.outputTar())).isEqualTo("ok");
    } finally {
      Files.deleteIfExists(out.outputTar());
    }
  }

  @Test
  void oversizedOutputTarIsMissingAndReportIsKept() throws IOException {
    Extracted out =
        extract(new OutputLimits(16, 3, 16), "out/out.tar", "payload", "out/report.json", "{}");

    assertThat(out.outputTar()).isNull();
    assertThat(out.reportJson()).isEqualTo("{}");
  }

  @Test
  void nestedDuplicateAndSymlinkOutputsAreMissing() throws IOException {
    assertThat(extract(LIMITS, "out/nested/out.tar", "x").outputTar()).isNull();
    assertThat(extract(LIMITS, "out/report.json", "a", "out/report.json", "b").reportJson())
        .isNull();

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
      TarArchiveEntry link = new TarArchiveEntry("out/report.json", TarConstants.LF_SYMLINK);
      link.setLinkName("/etc/passwd");
      tar.putArchiveEntry(link);
      tar.closeArchiveEntry();
    }
    Extracted out =
        DockerSandboxRunner.extractOutputs(new ByteArrayInputStream(bytes.toByteArray()), LIMITS);
    assertThat(out.reportJson()).isNull();
  }

  @Test
  void tooManyEntriesDiscardsEveryOutput() throws IOException {
    Extracted out =
        extract(
            new OutputLimits(16, 16, 2),
            "out/out.tar",
            "payload",
            "out/report.json",
            "{}",
            "out/x",
            "x");

    assertThat(out.outputTar()).isNull();
    assertThat(out.reportJson()).isNull();
  }

  /** Builds an archive from alternating name and content arguments, then extracts it. */
  private static Extracted extract(OutputLimits limits, String... nameAndContent)
      throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
      for (int i = 0; i < nameAndContent.length; i += 2) {
        byte[] content = nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(nameAndContent[i]);
        entry.setSize(content.length);
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
      }
    }
    return DockerSandboxRunner.extractOutputs(
        new ByteArrayInputStream(bytes.toByteArray()), limits);
  }
}
