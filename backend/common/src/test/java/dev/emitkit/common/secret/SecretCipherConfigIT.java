package dev.emitkit.common.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

@Testcontainers
class SecretCipherConfigIT {

  private static final String ROLE = "auth/approle/role/emitkit-api";
  private static final String POLICY =
      """
      path "transit/encrypt/emitkit-secrets" { capabilities = ["update"] }
      path "transit/decrypt/emitkit-secrets" { capabilities = ["update"] }
      """;

  @Container
  static VaultContainer<?> vault =
      new VaultContainer<>("hashicorp/vault:2.2.1")
          .withVaultToken("root")
          .withInitCommand(
              "secrets enable transit",
              "write -f transit/keys/emitkit-secrets type=aes256-gcm96 derived=true");

  @Test
  void appRoleLogin_roundTripsAndReportsHealthUp(@TempDir Path dir) throws Exception {
    var root =
        new VaultTemplate(
            VaultEndpoint.from(vault.getHttpHostAddress()), new TokenAuthentication("root"));
    root.write("sys/policies/acl/emitkit-api", Map.of("policy", POLICY));
    root.write("sys/auth/approle", Map.of("type", "approle"));
    root.write(ROLE, Map.of("token_policies", "emitkit-api"));
    String roleId = root.read(ROLE + "/role-id").getData().get("role_id").toString();
    String secretId =
        root.write(ROLE + "/secret-id", Map.of()).getData().get("secret_id").toString();
    Path file = Files.writeString(dir.resolve("secret-id"), secretId + "\n");

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SecretCipherConfig.class, VaultHealthConfig.class))
        .withPropertyValues(
            "emitkit.vault.uri=" + vault.getHttpHostAddress(),
            "emitkit.vault.role-id=" + roleId,
            "emitkit.vault.secret-id-file=" + file)
        .run(ctx -> {
          assertThat(ctx).hasNotFailed();
          SecretCipher cipher = ctx.getBean(SecretCipher.class);
          var row = new SecretContext("projects", "webhook_secret_enc", "row-1");
          assertThat(cipher.decrypt(cipher.encrypt("s3cret", row), row)).isEqualTo("s3cret");
          assertThat(ctx.getBean(VaultHealthIndicator.class).health().getStatus())
              .isEqualTo(Status.UP);
        });
  }
}
