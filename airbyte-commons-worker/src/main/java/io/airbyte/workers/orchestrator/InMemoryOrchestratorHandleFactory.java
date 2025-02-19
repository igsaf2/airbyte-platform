/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.orchestrator;

import io.airbyte.commons.functional.CheckedSupplier;
import io.airbyte.config.ReplicationOutput;
import io.airbyte.config.StandardSyncInput;
import io.airbyte.persistence.job.models.IntegrationLauncherConfig;
import io.airbyte.persistence.job.models.JobRunConfig;
import io.airbyte.workers.Worker;
import io.airbyte.workers.general.ReplicationWorkerFactory;
import io.micronaut.context.annotation.Requires;
import io.temporal.activity.ActivityExecutionContext;
import jakarta.inject.Singleton;
import java.util.function.Supplier;

/**
 * Factory for building InMemoryOrchestrator handles.
 * <p>
 * When running in Docker, the ReplicationWorker is hosted within the airbyte-worker. This factory
 * creates the handle to manage an instance of the ReplicationWorker in memory.
 */
@Singleton
@Requires(property = "airbyte.container.orchestrator.enabled",
          notEquals = "true")
public class InMemoryOrchestratorHandleFactory implements OrchestratorHandleFactory {

  private final ReplicationWorkerFactory replicationWorkerFactory;

  public InMemoryOrchestratorHandleFactory(final ReplicationWorkerFactory replicationWorkerFactory) {
    this.replicationWorkerFactory = replicationWorkerFactory;
  }

  @Override
  public CheckedSupplier<Worker<StandardSyncInput, ReplicationOutput>, Exception> create(IntegrationLauncherConfig sourceLauncherConfig,
                                                                                         IntegrationLauncherConfig destinationLauncherConfig,
                                                                                         JobRunConfig jobRunConfig,
                                                                                         StandardSyncInput syncInput,
                                                                                         final Supplier<ActivityExecutionContext> activityContext) {
    return () -> replicationWorkerFactory.create(syncInput, jobRunConfig, sourceLauncherConfig, destinationLauncherConfig);
  }

}
