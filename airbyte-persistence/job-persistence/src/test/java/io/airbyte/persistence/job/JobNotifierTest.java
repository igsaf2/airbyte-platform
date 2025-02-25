/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.persistence.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import io.airbyte.analytics.TrackingClient;
import io.airbyte.config.ActorDefinitionVersion;
import io.airbyte.config.JobConfig;
import io.airbyte.config.JobConfig.ConfigType;
import io.airbyte.config.Notification;
import io.airbyte.config.Notification.NotificationType;
import io.airbyte.config.SlackNotificationConfiguration;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.StandardSync;
import io.airbyte.config.StandardWorkspace;
import io.airbyte.config.persistence.ActorDefinitionVersionHelper;
import io.airbyte.config.persistence.ConfigNotFoundException;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.notification.NotificationClient;
import io.airbyte.persistence.job.models.Job;
import io.airbyte.persistence.job.models.JobStatus;
import io.airbyte.validation.json.JsonValidationException;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JobNotifierTest {

  private static final String WEBAPP_URL = "http://localhost:8000";
  private static final Instant NOW = Instant.now();
  private static final String TEST_DOCKER_REPO = "airbyte/test-image";
  private static final String TEST_DOCKER_TAG = "0.1.0";
  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private static final UUID SOURCE_ID = UUID.randomUUID();
  private static final UUID DESTINATION_ID = UUID.randomUUID();

  private final WebUrlHelper webUrlHelper = new WebUrlHelper(WEBAPP_URL);

  private ConfigRepository configRepository;
  private WorkspaceHelper workspaceHelper;
  private JobNotifier jobNotifier;
  private NotificationClient notificationClient;
  private TrackingClient trackingClient;
  private ActorDefinitionVersionHelper actorDefinitionVersionHelper;

  @BeforeEach
  void setup() {
    configRepository = mock(ConfigRepository.class);
    workspaceHelper = mock(WorkspaceHelper.class);
    trackingClient = mock(TrackingClient.class);
    actorDefinitionVersionHelper = mock(ActorDefinitionVersionHelper.class);

    jobNotifier = Mockito.spy(new JobNotifier(webUrlHelper, configRepository, workspaceHelper, trackingClient, actorDefinitionVersionHelper));
    notificationClient = mock(NotificationClient.class);
    when(jobNotifier.getNotificationClient(getSlackNotification())).thenReturn(notificationClient);
  }

  @Test
  void testFailJob() throws IOException, InterruptedException, JsonValidationException, ConfigNotFoundException {
    final Job job = createJob();
    final StandardSourceDefinition sourceDefinition = new StandardSourceDefinition()
        .withName("source-test")
        .withSourceDefinitionId(UUID.randomUUID());
    final StandardDestinationDefinition destinationDefinition = new StandardDestinationDefinition()
        .withName("destination-test")
        .withDestinationDefinitionId(UUID.randomUUID());
    final ActorDefinitionVersion actorDefinitionVersion = new ActorDefinitionVersion()
        .withDockerImageTag(TEST_DOCKER_TAG)
        .withDockerRepository(TEST_DOCKER_REPO);
    when(configRepository.getStandardSync(UUID.fromString(job.getScope())))
        .thenReturn(new StandardSync().withSourceId(SOURCE_ID).withDestinationId(DESTINATION_ID));
    when(configRepository.getSourceDefinitionFromConnection(any())).thenReturn(sourceDefinition);
    when(configRepository.getDestinationDefinitionFromConnection(any())).thenReturn(destinationDefinition);
    when(configRepository.getStandardSourceDefinition(any())).thenReturn(sourceDefinition);
    when(configRepository.getStandardDestinationDefinition(any())).thenReturn(destinationDefinition);
    when(configRepository.getStandardWorkspaceNoSecrets(WORKSPACE_ID, true)).thenReturn(getWorkspace());
    when(workspaceHelper.getWorkspaceForJobIdIgnoreExceptions(job.getId())).thenReturn(WORKSPACE_ID);
    when(notificationClient.notifyJobFailure(anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
    when(actorDefinitionVersionHelper.getSourceVersion(sourceDefinition, WORKSPACE_ID, SOURCE_ID)).thenReturn(actorDefinitionVersion);
    when(actorDefinitionVersionHelper.getDestinationVersion(destinationDefinition, WORKSPACE_ID, DESTINATION_ID)).thenReturn(actorDefinitionVersion);

    jobNotifier.failJob("JobNotifierTest was running", job);
    final DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL).withZone(ZoneId.systemDefault());
    verify(notificationClient).notifyJobFailure(
        "source-test",
        "destination-test",
        String.format("sync started on %s, running for 1 day 10 hours 17 minutes 36 seconds, as the JobNotifierTest was running.",
            formatter.format(Instant.ofEpochSecond(job.getStartedAtInSecond().get()))),
        String.format("http://localhost:8000/workspaces/%s/connections/%s", WORKSPACE_ID, job.getScope()),
        job.getId());

    final Builder<String, Object> metadata = ImmutableMap.builder();
    metadata.put("connection_id", UUID.fromString(job.getScope()));
    metadata.put("connector_source_definition_id", sourceDefinition.getSourceDefinitionId());
    metadata.put("connector_source", "source-test");
    metadata.put("connector_source_version", TEST_DOCKER_TAG);
    metadata.put("connector_source_docker_repository", actorDefinitionVersion.getDockerRepository());
    metadata.put("connector_destination_definition_id", destinationDefinition.getDestinationDefinitionId());
    metadata.put("connector_destination", "destination-test");
    metadata.put("connector_destination_version", TEST_DOCKER_TAG);
    metadata.put("connector_destination_docker_repository", actorDefinitionVersion.getDockerRepository());
    metadata.put("notification_type", NotificationType.SLACK);
    verify(trackingClient).track(WORKSPACE_ID, JobNotifier.FAILURE_NOTIFICATION, metadata.build());
  }

  private static StandardWorkspace getWorkspace() {
    return new StandardWorkspace()
        .withCustomerId(UUID.randomUUID())
        .withNotifications(List.of(getSlackNotification()));
  }

  private static Job createJob() {
    return new Job(
        10L,
        ConfigType.SYNC,
        UUID.randomUUID().toString(),
        new JobConfig(),
        Collections.emptyList(),
        JobStatus.FAILED,
        NOW.getEpochSecond(),
        NOW.getEpochSecond(),
        NOW.getEpochSecond() + 123456L);
  }

  private static Notification getSlackNotification() {
    return new Notification()
        .withNotificationType(NotificationType.SLACK)
        .withSlackConfiguration(new SlackNotificationConfiguration()
            .withWebhook("http://random.webhook.url/hooks.slack.com/"));
  }

}
