/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.config.persistence;

import io.airbyte.config.ActorDefinitionVersion;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.persistence.version_overrides.DefaultDefinitionVersionOverrideProvider;
import io.airbyte.config.persistence.version_overrides.LocalDefinitionVersionOverrideProvider;
import io.airbyte.config.persistence.version_overrides.OverrideTargetType;
import io.airbyte.featureflag.ConnectorVersionOverridesEnabled;
import io.airbyte.featureflag.FeatureFlagClient;
import io.airbyte.featureflag.Workspace;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for retrieving the actor definition version to use when running a connector. This
 * should be used when a specific actor or workspace is present, rather than accessing the fields
 * directly on the definitions.
 */
@Singleton
public class ActorDefinitionVersionHelper {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDefinitionVersionOverrideProvider.class);

  private final LocalDefinitionVersionOverrideProvider localOverrideProvider;
  private final FeatureFlagClient featureFlagClient;

  public ActorDefinitionVersionHelper(final LocalDefinitionVersionOverrideProvider localOverrideProvider, final FeatureFlagClient featureFlagClient) {
    this.localOverrideProvider = localOverrideProvider;
    this.featureFlagClient = featureFlagClient;
    LOGGER.info("ActorDefinitionVersionHelper initialized with {}", localOverrideProvider.getClass().getSimpleName());
  }

  private ActorDefinitionVersion getDefaultSourceVersion(final StandardSourceDefinition sourceDefinition) {
    return new ActorDefinitionVersion()
        .withDockerRepository(sourceDefinition.getDockerRepository())
        .withDockerImageTag(sourceDefinition.getDockerImageTag())
        .withSpec(sourceDefinition.getSpec());
  }

  private ActorDefinitionVersion getDefaultDestinationVersion(final StandardDestinationDefinition destinationDefinition) {
    return new ActorDefinitionVersion()
        .withDockerRepository(destinationDefinition.getDockerRepository())
        .withDockerImageTag(destinationDefinition.getDockerImageTag())
        .withSpec(destinationDefinition.getSpec());
  }

  /**
   * Get the actor definition version to use for a source.
   *
   * @param sourceDefinition source definition
   * @param workspaceId workspace id
   * @param actorId source id
   * @return actor definition version
   */
  public ActorDefinitionVersion getSourceVersion(final StandardSourceDefinition sourceDefinition,
                                                 final UUID workspaceId,
                                                 @Nullable final UUID actorId) {
    final ActorDefinitionVersion defaultVersion = getDefaultSourceVersion(sourceDefinition);

    if (!featureFlagClient.boolVariation(ConnectorVersionOverridesEnabled.INSTANCE, new Workspace(workspaceId))) {
      return defaultVersion;
    }

    Optional<ActorDefinitionVersion> localOverride = Optional.empty();

    if (actorId != null) {
      localOverride = localOverrideProvider.getOverride(sourceDefinition.getSourceDefinitionId(), actorId, OverrideTargetType.ACTOR, defaultVersion);
    }

    if (localOverride.isEmpty()) {
      localOverride =
          localOverrideProvider.getOverride(sourceDefinition.getSourceDefinitionId(), workspaceId, OverrideTargetType.WORKSPACE, defaultVersion);
    }

    return localOverride.orElse(defaultVersion);

  }

  /**
   * Get the actor definition version to use for sources in a given workspace.
   *
   * @param sourceDefinition source definition
   * @param workspaceId workspace id
   * @return actor definition version
   */
  public ActorDefinitionVersion getSourceVersion(final StandardSourceDefinition sourceDefinition, final UUID workspaceId) {
    return getSourceVersion(sourceDefinition, workspaceId, null);
  }

  /**
   * Get the actor definition version to use for a destination.
   *
   * @param destinationDefinition destination definition
   * @param workspaceId workspace id
   * @param actorId destination id
   * @return actor definition version
   */
  public ActorDefinitionVersion getDestinationVersion(final StandardDestinationDefinition destinationDefinition,
                                                      final UUID workspaceId,
                                                      @Nullable final UUID actorId) {
    final ActorDefinitionVersion defaultVersion = getDefaultDestinationVersion(destinationDefinition);

    if (!featureFlagClient.boolVariation(ConnectorVersionOverridesEnabled.INSTANCE, new Workspace(workspaceId))) {
      return defaultVersion;
    }

    Optional<ActorDefinitionVersion> localOverride = Optional.empty();

    if (actorId != null) {
      localOverride =
          localOverrideProvider.getOverride(destinationDefinition.getDestinationDefinitionId(), actorId, OverrideTargetType.ACTOR, defaultVersion);
    }

    if (localOverride.isEmpty()) {
      localOverride = localOverrideProvider.getOverride(destinationDefinition.getDestinationDefinitionId(), workspaceId, OverrideTargetType.WORKSPACE,
          defaultVersion);
    }

    return localOverride.orElse(defaultVersion);
  }

  /**
   * Get the actor definition version to use for destinations in a given workspace.
   *
   * @param destinationDefinition destination definition
   * @param workspaceId workspace id
   * @return actor definition version
   */
  public ActorDefinitionVersion getDestinationVersion(final StandardDestinationDefinition destinationDefinition,
                                                      final UUID workspaceId) {
    return getDestinationVersion(destinationDefinition, workspaceId, null);
  }

}
