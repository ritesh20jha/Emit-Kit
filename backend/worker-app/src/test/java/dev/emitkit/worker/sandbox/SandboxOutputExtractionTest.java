package dev.emitkit.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;

/** Bounds and entry matching for the untrusted output archive. Needs no Docker daemon. */
class SandboxOutputExtractionTest {

  private static final DockerSandboxRunner.OutputLimits LIMITS =
      new DockerSandboxRunner.OutputLimits(16, 16, 16);

  @Test
  void readsExactTopLevelOutputs() throws IOException {
    DockerSandboxRunner.Extracted out =
        extract(LIMITS, dir("out/"), file("out/out.tar", "payload"), file("out/report.json", "{}"));
    try {
      assertThat(out.reportJson()).isEqualTo("{}");
      assertThat(Files.readString(out.outputTar())).isEqualTo("payload");
    } finally {
      Files.deleteIfExists(out.outputTar());
    }
  }

  @Test
  void ignoresNestedNonFileAndUnknownEntries() throws IOException {
    DockerSandboxRunner.Extracted out =
        extract(
            LIMITS,
            file("out/nested/out.tar", "x"),
            file("out/extra.txt", "x"),
            symlink("out/report.json"));

    assertThat(out.outputTar()).isNull();
    assertThat(out.reportJson()).isNull();
  }

  @Test
  void oversizedReportIsTreatedAsMissing() throws IOException {
    DockerSandboxRunner.Extracted out =
        extract(
            new DockerSandboxRunner.OutputLimits(4, 16, 16),
            file("out/report.json", "12345"),
            file("out/out.tar", "ok"));
    try {
      assertThat(out.reportJson()).isNull();
      assertThat(Files.readString(out.outputTar())).isEqualTo("ok");
    } finally {
      Files.deleteIfExists(out.outputTar());
    }
  }

  @Test
  void oversizedOutputTarIsTreatedAsMissing() throws IOException {
    DockerSandboxRunner.Extracted out =
        extract(
            new DockerSandboxRunner.OutputLimits(16, 3, 16),
            file("out/out.tar", "payload"),
            file("out/report.json", "{}"));

    assertThat(out.outputTar()).isNull();
    assertThat(out.reportJson()).isEqualTo("{}");
  }

  @Test
  void duplicateEntryTreatsThatOutputAsMissing() throws IOException {
    DockerSandboxRunner.Extracted out =
        extract(LIMITS, file("out/report.json", "first"), file("out/report.json", "second"));

    assertThat(out.reportJson()).isNull();
  }

  @Test
  void duplicateOutputTarIsDeletedAndTreatedAsMissing() throws IOException {
    DockerSandboxRunner.Extracted out =
        extract(LIMITS, file("out/out.tar", "first"), file("out/out.tar", "second"));

    assertThat(out.outputTar()).isNull();
  }

  @Test
  void tooManyEntriesDiscardsEveryOutput() throws IOException {
    DockerSandboxRunner.Extracted out =
        extract(
            new DockerSandboxRunner.OutputLimits(16, 16, 2),
            file("out/out.tar", "payload"),
            file("out/report.json", "{}"),
            file("out/extra.txt", "x"));

    assertThat(out.outputTar()).isNull();
    assertThat(out.reportJson()).isNull();
  }

  private static DockerSandboxRunner.Extracted extract(
      DockerSandboxRunner.OutputLimits limits, Entry... entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
      for (Entry entry : entries) {
        entry.writeTo(tar);
      }
    }
    return DockerSandboxRunner.extractOutputs(
        new ByteArrayInputStream(bytes.toByteArray()), limits);
  }

  private interface Entry {
    void writeTo(TarArchiveOutputStream tar) throws IOException;
  }

  private static Entry file(String name, String content) {
    return tar -> {
      byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry entry = new TarArchiveEntry(name);
      entry.setSize(bytes.length);
      tar.putArchiveEntry(entry);
      tar.write(bytes);
      tar.closeArchiveEntry();
    };
  }

  private static Entry dir(String name) {
    return tar -> {
      tar.putArchiveEntry(new TarArchiveEntry(name));
      tar.closeArchiveEntry();
    };
  }

  private static Entry symlink(String name) {
    return tar -> {
      TarArchiveEntry entry = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
      entry.setLinkName("/etc/passwd");
      tar.putArchiveEntry(entry);
      tar.closeArchiveEntry();
    };
  }
}
