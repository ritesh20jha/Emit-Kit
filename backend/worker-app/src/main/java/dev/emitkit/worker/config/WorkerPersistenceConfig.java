package dev.emitkit.worker.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Registers the entities and repositories of {@code :domain} for the worker's JPA context. */
@Configuration(proxyBeanMethods = false)
@EntityScan("dev.emitkit.domain")
@EnableJpaRepositories("dev.emitkit.domain")
class WorkerPersistenceConfig {}
