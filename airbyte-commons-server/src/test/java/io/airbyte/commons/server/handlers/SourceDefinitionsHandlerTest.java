/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.server.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.airbyte.api.model.generated.CustomSourceDefinitionCreate;
import io.airbyte.api.model.generated.PrivateSourceDefinitionRead;
import io.airbyte.api.model.generated.PrivateSourceDefinitionReadList;
import io.airbyte.api.model.generated.ReleaseStage;
import io.airbyte.api.model.generated.SourceDefinitionCreate;
import io.airbyte.api.model.generated.SourceDefinitionIdRequestBody;
import io.airbyte.api.model.generated.SourceDefinitionIdWithWorkspaceId;
import io.airbyte.api.model.generated.SourceDefinitionRead;
import io.airbyte.api.model.generated.SourceDefinitionReadList;
import io.airbyte.api.model.generated.SourceDefinitionUpdate;
import io.airbyte.api.model.generated.SourceRead;
import io.airbyte.api.model.generated.SourceReadList;
import io.airbyte.api.model.generated.WorkspaceIdRequestBody;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.server.errors.IdNotFoundKnownException;
import io.airbyte.commons.server.errors.UnsupportedProtocolVersionException;
import io.airbyte.commons.server.scheduler.SynchronousJobMetadata;
import io.airbyte.commons.server.scheduler.SynchronousResponse;
import io.airbyte.commons.server.scheduler.SynchronousSchedulerClient;
import io.airbyte.commons.server.services.AirbyteRemoteOssCatalog;
import io.airbyte.commons.version.AirbyteProtocolVersionRange;
import io.airbyte.commons.version.Version;
import io.airbyte.config.ActorDefinitionResourceRequirements;
import io.airbyte.config.ActorType;
import io.airbyte.config.ConnectorRegistrySourceDefinition;
import io.airbyte.config.JobConfig.ConfigType;
import io.airbyte.config.ResourceRequirements;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.helpers.ConnectorRegistryConverters;
import io.airbyte.config.persistence.ConfigNotFoundException;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.protocol.models.ConnectorSpecification;
import io.airbyte.validation.json.JsonValidationException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SourceDefinitionsHandlerTest {

  private static final String TODAY_DATE_STRING = LocalDate.now().toString();
  private static final String DEFAULT_PROTOCOL_VERSION = "0.2.0";

  private ConfigRepository configRepository;
  private StandardSourceDefinition sourceDefinition;
  private SourceDefinitionsHandler sourceDefinitionsHandler;
  private Supplier<UUID> uuidSupplier;
  private SynchronousSchedulerClient schedulerSynchronousClient;
  private AirbyteRemoteOssCatalog githubStore;
  private SourceHandler sourceHandler;
  private UUID workspaceId;
  private AirbyteProtocolVersionRange protocolVersionRange;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    configRepository = mock(ConfigRepository.class);
    uuidSupplier = mock(Supplier.class);
    schedulerSynchronousClient = spy(SynchronousSchedulerClient.class);
    githubStore = mock(AirbyteRemoteOssCatalog.class);
    sourceHandler = mock(SourceHandler.class);
    workspaceId = UUID.randomUUID();

    sourceDefinition = generateSourceDefinition();

    protocolVersionRange = new AirbyteProtocolVersionRange(new Version("0.0.0"), new Version("0.3.0"));

    sourceDefinitionsHandler = new SourceDefinitionsHandler(configRepository, uuidSupplier, schedulerSynchronousClient, githubStore, sourceHandler,
        protocolVersionRange);
  }

  private StandardSourceDefinition generateSourceDefinition() {
    final UUID sourceDefinitionId = UUID.randomUUID();
    final ConnectorSpecification spec = new ConnectorSpecification().withConnectionSpecification(
        Jsons.jsonNode(ImmutableMap.of("foo", "bar")));

    return new StandardSourceDefinition()
        .withSourceDefinitionId(sourceDefinitionId)
        .withName("presto")
        .withDocumentationUrl("https://netflix.com")
        .withDockerRepository("dockerstuff")
        .withDockerImageTag("12.3")
        .withIcon("rss.svg")
        .withSpec(spec)
        .withTombstone(false)
        .withReleaseStage(io.airbyte.config.ReleaseStage.ALPHA)
        .withReleaseDate(TODAY_DATE_STRING)
        .withResourceRequirements(new ActorDefinitionResourceRequirements().withDefault(new ResourceRequirements().withCpuRequest("2")));

  }

  @Test
  @DisplayName("listSourceDefinition should return the right list")
  void testListSourceDefinitions() throws JsonValidationException, IOException, URISyntaxException {
    final StandardSourceDefinition sourceDefinition2 = generateSourceDefinition();

    when(configRepository.listStandardSourceDefinitions(false)).thenReturn(Lists.newArrayList(sourceDefinition, sourceDefinition2));

    final SourceDefinitionRead expectedSourceDefinitionRead1 = new SourceDefinitionRead()
        .sourceDefinitionId(sourceDefinition.getSourceDefinitionId())
        .name(sourceDefinition.getName())
        .dockerRepository(sourceDefinition.getDockerRepository())
        .dockerImageTag(sourceDefinition.getDockerImageTag())
        .documentationUrl(new URI(sourceDefinition.getDocumentationUrl()))
        .icon(SourceDefinitionsHandler.loadIcon(sourceDefinition.getIcon()))
        .releaseStage(ReleaseStage.fromValue(sourceDefinition.getReleaseStage().value()))
        .releaseDate(LocalDate.parse(sourceDefinition.getReleaseDate()))
        .resourceRequirements(new io.airbyte.api.model.generated.ActorDefinitionResourceRequirements()
            ._default(new io.airbyte.api.model.generated.ResourceRequirements()
                .cpuRequest(sourceDefinition.getResourceRequirements().getDefault().getCpuRequest()))
            .jobSpecific(Collections.emptyList()));

    final SourceDefinitionRead expectedSourceDefinitionRead2 = new SourceDefinitionRead()
        .sourceDefinitionId(sourceDefinition2.getSourceDefinitionId())
        .name(sourceDefinition2.getName())
        .dockerRepository(sourceDefinition.getDockerRepository())
        .dockerImageTag(sourceDefinition.getDockerImageTag())
        .documentationUrl(new URI(sourceDefinition.getDocumentationUrl()))
        .icon(SourceDefinitionsHandler.loadIcon(sourceDefinition.getIcon()))
        .releaseStage(ReleaseStage.fromValue(sourceDefinition.getReleaseStage().value()))
        .releaseDate(LocalDate.parse(sourceDefinition.getReleaseDate()))
        .resourceRequirements(new io.airbyte.api.model.generated.ActorDefinitionResourceRequirements()
            ._default(new io.airbyte.api.model.generated.ResourceRequirements()
                .cpuRequest(sourceDefinition2.getResourceRequirements().getDefault().getCpuRequest()))
            .jobSpecific(Collections.emptyList()));

    final SourceDefinitionReadList actualSourceDefinitionReadList = sourceDefinitionsHandler.listSourceDefinitions();

    assertEquals(
        Lists.newArrayList(expectedSourceDefinitionRead1, expectedSourceDefinitionRead2),
        actualSourceDefinitionReadList.getSourceDefinitions());
  }

  @Test
  @DisplayName("listSourceDefinitionsForWorkspace should return the right list")
  void testListSourceDefinitionsForWorkspace() throws IOException, URISyntaxException {
    final StandardSourceDefinition sourceDefinition2 = generateSourceDefinition();

    when(configRepository.listPublicSourceDefinitions(false)).thenReturn(Lists.newArrayList(sourceDefinition));
    when(configRepository.listGrantedSourceDefinitions(workspaceId, false)).thenReturn(Lists.newArrayList(sourceDefinition2));

    final SourceDefinitionRead expectedSourceDefinitionRead1 = new SourceDefinitionRead()
        .sourceDefinitionId(sourceDefinition.getSourceDefinitionId())
        .name(sourceDefinition.getName())
        .dockerRepository(sourceDefinition.getDockerRepository())
        .dockerImageTag(sourceDefinition.getDockerImageTag())
        .documentationUrl(new URI(sourceDefinition.getDocumentationUrl()))
        .icon(SourceDefinitionsHandler.loadIcon(sourceDefinition.getIcon()))
        .releaseStage(ReleaseStage.fromValue(sourceDefinition.getReleaseStage().value()))
        .releaseDate(LocalDate.parse(sourceDefinition.getReleaseDate()))
        .resourceRequirements(new io.airbyte.api.model.generated.ActorDefinitionResourceRequirements()
            ._default(new io.airbyte.api.model.generated.ResourceRequirements()
                .cpuRequest(sourceDefinition.getResourceRequirements().getDefault().getCpuRequest()))
            .jobSpecific(Collections.emptyList()));

    final SourceDefinitionRead expectedSourceDefinitionRead2 = new SourceDefinitionRead()
        .sourceDefinitionId(sourceDefinition2.getSourceDefinitionId())
        .name(sourceDefinition2.getName())
        .dockerRepository(sourceDefinition.getDockerRepository())
        .dockerImageTag(sourceDefinition.getDockerImageTag())
        .documentationUrl(new URI(sourceDefinition.getDocumentationUrl()))
        .icon(SourceDefinitionsHandler.loadIcon(sourceDefinition.getIcon()))
        .releaseStage(ReleaseStage.fromValue(sourceDefinition.getReleaseStage().value()))
        .releaseDate(LocalDate.parse(sourceDefinition.getReleaseDate()))
        .resourceRequirements(new io.airbyte.api.model.generated.ActorDefinitionResourceRequirements()
            ._default(new io.airbyte.api.model.generated.ResourceRequirements()
                .cpuRequest(sourceDefinition2.getResourceRequirements().getDefault().getCpuRequest()))
            .jobSpecific(Collections.emptyList()));

    final SourceDefinitionReadList actualSourceDefinitionReadList =
        sourceDefinitionsHandler.listSourceDefinitionsForWorkspace(new WorkspaceIdRequestBody().workspaceId(workspaceId));

    assertEquals(
        Lists.newArrayList(expectedSourceDefinitionRead1, expectedSourceDefinitionRead2),
        actualSourceDefinitionReadList.getSourceDefinitions());
  }

  @Test
  @DisplayName("listPrivateSourceDefinitions should return the right list")
  void testListPrivateSourceDefinitions() throws IOException, URISyntaxException {
    final StandardSourceDefinition sourceDefinition2 = generateSourceDefinition();

    when(configRepository.listGrantableSourceDefinitions(workspaceId, false)).thenReturn(
        Lists.newArrayList(
            Map.entry(sourceDefinition, false),
            Map.entry(sourceDefinition2, true)));

    final SourceDefinitionRead expectedSourceDefinitionRead1 = new SourceDefinitionRead()
        .sourceDefinitionId(sourceDefinition.getSourceDefinitionId())
        .name(sourceDefinition.getName())
        .dockerRepository(sourceDefinition.getDockerRepository())
        .dockerImageTag(sourceDefinition.getDockerImageTag())
        .documentationUrl(new URI(sourceDefinition.getDocumentationUrl()))
        .icon(SourceDefinitionsHandler.loadIcon(sourceDefinition.getIcon()))
        .releaseStage(ReleaseStage.fromValue(sourceDefinition.getReleaseStage().value()))
        .releaseDate(LocalDate.parse(sourceDefinition.getReleaseDate()))
        .resourceRequirements(new io.airbyte.api.model.generated.ActorDefinitionResourceRequirements()
            ._default(new io.airbyte.api.model.generated.ResourceRequirements()
                .cpuRequest(sourceDefinition.getResourceRequirements().getDefault().getCpuRequest()))
            .jobSpecific(Collections.emptyList()));

    final SourceDefinitionRead expectedSourceDefinitionRead2 = new SourceDefinitionRead()
        .sourceDefinitionId(sourceDefinition2.getSourceDefinitionId())
        .name(sourceDefinition2.getName())
        .dockerRepository(sourceDefinition.getDockerRepository())
        .dockerImageTag(sourceDefinition.getDockerImageTag())
        .documentationUrl(new URI(sourceDefinition.getDocumentationUrl()))
        .icon(SourceDefinitionsHandler.loadIcon(sourceDefinition.getIcon()))
        .releaseStage(ReleaseStage.fromValue(sourceDefinition.getReleaseStage().value()))
        .releaseDate(LocalDate.parse(sourceDefinition.getReleaseDate()))
        .resourceRequirements(new io.airbyte.api.model.generated.ActorDefinitionResourceRequirements()
            ._default(new io.airbyte.api.model.generated.ResourceRequirements()
                .cpuRequest(sourceDefinition2.getResourceRequirements().getDefault().getCpuRequest()))
            .jobSpecific(Collections.emptyList()));

    final PrivateSourceDefinitionRead expectedSourceDefinitionOptInRead1 =
        new PrivateSourceDefinitionRead().sourceDefinition(expectedSourceDefinitionRead1).granted(false);

    final PrivateSourceDefinitionRead expectedSourceDefinitionOptInRead2 =
        new PrivateSourceDefinitionRead().sourceDefinition(expectedSourceDefinitionRead2).granted(true);

    final PrivateSourceDefinitionReadList actualSourceDefinitionOptInReadList = sourceDefinitionsHandler.listPrivateSourceDefinitions(
        new WorkspaceIdRequestBody().workspaceId(workspaceId));

    assertEquals(
        Lists.newArrayList(expectedSourceDefinitionOptInRead1, expectedSourceDefinitionOptInRead2),
        actualSourceDefinitionOptInReadList.getSourceDefinitions());
  }

  @Test
  @DisplayName("getSourceDefinition should return the right source")
  void testGetSourceDefinition() throws JsonValidationException, ConfigNotFoundException, IOException, URISyntaxException {
    when(configRepository.getStandardSourceDefinition(sourceDefinition.getSourceDefinitionId()))
        .thenReturn(sourceDefinition);

    final SourceDefinitionRead expectedSourceDefinitionRead = new SourceDefinitionRead()
        .sourceDefinitionId(sourceDefinition.getSourceDefinitionId())
        .name(sourceDefinition.getName())
        .dockerRepository(sourceDefinition.getDockerRepository())
        .dockerImageTag(sourceDefinition.getDockerImageTag())
        .documentationUrl(new URI(sourceDefinition.getDocumentationUrl()))
        .icon(SourceDefinitionsHandler.loadIcon(sourceDefinition.getIcon()))
        .releaseStage(ReleaseStage.fromValue(sourceDefinition.getReleaseStage().value()))
        .releaseDate(LocalDate.parse(sourceDefinition.getReleaseDate()))
        .resourceRequirements(new io.airbyte.api.model.generated.ActorDefinitionResourceRequirements()
            ._default(new io.airbyte.api.model.generated.ResourceRequirements()
                .cpuRequest(sourceDefinition.getResourceRequirements().getDefault().getCpuRequest()))
            .jobSpecific(Collections.emptyList()));

    final SourceDefinitionIdRequestBody sourceDefinitionIdRequestBody =
        new SourceDefinitionIdRequestBody().sourceDefinitionId(sourceDefinition.getSourceDefinitionId());

    final SourceDefinitionRead actualSourceDefinitionRead = sourceDefinitionsHandler.getSourceDefinition(sourceDefinitionIdRequestBody);

    assertEquals(expectedSourceDefinitionRead, actualSourceDefinitionRead);
  }

  @Test
  @DisplayName("getSourceDefinitionForWorkspace should throw an exception for a missing grant")
  void testGetDefinitionWithoutGrantForWorkspace() throws IOException {
    when(configRepository.workspaceCanUseDefinition(sourceDefinition.getSourceDefinitionId(), workspaceId))
        .thenReturn(false);

    final SourceDefinitionIdWithWorkspaceId sourceDefinitionIdWithWorkspaceId = new SourceDefinitionIdWithWorkspaceId()
        .sourceDefinitionId(sourceDefinition.getSourceDefinitionId())
        .workspaceId(workspaceId);

    assertThrows(IdNotFoundKnownException.class, () -> sourceDefinitionsHandler.getSourceDefinitionForWorkspace(sourceDefinitionIdWithWorkspaceId));
  }

  @Test
  @DisplayName("getSourceDefinitionForWorkspace should return the source if the grant exists")
  void testGetDefinitionWithGrantForWorkspace() throws JsonValidationException, ConfigNotFoundException, IOException, URISyntaxException {
    when(configRepository.workspaceCanUseDefinition(sourceDefinition.getSourceDefinitionId(), workspaceId))
        .thenReturn(true);
    when(configRepository.getStandardSourceDefinition(sourceDefinition.getSourceDefinitionId()))
        .thenReturn(sourceDefinition);

    final SourceDefinitionRead expectedSourceDefinitionRead = new SourceDefinitionRead()
        .sourceDefinitionId(sourceDefinition.getSourceDefinitionId())
        .name(sourceDefinition.getName())
        .dockerRepository(sourceDefinition.getDockerRepository())
        .dockerImageTag(sourceDefinition.getDockerImageTag())
        .documentationUrl(new URI(sourceDefinition.getDocumentationUrl()))
        .icon(SourceDefinitionsHandler.loadIcon(sourceDefinition.getIcon()))
        .releaseStage(ReleaseStage.fromValue(sourceDefinition.getReleaseStage().value()))
        .releaseDate(LocalDate.parse(sourceDefinition.getReleaseDate()))
        .resourceRequirements(new io.airbyte.api.model.generated.ActorDefinitionResourceRequirements()
            ._default(new io.airbyte.api.model.generated.ResourceRequirements()
                .cpuRequest(sourceDefinition.getResourceRequirements().getDefault().getCpuRequest()))
            .jobSpecific(Collections.emptyList()));

    final SourceDefinitionIdWithWorkspaceId sourceDefinitionIdWithWorkspaceId = new SourceDefinitionIdWithWorkspaceId()
        .sourceDefinitionId(sourceDefinition.getSourceDefinitionId())
        .workspaceId(workspaceId);

    final SourceDefinitionRead actualSourceDefinitionRead = sourceDefinitionsHandler
        .getSourceDefinitionForWorkspace(sourceDefinitionIdWithWorkspaceId);

    assertEquals(expectedSourceDefinitionRead, actualSourceDefinitionRead);
  }

  @Test
  @DisplayName("createSourceDefinition should not create a sourceDefinition with an unsupported protocol version")
  void testCreateSourceDefinitionWithInvalidProtocol() throws URISyntaxException, IOException, JsonValidationException {
    final String invalidProtocol = "131.1.2";
    final StandardSourceDefinition sourceDefinition = generateSourceDefinition();
    sourceDefinition.getSpec().setProtocolVersion(invalidProtocol);
    final String imageName = sourceDefinition.getDockerRepository() + ":" + sourceDefinition.getDockerImageTag();

    when(uuidSupplier.get()).thenReturn(sourceDefinition.getSourceDefinitionId());
    when(schedulerSynchronousClient.createGetSpecJob(imageName, true)).thenReturn(new SynchronousResponse<>(
        sourceDefinition.getSpec(),
        SynchronousJobMetadata.mock(ConfigType.GET_SPEC)));

    final SourceDefinitionCreate create = new SourceDefinitionCreate()
        .name(sourceDefinition.getName())
        .dockerRepository(sourceDefinition.getDockerRepository())
        .dockerImageTag(sourceDefinition.getDockerImageTag())
        .documentationUrl(new URI(sourceDefinition.getDocumentationUrl()))
        .icon(sourceDefinition.getIcon())
        .resourceRequirements(new io.airbyte.api.model.generated.ActorDefinitionResourceRequirements()
            ._default(new io.airbyte.api.model.generated.ResourceRequirements()
                .cpuRequest(sourceDefinition.getResourceRequirements().getDefault().getCpuRequest()))
            .jobSpecific(Collections.emptyList()));
    final CustomSourceDefinitionCreate customCreate = new CustomSourceDefinitionCreate()
        .sourceDefinition(create)
        .workspaceId(workspaceId);
    assertThrows(UnsupportedProtocolVersionException.class, () -> sourceDefinitionsHandler.createCustomSourceDefinition(customCreate));

    verify(schedulerSynchronousClient).createGetSpecJob(imageName, true);
    verify(configRepository, never())
        .writeStandardSourceDefinition(
            sourceDefinition
                .withReleaseDate(null)
                .withReleaseStage(io.airbyte.config.ReleaseStage.CUSTOM)
                .withProtocolVersion(DEFAULT_PROTOCOL_VERSION));
  }

  @Test
  @DisplayName("createCustomSourceDefinition should correctly create a sourceDefinition")
  void testCreateCustomSourceDefinition() throws URISyntaxException, IOException, JsonValidationException {
    final StandardSourceDefinition sourceDefinition = generateSourceDefinition();
    final String imageName = sourceDefinition.getDockerRepository() + ":" + sourceDefinition.getDockerImageTag();

    when(uuidSupplier.get()).thenReturn(sourceDefinition.getSourceDefinitionId());
    when(schedulerSynchronousClient.createGetSpecJob(imageName, true)).thenReturn(new SynchronousResponse<>(
        sourceDefinition.getSpec(),
        SynchronousJobMetadata.mock(ConfigType.GET_SPEC)));

    final SourceDefinitionCreate create = new SourceDefinitionCreate()
        .name(sourceDefinition.getName())
        .dockerRepository(sourceDefinition.getDockerRepository())
        .dockerImageTag(sourceDefinition.getDockerImageTag())
        .documentationUrl(new URI(sourceDefinition.getDocumentationUrl()))
        .icon(sourceDefinition.getIcon())
        .resourceRequirements(new io.airbyte.api.model.generated.ActorDefinitionResourceRequirements()
            ._default(new io.airbyte.api.model.generated.ResourceRequirements()
                .cpuRequest(sourceDefinition.getResourceRequirements().getDefault().getCpuRequest()))
            .jobSpecific(Collections.emptyList()));

    final CustomSourceDefinitionCreate customCreate = new CustomSourceDefinitionCreate()
        .sourceDefinition(create)
        .workspaceId(workspaceId);

    final SourceDefinitionRead expectedRead = new SourceDefinitionRead()
        .name(sourceDefinition.getName())
        .dockerRepository(sourceDefinition.getDockerRepository())
        .dockerImageTag(sourceDefinition.getDockerImageTag())
        .documentationUrl(new URI(sourceDefinition.getDocumentationUrl()))
        .sourceDefinitionId(sourceDefinition.getSourceDefinitionId())
        .icon(SourceDefinitionsHandler.loadIcon(sourceDefinition.getIcon()))
        .protocolVersion(DEFAULT_PROTOCOL_VERSION)
        .releaseStage(ReleaseStage.CUSTOM)
        .resourceRequirements(new io.airbyte.api.model.generated.ActorDefinitionResourceRequirements()
            ._default(new io.airbyte.api.model.generated.ResourceRequirements()
                .cpuRequest(sourceDefinition.getResourceRequirements().getDefault().getCpuRequest()))
            .jobSpecific(Collections.emptyList()));

    final SourceDefinitionRead actualRead = sourceDefinitionsHandler.createCustomSourceDefinition(customCreate);

    assertEquals(expectedRead, actualRead);
    verify(schedulerSynchronousClient).createGetSpecJob(imageName, true);
    verify(configRepository).writeCustomSourceDefinition(
        sourceDefinition
            .withReleaseDate(null)
            .withReleaseStage(io.airbyte.config.ReleaseStage.CUSTOM)
            .withProtocolVersion(DEFAULT_PROTOCOL_VERSION)
            .withCustom(true),
        workspaceId);
  }

  @Test
  @DisplayName("createCustomSourceDefinition should not create a sourceDefinition with unspported protocol version")
  void testCreateCustomSourceDefinitionWithInvalidProtocol() throws URISyntaxException, IOException, JsonValidationException {
    final String invalidVersion = "130.0.0";
    final StandardSourceDefinition sourceDefinition = generateSourceDefinition();
    sourceDefinition.getSpec().setProtocolVersion(invalidVersion);
    final String imageName = sourceDefinition.getDockerRepository() + ":" + sourceDefinition.getDockerImageTag();

    when(uuidSupplier.get()).thenReturn(sourceDefinition.getSourceDefinitionId());
    when(schedulerSynchronousClient.createGetSpecJob(imageName, true)).thenReturn(new SynchronousResponse<>(
        sourceDefinition.getSpec(),
        SynchronousJobMetadata.mock(ConfigType.GET_SPEC)));

    final SourceDefinitionCreate create = new SourceDefinitionCreate()
        .name(sourceDefinition.getName())
        .dockerRepository(sourceDefinition.getDockerRepository())
        .dockerImageTag(sourceDefinition.getDockerImageTag())
        .documentationUrl(new URI(sourceDefinition.getDocumentationUrl()))
        .icon(sourceDefinition.getIcon())
        .resourceRequirements(new io.airbyte.api.model.generated.ActorDefinitionResourceRequirements()
            ._default(new io.airbyte.api.model.generated.ResourceRequirements()
                .cpuRequest(sourceDefinition.getResourceRequirements().getDefault().getCpuRequest()))
            .jobSpecific(Collections.emptyList()));

    final CustomSourceDefinitionCreate customCreate = new CustomSourceDefinitionCreate()
        .sourceDefinition(create)
        .workspaceId(workspaceId);

    assertThrows(UnsupportedProtocolVersionException.class, () -> sourceDefinitionsHandler.createCustomSourceDefinition(customCreate));

    verify(schedulerSynchronousClient).createGetSpecJob(imageName, true);
    verify(configRepository, never()).writeCustomSourceDefinition(
        sourceDefinition
            .withReleaseDate(null)
            .withReleaseStage(io.airbyte.config.ReleaseStage.CUSTOM)
            .withProtocolVersion(invalidVersion)
            .withCustom(true),
        workspaceId);
  }

  @Test
  @DisplayName("updateSourceDefinition should correctly update a sourceDefinition")
  void testUpdateSourceDefinition() throws ConfigNotFoundException, IOException, JsonValidationException, URISyntaxException {
    when(configRepository.getStandardSourceDefinition(sourceDefinition.getSourceDefinitionId())).thenReturn(sourceDefinition);
    final String newDockerImageTag = "averydifferenttag";
    final String newProtocolVersion = "0.2.1";
    final SourceDefinitionRead sourceDefinition = sourceDefinitionsHandler
        .getSourceDefinition(new SourceDefinitionIdRequestBody().sourceDefinitionId(this.sourceDefinition.getSourceDefinitionId()));
    final String currentTag = sourceDefinition.getDockerImageTag();
    assertNotEquals(newDockerImageTag, currentTag);

    final String newImageName = this.sourceDefinition.getDockerRepository() + ":" + newDockerImageTag;
    final ConnectorSpecification newSpec = new ConnectorSpecification()
        .withConnectionSpecification(Jsons.jsonNode(ImmutableMap.of("foo2", "bar2")))
        .withProtocolVersion(newProtocolVersion);
    when(schedulerSynchronousClient.createGetSpecJob(newImageName, false)).thenReturn(new SynchronousResponse<>(
        newSpec,
        SynchronousJobMetadata.mock(ConfigType.GET_SPEC)));

    final StandardSourceDefinition updatedSource = Jsons.clone(this.sourceDefinition)
        .withDockerImageTag(newDockerImageTag).withSpec(newSpec).withProtocolVersion(newProtocolVersion);

    final SourceDefinitionRead sourceDefinitionRead = sourceDefinitionsHandler
        .updateSourceDefinition(
            new SourceDefinitionUpdate().sourceDefinitionId(this.sourceDefinition.getSourceDefinitionId()).dockerImageTag(newDockerImageTag));

    assertEquals(newDockerImageTag, sourceDefinitionRead.getDockerImageTag());
    verify(schedulerSynchronousClient).createGetSpecJob(newImageName, false);
    verify(configRepository).writeStandardSourceDefinition(updatedSource);

    verify(configRepository).clearUnsupportedProtocolVersionFlag(updatedSource.getSourceDefinitionId(), ActorType.SOURCE, protocolVersionRange);
  }

  @Test
  @DisplayName("updateSourceDefinition should not update a sourceDefinition with an invalid protocol version")
  void testUpdateSourceDefinitionWithInvalidProtocol() throws ConfigNotFoundException, IOException, JsonValidationException, URISyntaxException {
    when(configRepository.getStandardSourceDefinition(sourceDefinition.getSourceDefinitionId())).thenReturn(sourceDefinition);
    final String newDockerImageTag = "averydifferenttag";
    final String newProtocolVersion = "132.2.1";
    final SourceDefinitionRead sourceDefinition = sourceDefinitionsHandler
        .getSourceDefinition(new SourceDefinitionIdRequestBody().sourceDefinitionId(this.sourceDefinition.getSourceDefinitionId()));
    final String currentTag = sourceDefinition.getDockerImageTag();
    assertNotEquals(newDockerImageTag, currentTag);

    final String newImageName = this.sourceDefinition.getDockerRepository() + ":" + newDockerImageTag;
    final ConnectorSpecification newSpec = new ConnectorSpecification()
        .withConnectionSpecification(Jsons.jsonNode(ImmutableMap.of("foo2", "bar2")))
        .withProtocolVersion(newProtocolVersion);
    when(schedulerSynchronousClient.createGetSpecJob(newImageName, false)).thenReturn(new SynchronousResponse<>(
        newSpec,
        SynchronousJobMetadata.mock(ConfigType.GET_SPEC)));

    final StandardSourceDefinition updatedSource = Jsons.clone(this.sourceDefinition)
        .withDockerImageTag(newDockerImageTag).withSpec(newSpec).withProtocolVersion(newProtocolVersion);

    assertThrows(UnsupportedProtocolVersionException.class, () -> sourceDefinitionsHandler
        .updateSourceDefinition(
            new SourceDefinitionUpdate().sourceDefinitionId(this.sourceDefinition.getSourceDefinitionId()).dockerImageTag(newDockerImageTag)));

    verify(schedulerSynchronousClient).createGetSpecJob(newImageName, false);
    verify(configRepository, never()).writeStandardSourceDefinition(updatedSource);
  }

  @Test
  @DisplayName("deleteSourceDefinition should correctly delete a sourceDefinition")
  void testDeleteSourceDefinition() throws ConfigNotFoundException, IOException, JsonValidationException {
    final SourceDefinitionIdRequestBody sourceDefinitionIdRequestBody =
        new SourceDefinitionIdRequestBody().sourceDefinitionId(sourceDefinition.getSourceDefinitionId());
    final StandardSourceDefinition updatedSourceDefinition = Jsons.clone(this.sourceDefinition).withTombstone(true);
    final SourceRead source = new SourceRead();

    when(configRepository.getStandardSourceDefinition(sourceDefinition.getSourceDefinitionId()))
        .thenReturn(sourceDefinition);
    when(sourceHandler.listSourcesForSourceDefinition(sourceDefinitionIdRequestBody))
        .thenReturn(new SourceReadList().sources(Collections.singletonList(source)));

    assertFalse(sourceDefinition.getTombstone());

    sourceDefinitionsHandler.deleteSourceDefinition(sourceDefinitionIdRequestBody);

    verify(sourceHandler).deleteSource(source);
    verify(configRepository).writeStandardSourceDefinition(updatedSourceDefinition);
  }

  @Test
  @DisplayName("grantSourceDefinitionToWorkspace should correctly create a workspace grant")
  void testGrantSourceDefinitionToWorkspace() throws JsonValidationException, ConfigNotFoundException, IOException, URISyntaxException {
    when(configRepository.getStandardSourceDefinition(sourceDefinition.getSourceDefinitionId()))
        .thenReturn(sourceDefinition);

    final SourceDefinitionRead expectedSourceDefinitionRead = new SourceDefinitionRead()
        .sourceDefinitionId(sourceDefinition.getSourceDefinitionId())
        .name(sourceDefinition.getName())
        .dockerRepository(sourceDefinition.getDockerRepository())
        .dockerImageTag(sourceDefinition.getDockerImageTag())
        .documentationUrl(new URI(sourceDefinition.getDocumentationUrl()))
        .icon(SourceDefinitionsHandler.loadIcon(sourceDefinition.getIcon()))
        .releaseStage(ReleaseStage.fromValue(sourceDefinition.getReleaseStage().value()))
        .releaseDate(LocalDate.parse(sourceDefinition.getReleaseDate()))
        .resourceRequirements(new io.airbyte.api.model.generated.ActorDefinitionResourceRequirements()
            ._default(new io.airbyte.api.model.generated.ResourceRequirements()
                .cpuRequest(sourceDefinition.getResourceRequirements().getDefault().getCpuRequest()))
            .jobSpecific(Collections.emptyList()));

    final PrivateSourceDefinitionRead expectedPrivateSourceDefinitionRead =
        new PrivateSourceDefinitionRead().sourceDefinition(expectedSourceDefinitionRead).granted(true);

    final PrivateSourceDefinitionRead actualPrivateSourceDefinitionRead =
        sourceDefinitionsHandler.grantSourceDefinitionToWorkspace(
            new SourceDefinitionIdWithWorkspaceId()
                .sourceDefinitionId(sourceDefinition.getSourceDefinitionId())
                .workspaceId(workspaceId));

    assertEquals(expectedPrivateSourceDefinitionRead, actualPrivateSourceDefinitionRead);
    verify(configRepository).writeActorDefinitionWorkspaceGrant(
        sourceDefinition.getSourceDefinitionId(),
        workspaceId);
  }

  @Test
  @DisplayName("revokeSourceDefinitionFromWorkspace should correctly delete a workspace grant")
  void testRevokeSourceDefinitionFromWorkspace() throws IOException {
    sourceDefinitionsHandler.revokeSourceDefinitionFromWorkspace(new SourceDefinitionIdWithWorkspaceId()
        .sourceDefinitionId(sourceDefinition.getSourceDefinitionId())
        .workspaceId(workspaceId));
    verify(configRepository).deleteActorDefinitionWorkspaceGrant(
        sourceDefinition.getSourceDefinitionId(),
        workspaceId);
  }

  @SuppressWarnings("TypeName")
  @Nested
  @DisplayName("listLatest")
  class listLatest {

    @Test
    @DisplayName("should return the latest list")
    void testCorrect() {
      final ConnectorRegistrySourceDefinition registrySourceDefinition = new ConnectorRegistrySourceDefinition()
          .withSourceDefinitionId(UUID.randomUUID())
          .withName("some-source")
          .withDocumentationUrl("https://airbyte.com")
          .withDockerRepository("dockerrepo")
          .withDockerImageTag("1.2.4")
          .withIcon("source.svg")
          .withSpec(new ConnectorSpecification().withConnectionSpecification(
              Jsons.jsonNode(ImmutableMap.of("key", "val"))))
          .withTombstone(false)
          .withReleaseStage(io.airbyte.config.ReleaseStage.ALPHA)
          .withReleaseDate(TODAY_DATE_STRING)
          .withResourceRequirements(new ActorDefinitionResourceRequirements().withDefault(new ResourceRequirements().withCpuRequest("2")));
      when(githubStore.getSourceDefinitions()).thenReturn(Collections.singletonList(registrySourceDefinition));

      final var sourceDefinitionReadList = sourceDefinitionsHandler.listLatestSourceDefinitions().getSourceDefinitions();
      assertEquals(1, sourceDefinitionReadList.size());

      final var sourceDefinitionRead = sourceDefinitionReadList.get(0);
      assertEquals(
          SourceDefinitionsHandler.buildSourceDefinitionRead(ConnectorRegistryConverters.toStandardSourceDefinition(registrySourceDefinition)),
          sourceDefinitionRead);
    }

    @Test
    @DisplayName("returns empty collection if cannot find latest definitions")
    void testHttpTimeout() {
      assertEquals(0, sourceDefinitionsHandler.listLatestSourceDefinitions().getSourceDefinitions().size());
    }

    @Test
    @DisplayName("Icon should be an SVG icon")
    void testIconHoldsData() {
      final String icon = SourceDefinitionsHandler.loadIcon(sourceDefinition.getIcon());
      assertNotNull(icon);
      assertTrue(icon.contains("<svg"));
    }

  }

}
