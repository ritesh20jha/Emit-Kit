package dev.emitkit.api.orgs;

import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables {@code @Async} and gives the organization sync its own executor, which runs each task on
 * a virtual thread so a slow GitHub call holds no platform thread. Declaring an {@link
 * java.util.concurrent.Executor} bean makes Boot skip its default {@code applicationTaskExecutor};
 * nothing else in this application uses that default.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class OrgSyncConfig {

  /** The bean name of the executor that {@link OrgSyncListener} runs on. */
  static final String ORG_SYNC_EXECUTOR = "orgSyncExecutor";

  @Bean(name = ORG_SYNC_EXECUTOR)
  TaskExecutor orgSyncExecutor() {
    return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
  }
}
