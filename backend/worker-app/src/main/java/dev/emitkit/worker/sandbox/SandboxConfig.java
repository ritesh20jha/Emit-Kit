package dev.emitkit.worker.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the sandbox Docker client and the runner built on it. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SandboxProperties.class)
class SandboxConfig {

  @Bean
  DockerClient dockerClient(SandboxProperties properties) {
    return newDockerClient(properties.dockerHost());
  }

  @Bean
  SandboxRunner sandboxRunner(DockerClient dockerClient, SandboxProperties properties) {
    return new DockerSandboxRunner(dockerClient, properties);
  }

  /** A docker-java client for {@code dockerHost}; the response timeout is per read. */
  static DockerClient newDockerClient(String dockerHost) {
    var config =
        DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(dockerHost).build();
    var http =
        new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .connectionTimeout(Duration.ofSeconds(10))
            .responseTimeout(Duration.ofSeconds(60))
            .build();
    return DockerClientImpl.getInstance(config, http);
  }
}
