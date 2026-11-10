package dev.emitkit.worker.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wires the sandbox: the Docker client for the sandbox daemon and the runner built on it. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SandboxProperties.class)
@EnableScheduling
class SandboxConfig {

  @Bean
  DockerClient dockerClient(SandboxProperties properties) {
    return newDockerClient(properties.dockerHost());
  }

  @Bean
  SandboxRunner sandboxRunner(DockerClient dockerClient, SandboxProperties properties) {
    return new DockerSandboxRunner(dockerClient, properties);
  }

  /** Builds a docker-java client for {@code dockerHost} over the Apache HTTP 5 transport. */
  static DockerClient newDockerClient(String dockerHost) {
    DefaultDockerClientConfig config =
        DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(dockerHost).build();
    ApacheDockerHttpClient httpClient =
        new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .build();
    return DockerClientImpl.getInstance(config, httpClient);
  }
}
