package dev.emitkit.common.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SecretCipherConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(SecretCipherConfig.class);

  @ParameterizedTest
  @ValueSource(strings = {"prod", "staging"})
  void localCipherUnderProdOrStaging_failsStartup(String profile) {
    runner
        .withPropertyValues("spring.profiles.active=" + profile, "emitkit.vault.cipher=local")
        .run(ctx ->
            assertThat(ctx)
                .hasFailed()
                .getFailure()
                .hasMessageContaining("LocalAesCipher is not allowed"));
  }

  @Test
  void localCipherUnderTestProfile_isUsed() {
    runner
        .withPropertyValues("spring.profiles.active=test", "emitkit.vault.cipher=local")
        .run(ctx -> assertThat(ctx.getBean(SecretCipher.class)).isInstanceOf(LocalAesCipher.class));
  }

  @Test
  void appRoleSecretIdIsReadFromFile(@TempDir Path dir) throws Exception {
    Path file = Files.writeString(dir.resolve("secret-id"), "  s3cr3t-id\n");
    var props =
        new VaultProperties(
            null, null, file.toString(), "emitkit-secrets", VaultProperties.CipherMode.VAULT);

    assertThat(props.secretId()).isEqualTo("s3cr3t-id");
  }

  @Test
  void vaultCipherSelectedByDefault_withoutConnectingAtStartup(@TempDir Path dir) throws Exception {
    Path file = Files.writeString(dir.resolve("secret-id"), "s3cr3t-id\n");

    runner
        .withConfiguration(AutoConfigurations.of(VaultHealthConfig.class))
        .withPropertyValues(
            "emitkit.vault.uri=http://127.0.0.1:1",
            "emitkit.vault.role-id=emitkit-api",
            "emitkit.vault.secret-id-file=" + file)
        .run(ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx.getBean(SecretCipher.class)).isInstanceOf(VaultTransitCipher.class);
          assertThat(ctx.getBean("vault")).isInstanceOf(VaultHealthIndicator.class);
        });
  }
}
