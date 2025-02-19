/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.general;

import static io.airbyte.metrics.lib.ApmTraceConstants.WORKER_OPERATION_NAME;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import datadog.trace.api.Trace;
import io.airbyte.commons.converters.ConnectorConfigUpdater;
import io.airbyte.commons.converters.ThreadedTimeTracker;
import io.airbyte.commons.io.LineGobbler;
import io.airbyte.config.FailureReason;
import io.airbyte.config.ReplicationAttemptSummary;
import io.airbyte.config.ReplicationOutput;
import io.airbyte.config.StandardSyncInput;
import io.airbyte.config.StandardSyncSummary.ReplicationStatus;
import io.airbyte.config.State;
import io.airbyte.config.StreamSyncStats;
import io.airbyte.config.SyncStats;
import io.airbyte.config.WorkerDestinationConfig;
import io.airbyte.config.WorkerSourceConfig;
import io.airbyte.metrics.lib.ApmTraceUtils;
import io.airbyte.protocol.models.AirbyteControlMessage;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.AirbyteMessage.Type;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import io.airbyte.protocol.models.AirbyteStreamNameNamespacePair;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.workers.RecordSchemaValidator;
import io.airbyte.workers.WorkerMetricReporter;
import io.airbyte.workers.WorkerUtils;
import io.airbyte.workers.exception.WorkerException;
import io.airbyte.workers.helper.FailureHelper;
import io.airbyte.workers.internal.AirbyteDestination;
import io.airbyte.workers.internal.AirbyteMapper;
import io.airbyte.workers.internal.AirbyteSource;
import io.airbyte.workers.internal.HeartbeatTimeoutChaperone;
import io.airbyte.workers.internal.book_keeping.MessageTracker;
import io.airbyte.workers.internal.book_keeping.SyncStatsBuilder;
import io.airbyte.workers.internal.exception.DestinationException;
import io.airbyte.workers.internal.exception.SourceException;
import io.airbyte.workers.internal.sync_persistence.SyncPersistence;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * This worker is the "data shovel" of ETL. It is responsible for moving data from the Source
 * container to the Destination container. It manages the full lifecycle of this process. This
 * includes:
 * <ul>
 * <li>Starting the Source and Destination containers</li>
 * <li>Passing data from Source to Destination</li>
 * <li>Executing any configured map-only operations (Mappers) in between the Source and
 * Destination</li>
 * <li>Collecting metadata about the data that is passing from Source to Destination</li>
 * <li>Listening for state messages emitted from the Destination to keep track of what data has been
 * replicated.</li>
 * <li>Handling shutdown of the Source and Destination</li>
 * <li>Handling failure cases and returning state for partially completed replications (so that the
 * next replication can pick up where it left off instead of starting from the beginning)</li>
 * </ul>
 */
@SuppressWarnings("PMD.AvoidPrintStackTrace")
public class DefaultReplicationWorker implements ReplicationWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultReplicationWorker.class);

  private final String jobId;
  private final int attempt;
  private final AirbyteSource source;
  private final AirbyteMapper mapper;
  private final AirbyteDestination destination;
  private final MessageTracker messageTracker;
  private final SyncPersistence syncPersistence;

  private final ExecutorService executors;
  private final AtomicBoolean cancelled;
  private final AtomicBoolean hasFailed;
  private final RecordSchemaValidator recordSchemaValidator;
  private final WorkerMetricReporter metricReporter;
  private final ConnectorConfigUpdater connectorConfigUpdater;
  private final boolean fieldSelectionEnabled;
  private final HeartbeatTimeoutChaperone srcHeartbeatTimeoutChaperone;
  private final boolean removeValidationLimit;

  public DefaultReplicationWorker(final String jobId,
                                  final int attempt,
                                  final AirbyteSource source,
                                  final AirbyteMapper mapper,
                                  final AirbyteDestination destination,
                                  final MessageTracker messageTracker,
                                  final SyncPersistence syncPersistence,
                                  final RecordSchemaValidator recordSchemaValidator,
                                  final WorkerMetricReporter metricReporter,
                                  final ConnectorConfigUpdater connectorConfigUpdater,
                                  final boolean fieldSelectionEnabled,
                                  final HeartbeatTimeoutChaperone srcHeartbeatTimeoutChaperone,
                                  final boolean removeValidationLimit) {
    this.jobId = jobId;
    this.attempt = attempt;
    this.source = source;
    this.mapper = mapper;
    this.destination = destination;
    this.messageTracker = messageTracker;
    this.syncPersistence = syncPersistence;
    this.executors = Executors.newFixedThreadPool(2);
    this.recordSchemaValidator = recordSchemaValidator;
    this.metricReporter = metricReporter;
    this.connectorConfigUpdater = connectorConfigUpdater;
    this.fieldSelectionEnabled = fieldSelectionEnabled;
    this.srcHeartbeatTimeoutChaperone = srcHeartbeatTimeoutChaperone;
    this.removeValidationLimit = removeValidationLimit;

    this.cancelled = new AtomicBoolean(false);
    this.hasFailed = new AtomicBoolean(false);
  }

  /**
   * Run executes two threads. The first pipes data from STDOUT of the source to STDIN of the
   * destination. The second listen on STDOUT of the destination. The goal of this second thread is to
   * detect when the destination emits state messages. Only state messages emitted by the destination
   * should be treated as state that is safe to return from run. In the case when the destination
   * emits no state, we fall back on whatever state is pass in as an argument to this method.
   *
   * @param syncInput all configuration for running replication
   * @param jobRoot file root that worker is allowed to use
   * @return output of the replication attempt (including state)
   * @throws WorkerException exception from worker
   */
  @Trace(operationName = WORKER_OPERATION_NAME)
  @Override
  public final ReplicationOutput run(final StandardSyncInput syncInput, final Path jobRoot) throws WorkerException {
    LOGGER.info("start sync worker. job id: {} attempt id: {}", jobId, attempt);
    LOGGER
        .info("Committing states from " + (shouldCommitStateAsap(syncInput) ? "replication" : "persistState")
            + " activity");
    if (shouldCommitStatsAsap(syncInput)) {
      LOGGER.info("Committing stats from replication activity");
    }
    LineGobbler.startSection("REPLICATION");

    // todo (cgardens) - this should not be happening in the worker. this is configuration information
    // that is independent of workflow executions.
    final WorkerDestinationConfig destinationConfig = WorkerUtils.syncToWorkerDestinationConfig(syncInput);
    destinationConfig.setCatalog(mapper.mapCatalog(destinationConfig.getCatalog()));

    final ThreadedTimeTracker timeTracker = new ThreadedTimeTracker();
    timeTracker.trackReplicationStartTime();

    final AtomicReference<FailureReason> replicationRunnableFailureRef = new AtomicReference<>();
    final AtomicReference<FailureReason> destinationRunnableFailureRef = new AtomicReference<>();

    try {
      LOGGER.info("configured sync modes: {}", syncInput.getCatalog().getStreams()
          .stream()
          .collect(Collectors.toMap(s -> s.getStream().getNamespace() + "." + s.getStream().getName(),
              s -> String.format("%s - %s", s.getSyncMode(), s.getDestinationSyncMode()))));
      LOGGER.debug("field selection enabled: {}", fieldSelectionEnabled);
      final WorkerSourceConfig sourceConfig = WorkerUtils.syncToWorkerSourceConfig(syncInput);

      ApmTraceUtils.addTagsToTrace(destinationConfig.getConnectionId(), jobId, jobRoot);
      replicate(jobRoot, destinationConfig, timeTracker, replicationRunnableFailureRef, destinationRunnableFailureRef, sourceConfig,
          syncInput.getConnectionId(), shouldCommitStateAsap(syncInput));
      timeTracker.trackReplicationEndTime();

      return getReplicationOutput(syncInput, destinationConfig, replicationRunnableFailureRef, destinationRunnableFailureRef, timeTracker);
    } catch (final Exception e) {
      ApmTraceUtils.addExceptionToTrace(e);
      throw new WorkerException("Sync failed", e);
    }

  }

  private void replicate(final Path jobRoot,
                         final WorkerDestinationConfig destinationConfig,
                         final ThreadedTimeTracker timeTracker,
                         final AtomicReference<FailureReason> replicationRunnableFailureRef,
                         final AtomicReference<FailureReason> destinationRunnableFailureRef,
                         final WorkerSourceConfig sourceConfig,
                         final UUID connectionId,
                         final boolean commitStatesAsap) {
    final Map<String, String> mdc = MDC.getCopyOfContextMap();

    // note: resources are closed in the opposite order in which they are declared. thus source will be
    // closed first (which is what we want).
    try (syncPersistence; srcHeartbeatTimeoutChaperone; destination; source) {
      destination.start(destinationConfig, jobRoot);
      timeTracker.trackSourceReadStartTime();
      source.start(sourceConfig, jobRoot);
      timeTracker.trackDestinationWriteStartTime();

      // note: `whenComplete` is used instead of `exceptionally` so that the original exception is still
      // thrown
      final CompletableFuture<?> readFromDstThread = CompletableFuture.runAsync(
          readFromDstRunnable(destination, cancelled, messageTracker, syncPersistence, connectorConfigUpdater, mdc, timeTracker,
              destinationConfig.getDestinationId(), connectionId, commitStatesAsap),
          executors)
          .whenComplete((msg, ex) -> {
            if (ex != null) {
              ApmTraceUtils.addExceptionToTrace(ex);
              if (ex.getCause() instanceof DestinationException) {
                destinationRunnableFailureRef.set(FailureHelper.destinationFailure(ex, Long.valueOf(jobId), attempt));
              } else {
                destinationRunnableFailureRef.set(FailureHelper.replicationFailure(ex, Long.valueOf(jobId), attempt));
              }
            }
          });

      final CompletableFuture<Void> readSrcAndWriteDstThread = CompletableFuture.runAsync(readFromSrcAndWriteToDstRunnable(
          source,
          destination,
          sourceConfig.getCatalog(),
          cancelled,
          mapper,
          messageTracker,
          connectorConfigUpdater,
          mdc,
          recordSchemaValidator,
          metricReporter,
          timeTracker,
          sourceConfig.getSourceId(),
          fieldSelectionEnabled,
          removeValidationLimit), executors)
          .whenComplete((msg, ex) -> {
            if (ex != null) {
              ApmTraceUtils.addExceptionToTrace(ex);
              replicationRunnableFailureRef.set(getFailureReason(ex.getCause(), Long.parseLong(jobId), attempt));
            }
          });

      try {
        srcHeartbeatTimeoutChaperone.runWithHeartbeatThread(readSrcAndWriteDstThread);
      } catch (final HeartbeatTimeoutChaperone.HeartbeatTimeoutException ex) {
        ApmTraceUtils.addExceptionToTrace(ex);
        replicationRunnableFailureRef.set(getFailureReason(ex, Long.parseLong(jobId), attempt));
      }

      LOGGER.info("Waiting for source and destination threads to complete.");
      // CompletableFuture#allOf waits until all futures finish before returning, even if one throws an
      // exception. So in order to handle exceptions from a future immediately without needing to wait for
      // the other future to finish, we first call CompletableFuture#anyOf.
      CompletableFuture.anyOf(readSrcAndWriteDstThread, readFromDstThread).get();
      LOGGER.info("One of source or destination thread complete. Waiting on the other.");
      CompletableFuture.allOf(readSrcAndWriteDstThread, readFromDstThread).get();
      LOGGER.info("Source and destination threads complete.");

    } catch (final Exception e) {
      hasFailed.set(true);
      ApmTraceUtils.addExceptionToTrace(e);
      LOGGER.error("Sync worker failed.", e);
    } finally {
      executors.shutdownNow();
    }
  }

  @VisibleForTesting
  static FailureReason getFailureReason(final Throwable ex, final long jobId, final int attempt) {
    if (ex instanceof SourceException) {
      return FailureHelper.sourceFailure(ex, Long.valueOf(jobId), attempt);
    } else if (ex instanceof DestinationException) {
      return FailureHelper.destinationFailure(ex, Long.valueOf(jobId), attempt);
    } else if (ex instanceof HeartbeatTimeoutChaperone.HeartbeatTimeoutException) {
      return FailureHelper.sourceHeartbeatFailure(ex, Long.valueOf(jobId), attempt);
    } else {
      return FailureHelper.replicationFailure(ex, Long.valueOf(jobId), attempt);
    }
  }

  @SuppressWarnings("PMD.AvoidInstanceofChecksInCatchClause")
  private static Runnable readFromDstRunnable(final AirbyteDestination destination,
                                              final AtomicBoolean cancelled,
                                              final MessageTracker messageTracker,
                                              final SyncPersistence syncPersistence,
                                              final ConnectorConfigUpdater connectorConfigUpdater,
                                              final Map<String, String> mdc,
                                              final ThreadedTimeTracker timeHolder,
                                              final UUID destinationId,
                                              final UUID connectionId,
                                              final boolean commitStatesAsap) {
    return () -> {
      MDC.setContextMap(mdc);
      LOGGER.info("Destination output thread started.");
      try {
        while (!cancelled.get() && !destination.isFinished()) {
          final Optional<AirbyteMessage> messageOptional;
          try {
            messageOptional = destination.attemptRead();
          } catch (final Exception e) {
            throw new DestinationException("Destination process read attempt failed", e);
          }
          if (messageOptional.isPresent()) {
            final AirbyteMessage message = messageOptional.get();
            LOGGER.info("State in DefaultReplicationWorker from destination: {}", message);

            messageTracker.acceptFromDestination(message);
            if (commitStatesAsap && message.getType() == Type.STATE) {
              syncPersistence.persist(connectionId, message.getState());
            }

            try {
              if (message.getType() == Type.CONTROL) {
                acceptDstControlMessage(destinationId, message.getControl(), connectorConfigUpdater);
              }
            } catch (final Exception e) {
              LOGGER.error("Error updating destination configuration", e);
            }
          }
        }
        timeHolder.trackDestinationWriteEndTime();
        if (!cancelled.get() && destination.getExitValue() != 0) {
          throw new DestinationException("Destination process exited with non-zero exit code " + destination.getExitValue());
        }
      } catch (final Exception e) {
        if (!cancelled.get()) {
          // Although this thread is closed first, it races with the destination's closure and can attempt one
          // final read after the destination is closed before it's terminated.
          // This read will fail and throw an exception. Because of this, throw exceptions only if the worker
          // was not cancelled.

          if (e instanceof DestinationException) {
            // Surface Destination exceptions directly so that they can be classified properly by the worker
            throw e;
          } else {
            throw new RuntimeException(e);
          }
        }
      }
    };
  }

  @SuppressWarnings("PMD.AvoidInstanceofChecksInCatchClause")
  private static Runnable readFromSrcAndWriteToDstRunnable(final AirbyteSource source,
                                                           final AirbyteDestination destination,
                                                           final ConfiguredAirbyteCatalog catalog,
                                                           final AtomicBoolean cancelled,
                                                           final AirbyteMapper mapper,
                                                           final MessageTracker messageTracker,
                                                           final ConnectorConfigUpdater connectorConfigUpdater,
                                                           final Map<String, String> mdc,
                                                           final RecordSchemaValidator recordSchemaValidator,
                                                           final WorkerMetricReporter metricReporter,
                                                           final ThreadedTimeTracker timeHolder,
                                                           final UUID sourceId,
                                                           final boolean fieldSelectionEnabled,
                                                           final boolean removeValidationLimit) {
    return () -> {
      MDC.setContextMap(mdc);
      LOGGER.info("Replication thread started.");
      long recordsRead = 0L;
      /*
       * validationErrors must be a ConcurrentHashMap as they are updated and read in different threads
       * concurrently for performance.
       */
      final ConcurrentHashMap<AirbyteStreamNameNamespacePair, ImmutablePair<Set<String>, Integer>> validationErrors = new ConcurrentHashMap<>();
      final ConcurrentHashMap<AirbyteStreamNameNamespacePair, Set<String>> uncountedValidationErrors = new ConcurrentHashMap<>();
      final Map<AirbyteStreamNameNamespacePair, List<String>> streamToSelectedFields = new HashMap<>();
      final Map<AirbyteStreamNameNamespacePair, Set<String>> streamToAllFields = new HashMap<>();
      final Map<AirbyteStreamNameNamespacePair, Set<String>> unexpectedFields = new HashMap<>();
      if (fieldSelectionEnabled) {
        populatedStreamToSelectedFields(catalog, streamToSelectedFields);
      }
      populateStreamToAllFields(catalog, streamToAllFields);
      try {
        while (!cancelled.get() && !source.isFinished()) {
          final Optional<AirbyteMessage> messageOptional;
          try {
            messageOptional = source.attemptRead();
          } catch (final Exception e) {
            throw new SourceException("Source process read attempt failed", e);
          }

          if (messageOptional.isPresent()) {
            final AirbyteMessage airbyteMessage = messageOptional.get();
            if (fieldSelectionEnabled) {
              filterSelectedFields(streamToSelectedFields, airbyteMessage);
            }
            if (removeValidationLimit) {
              validateSchemaUncounted(recordSchemaValidator, streamToAllFields, unexpectedFields, uncountedValidationErrors, airbyteMessage);
            } else {
              validateSchema(recordSchemaValidator, streamToAllFields, unexpectedFields, validationErrors, airbyteMessage);
            }

            final AirbyteMessage message = mapper.mapMessage(airbyteMessage);

            messageTracker.acceptFromSource(message);

            try {
              if (message.getType() == Type.CONTROL) {
                acceptSrcControlMessage(sourceId, message.getControl(), connectorConfigUpdater);
              }
            } catch (final Exception e) {
              LOGGER.error("Error updating source configuration", e);
            }

            try {
              if (message.getType() == Type.RECORD || message.getType() == Type.STATE) {
                destination.accept(message);
              }
            } catch (final Exception e) {
              throw new DestinationException("Destination process message delivery failed", e);
            }

            recordsRead += 1;

            if (recordsRead % 5000 == 0) {
              LOGGER.info("Records read: {} ({})", recordsRead,
                  FileUtils.byteCountToDisplaySize(messageTracker.getSyncStatsTracker().getTotalBytesEmitted()));
            }
          } else {
            LOGGER.info("Source has no more messages, closing connection.");
            try {
              source.close();
            } catch (final Exception e) {
              throw new SourceException("Source didn't exit properly - check the logs!", e);
            }
          }
        }
        timeHolder.trackSourceReadEndTime();
        LOGGER.info("Total records read: {} ({})", recordsRead,
            FileUtils.byteCountToDisplaySize(messageTracker.getSyncStatsTracker().getTotalBytesEmitted()));

        try {
          recordSchemaValidator.close();
        } catch (IOException e) {
          LOGGER.warn("Encountered an exception trying to shut down record schema validator thread. Exception: {}", e.getMessage());
        }

        if (removeValidationLimit) {
          LOGGER.info("Schema validation was performed without limit.");
          uncountedValidationErrors.forEach((stream, errors) -> {
            LOGGER.warn("Schema validation errors found for stream {}. Error messages: {}", stream, errors);
            metricReporter.trackSchemaValidationErrors(stream, errors);
          });
        } else {
          LOGGER.info("Schema validation was performed to a max of 10 records with errors per stream.");
          validationErrors.forEach((stream, errorPair) -> {
            LOGGER.warn("Schema validation errors found for stream {}. Error messages: {}", stream, errorPair.getLeft());
            metricReporter.trackSchemaValidationErrors(stream, errorPair.getLeft());
          });
        }
        unexpectedFields.forEach((stream, unexpectedFieldNames) -> {
          if (!unexpectedFieldNames.isEmpty()) {
            LOGGER.warn("Source {} has unexpected fields [{}] in stream {}", sourceId, String.join(", ", unexpectedFieldNames), stream);
            metricReporter.trackUnexpectedFields(stream, unexpectedFieldNames);
          }
        });

        try {
          destination.notifyEndOfInput();
        } catch (final Exception e) {
          throw new DestinationException("Destination process end of stream notification failed", e);
        }
        if (!cancelled.get() && source.getExitValue() != 0) {
          throw new SourceException("Source process exited with non-zero exit code " + source.getExitValue());
        }
      } catch (final Exception e) {
        if (!cancelled.get()) {
          // Although this thread is closed first, it races with the source's closure and can attempt one
          // final read after the source is closed before it's terminated.
          // This read will fail and throw an exception. Because of this, throw exceptions only if the worker
          // was not cancelled.

          if (e instanceof SourceException || e instanceof DestinationException) {
            // Surface Source and Destination exceptions directly so that they can be classified properly by the
            // worker
            throw e;
          } else {
            throw new RuntimeException(e);
          }
        }
      }
    };
  }

  private static void acceptSrcControlMessage(final UUID sourceId,
                                              final AirbyteControlMessage controlMessage,
                                              final ConnectorConfigUpdater connectorConfigUpdater) {
    if (controlMessage.getType() == AirbyteControlMessage.Type.CONNECTOR_CONFIG) {
      connectorConfigUpdater.updateSource(sourceId, controlMessage.getConnectorConfig().getConfig());
    }
  }

  private static void acceptDstControlMessage(final UUID destinationId,
                                              final AirbyteControlMessage controlMessage,
                                              final ConnectorConfigUpdater connectorConfigUpdater) {
    if (controlMessage.getType() == AirbyteControlMessage.Type.CONNECTOR_CONFIG) {
      connectorConfigUpdater.updateDestination(destinationId, controlMessage.getConnectorConfig().getConfig());
    }
  }

  private ReplicationOutput getReplicationOutput(final StandardSyncInput syncInput,
                                                 final WorkerDestinationConfig destinationConfig,
                                                 final AtomicReference<FailureReason> replicationRunnableFailureRef,
                                                 final AtomicReference<FailureReason> destinationRunnableFailureRef,
                                                 final ThreadedTimeTracker timeTracker)
      throws JsonProcessingException {
    final ReplicationStatus outputStatus;
    // First check if the process was cancelled. Cancellation takes precedence over failures.
    if (cancelled.get()) {
      outputStatus = ReplicationStatus.CANCELLED;
      // if the process was not cancelled but still failed, then it's an actual failure
    } else if (hasFailed.get()) {
      outputStatus = ReplicationStatus.FAILED;
    } else {
      outputStatus = ReplicationStatus.COMPLETED;
    }

    final boolean hasReplicationCompleted = outputStatus == ReplicationStatus.COMPLETED;
    final SyncStats totalSyncStats = getTotalStats(timeTracker, hasReplicationCompleted);
    final List<StreamSyncStats> streamSyncStats = SyncStatsBuilder.getPerStreamStats(messageTracker.getSyncStatsTracker(),
        hasReplicationCompleted);

    if (!hasReplicationCompleted && messageTracker.getSyncStatsTracker().getUnreliableStateTimingMetrics()) {
      LOGGER.warn("Could not reliably determine committed record counts, committed record stats will be set to null");
    }

    final ReplicationAttemptSummary summary = new ReplicationAttemptSummary()
        .withStatus(outputStatus)
        // TODO records and bytes synced should no longer be used as we are consuming total stats, we should
        // make a pass to remove them.
        .withRecordsSynced(messageTracker.getSyncStatsTracker().getTotalRecordsEmitted())
        .withBytesSynced(messageTracker.getSyncStatsTracker().getTotalBytesEmitted())
        .withTotalStats(totalSyncStats)
        .withStreamStats(streamSyncStats)
        .withStartTime(timeTracker.getReplicationStartTime())
        .withEndTime(System.currentTimeMillis());

    final ReplicationOutput output = new ReplicationOutput()
        .withReplicationAttemptSummary(summary)
        .withOutputCatalog(destinationConfig.getCatalog());

    final List<FailureReason> failures = getFailureReasons(replicationRunnableFailureRef, destinationRunnableFailureRef,
        output);

    if (!shouldCommitStateAsap(syncInput)) {
      prepStateForLaterSaving(syncInput, output);
    }

    final ObjectMapper mapper = new ObjectMapper();
    LOGGER.info("sync summary: {}", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    LOGGER.info("failures: {}", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(failures));
    LineGobbler.endSection("REPLICATION");

    return output;
  }

  private SyncStats getTotalStats(final ThreadedTimeTracker timeTracker, final boolean hasReplicationCompleted) {
    final SyncStats totalSyncStats = SyncStatsBuilder.getTotalStats(messageTracker.getSyncStatsTracker(), hasReplicationCompleted);
    totalSyncStats.setReplicationStartTime(timeTracker.getReplicationStartTime());
    totalSyncStats.setReplicationEndTime(timeTracker.getReplicationEndTime());
    totalSyncStats.setSourceReadStartTime(timeTracker.getSourceReadStartTime());
    totalSyncStats.setSourceReadEndTime(timeTracker.getSourceReadEndTime());
    totalSyncStats.setDestinationWriteStartTime(timeTracker.getDestinationWriteStartTime());
    totalSyncStats.setDestinationWriteEndTime(timeTracker.getDestinationWriteEndTime());

    return totalSyncStats;
  }

  /**
   * Extracts state out to the {@link ReplicationOutput} so it can be later saved in the
   * PersistStateActivity - State is NOT SAVED here.
   *
   * @param syncInput sync input
   * @param output sync output
   */
  private void prepStateForLaterSaving(final StandardSyncInput syncInput, final ReplicationOutput output) {
    if (messageTracker.getSourceOutputState().isPresent()) {
      LOGGER.info("Source output at least one state message");
    } else {
      LOGGER.info("Source did not output any state messages");
    }

    if (messageTracker.getDestinationOutputState().isPresent()) {
      LOGGER.info("State capture: Updated state to: {}", messageTracker.getDestinationOutputState());
      final State state = messageTracker.getDestinationOutputState().get();
      output.withState(state);
    } else if (syncInput.getState() != null) {
      LOGGER.warn("State capture: No new state, falling back on input state: {}", syncInput.getState());
      output.withState(syncInput.getState());
    } else {
      LOGGER.warn("State capture: No state retained.");
    }

    if (messageTracker.getSyncStatsTracker().getUnreliableStateTimingMetrics()) {
      metricReporter.trackStateMetricTrackerError();
    }
  }

  private List<FailureReason> getFailureReasons(final AtomicReference<FailureReason> replicationRunnableFailureRef,
                                                final AtomicReference<FailureReason> destinationRunnableFailureRef,
                                                final ReplicationOutput output) {
    // only .setFailures() if a failure occurred or if there is an AirbyteErrorTraceMessage
    final FailureReason sourceFailure = replicationRunnableFailureRef.get();
    final FailureReason destinationFailure = destinationRunnableFailureRef.get();
    final FailureReason traceMessageFailure = messageTracker.errorTraceMessageFailure(Long.valueOf(jobId), attempt);

    final List<FailureReason> failures = new ArrayList<>();

    if (traceMessageFailure != null) {
      failures.add(traceMessageFailure);
    }

    if (sourceFailure != null) {
      failures.add(sourceFailure);
    }
    if (destinationFailure != null) {
      failures.add(destinationFailure);
    }
    if (!failures.isEmpty()) {
      output.setFailures(failures);
    }
    return failures;
  }

  private static void validateSchemaUncounted(final RecordSchemaValidator recordSchemaValidator,
                                              final Map<AirbyteStreamNameNamespacePair, Set<String>> streamToAllFields,
                                              final Map<AirbyteStreamNameNamespacePair, Set<String>> unexpectedFields,
                                              final ConcurrentHashMap<AirbyteStreamNameNamespacePair, Set<String>> validationErrors,
                                              final AirbyteMessage message) {
    if (message.getRecord() == null) {
      return;
    }

    final AirbyteRecordMessage record = message.getRecord();
    final AirbyteStreamNameNamespacePair messageStream = AirbyteStreamNameNamespacePair.fromRecordMessage(record);

    recordSchemaValidator.validateSchemaWithoutCounting(record, messageStream, validationErrors);
    final Set<String> unexpectedFieldNames = getUnexpectedFieldNames(record, streamToAllFields.get(messageStream));
    if (!unexpectedFieldNames.isEmpty()) {
      unexpectedFields.computeIfAbsent(messageStream, k -> new HashSet<>()).addAll(unexpectedFieldNames);
    }
  }

  private static void validateSchema(final RecordSchemaValidator recordSchemaValidator,
                                     final Map<AirbyteStreamNameNamespacePair, Set<String>> streamToAllFields,
                                     final Map<AirbyteStreamNameNamespacePair, Set<String>> unexpectedFields,
                                     final ConcurrentHashMap<AirbyteStreamNameNamespacePair, ImmutablePair<Set<String>, Integer>> validationErrors,
                                     final AirbyteMessage message) {
    if (message.getRecord() == null) {
      return;
    }

    final AirbyteRecordMessage record = message.getRecord();
    final AirbyteStreamNameNamespacePair messageStream = AirbyteStreamNameNamespacePair.fromRecordMessage(record);
    // avoid noise by validating only if the stream has less than 10 records with validation errors
    final boolean streamHasLessThenTenErrs = validationErrors.get(messageStream) == null || validationErrors.get(messageStream).getRight() < 10;
    if (streamHasLessThenTenErrs) {
      recordSchemaValidator.validateSchema(record, messageStream, validationErrors);
      final Set<String> unexpectedFieldNames = getUnexpectedFieldNames(record, streamToAllFields.get(messageStream));
      if (!unexpectedFieldNames.isEmpty()) {
        unexpectedFields.computeIfAbsent(messageStream, k -> new HashSet<>()).addAll(unexpectedFieldNames);
      }
    }
  }

  private static Set<String> getUnexpectedFieldNames(final AirbyteRecordMessage record,
                                                     final Set<String> fieldsInCatalog) {
    Set<String> unexpectedFieldNames = new HashSet<>();
    final JsonNode data = record.getData();
    // If it's not an object it's malformed, but we tolerate it here - it will be logged as an error by
    // the validation.
    if (data.isObject()) {
      final Iterator<String> fieldNamesInRecord = data.fieldNames();
      while (fieldNamesInRecord.hasNext()) {
        final String fieldName = fieldNamesInRecord.next();
        if (!fieldsInCatalog.contains(fieldName)) {
          unexpectedFieldNames.add(fieldName);
        }
      }
    }
    return unexpectedFieldNames;
  }

  /**
   * Generates a map from stream -> the explicit list of fields included for that stream, according to
   * the configured catalog. Since the configured catalog only includes the selected fields, this lets
   * us filter records to only the fields explicitly requested.
   *
   * @param catalog catalog
   * @param streamToSelectedFields map of stream descriptor to list of selected fields
   */
  private static void populatedStreamToSelectedFields(final ConfiguredAirbyteCatalog catalog,
                                                      final Map<AirbyteStreamNameNamespacePair, List<String>> streamToSelectedFields) {
    for (final var s : catalog.getStreams()) {
      final List<String> selectedFields = new ArrayList<>();
      final JsonNode propertiesNode = s.getStream().getJsonSchema().findPath("properties");
      if (propertiesNode.isObject()) {
        propertiesNode.fieldNames().forEachRemaining((fieldName) -> selectedFields.add(fieldName));
      } else {
        throw new RuntimeException("No properties node in stream schema");
      }
      streamToSelectedFields.put(AirbyteStreamNameNamespacePair.fromConfiguredAirbyteSteam(s), selectedFields);
    }
  }

  /**
   * Populates a map for stream -> all the top-level fields in the catalog. Used to identify any
   * unexpected top-level fields in the records.
   *
   * @param catalog catalog
   * @param streamToAllFields map of stream descriptor to set of all of its fields
   */
  private static void populateStreamToAllFields(final ConfiguredAirbyteCatalog catalog,
                                                final Map<AirbyteStreamNameNamespacePair, Set<String>> streamToAllFields) {
    for (final var s : catalog.getStreams()) {
      final Set<String> fields = new HashSet<>();
      final JsonNode propertiesNode = s.getStream().getJsonSchema().findPath("properties");
      if (propertiesNode.isObject()) {
        propertiesNode.fieldNames().forEachRemaining((fieldName) -> fields.add(fieldName));
      } else {
        throw new RuntimeException("No properties node in stream schema");
      }
      streamToAllFields.put(AirbyteStreamNameNamespacePair.fromConfiguredAirbyteSteam(s), fields);
    }
  }

  private static void filterSelectedFields(final Map<AirbyteStreamNameNamespacePair, List<String>> streamToSelectedFields,
                                           final AirbyteMessage airbyteMessage) {
    final AirbyteRecordMessage record = airbyteMessage.getRecord();

    if (record == null) {
      // This isn't a record message, so we don't need to do any filtering.
      return;
    }

    final AirbyteStreamNameNamespacePair messageStream = AirbyteStreamNameNamespacePair.fromRecordMessage(record);
    final List<String> selectedFields = streamToSelectedFields.getOrDefault(messageStream, Collections.emptyList());
    final JsonNode data = record.getData();
    if (data.isObject()) {
      ((ObjectNode) data).retain(selectedFields);
    } else {
      throw new RuntimeException(String.format("Unexpected data in record: %s", data.toString()));
    }
  }

  @Trace(operationName = WORKER_OPERATION_NAME)
  @Override
  public void cancel() {
    // Resources are closed in the opposite order they are declared.
    LOGGER.info("Cancelling replication worker...");
    try {
      executors.awaitTermination(10, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      ApmTraceUtils.addExceptionToTrace(e);
      LOGGER.error("Unable to cancel due to interruption.", e);
    }
    cancelled.set(true);

    LOGGER.info("Cancelling destination...");
    try {
      destination.cancel();
    } catch (final Exception e) {
      ApmTraceUtils.addExceptionToTrace(e);
      LOGGER.info("Error cancelling destination: ", e);
    }

    LOGGER.info("Cancelling source...");
    try {
      source.cancel();
    } catch (final Exception e) {
      ApmTraceUtils.addExceptionToTrace(e);
      LOGGER.info("Error cancelling source: ", e);
    }

  }

  /**
   * Helper function to read the shouldCommitStateAsap feature flag.
   */
  public static boolean shouldCommitStateAsap(final StandardSyncInput syncInput) {
    return syncInput.getCommitStateAsap() != null && syncInput.getCommitStateAsap();
  }

  /**
   * Helper function to read the shouldCommitStatsAsap feature flag.
   */
  public static boolean shouldCommitStatsAsap(final StandardSyncInput syncInput) {
    // For consistency, we should only be committing stats early if we are committing states early.
    // Otherwise, we are risking stats discrepancy as we are committing stats for states that haven't
    // been persisted yet.
    return shouldCommitStateAsap(syncInput) && syncInput.getCommitStatsAsap() != null && syncInput.getCommitStatsAsap();
  }

}
