/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.oauth;

import static com.fasterxml.jackson.databind.node.JsonNodeType.OBJECT;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import io.airbyte.commons.json.Jsons;
import io.airbyte.config.DestinationOAuthParameter;
import io.airbyte.config.SourceOAuthParameter;
import io.airbyte.config.persistence.ConfigNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth params.
 */
public class MoreOAuthParameters {

  private static final Logger LOGGER = LoggerFactory.getLogger(Jsons.class);
  public static final String SECRET_MASK = "******";

  /**
   * Get source OAuth param from stream. If the boolean is set, throws when there's a row in the
   * actor_oauth_params table that corresponds to the definitionId & workspaceId.
   *
   * @param stream oauth param stream
   * @param workspaceId workspace id
   * @param sourceDefinitionId source definition id
   * @param throwIfOverridePresent Throw if we find an override oauth param?
   * @return oauth params
   */
  public static Optional<SourceOAuthParameter> getSourceOAuthParameter(final Stream<SourceOAuthParameter> stream,
                                                                       final UUID workspaceId,
                                                                       final UUID sourceDefinitionId,
                                                                       final boolean throwIfOverridePresent)
      throws ConfigNotFoundException {

    Optional<SourceOAuthParameter> sourceOAuthParameter = stream
        .filter(p -> sourceDefinitionId.equals(p.getSourceDefinitionId()))
        .filter(p -> p.getWorkspaceId() == null || workspaceId.equals(p.getWorkspaceId()))
        // we prefer params specific to a workspace before global ones (ie workspace is null)
        .min(Comparator.comparing(SourceOAuthParameter::getWorkspaceId, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(SourceOAuthParameter::getOauthParameterId));

    if (throwIfOverridePresent) {
      if (sourceOAuthParameter.filter(param -> param.getWorkspaceId() != null).isPresent()) {
        throw new ConfigNotFoundException("OAuthParamOverride", String.format("[%s] [%s]", workspaceId, sourceDefinitionId));
      }
    }
    return sourceOAuthParameter;
  }

  /**
   * Get destination OAuth param from stream. If the boolean is set, throws when there's a row in the
   * actor_oauth_params table that corresponds to the definitionId & workspaceId.
   *
   * @param stream oauth param stream
   * @param workspaceId workspace id
   * @param destinationDefinitionId destination definition id
   * @param throwIfOverridePresent Throw if we find an override oauth param?
   * @return oauth params
   */
  public static Optional<DestinationOAuthParameter> getDestinationOAuthParameter(
                                                                                 final Stream<DestinationOAuthParameter> stream,
                                                                                 final UUID workspaceId,
                                                                                 final UUID destinationDefinitionId,
                                                                                 boolean throwIfOverridePresent)
      throws ConfigNotFoundException {
    Optional<DestinationOAuthParameter> destinationOAuthParameter = stream
        .filter(p -> destinationDefinitionId.equals(p.getDestinationDefinitionId()))
        .filter(p -> p.getWorkspaceId() == null || workspaceId.equals(p.getWorkspaceId()))
        // we prefer params specific to a workspace before global ones (ie workspace is null)
        .min(Comparator.comparing(DestinationOAuthParameter::getWorkspaceId, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(DestinationOAuthParameter::getOauthParameterId));

    if (throwIfOverridePresent) {
      if (destinationOAuthParameter.filter(param -> param.getWorkspaceId() != null).isPresent()) {
        throw new ConfigNotFoundException("OAuthParamOverride", String.format("[%s] [%s]", workspaceId, destinationDefinitionId));
      }
    }
    return destinationOAuthParameter;
  }

  /**
   * Flatten config.
   *
   * @param config to flatten
   * @return flattened config
   */
  public static JsonNode flattenOAuthConfig(final JsonNode config) {
    if (config.getNodeType() == OBJECT) {
      return flattenOAuthConfig((ObjectNode) Jsons.emptyObject(), (ObjectNode) config);
    } else {
      throw new IllegalStateException("Config is not an Object config, unable to flatten");
    }
  }

  private static ObjectNode flattenOAuthConfig(final ObjectNode flatConfig, final ObjectNode configToFlatten) {
    final List<String> keysToFlatten = new ArrayList<>();
    for (final String key : Jsons.keys(configToFlatten)) {
      JsonNode currentNodeValue = configToFlatten.get(key);
      if (isSecretNode(currentNodeValue) && !flatConfig.has(key)) {
        // _secret keys are objects but we want to preserve them.
        flatConfig.set(key, currentNodeValue);
      } else if (currentNodeValue.getNodeType() == OBJECT) {
        keysToFlatten.add(key);
      } else if (!flatConfig.has(key)) {
        flatConfig.set(key, currentNodeValue);
      } else {
        LOGGER.debug("configToFlatten: {}", configToFlatten);
        throw new IllegalStateException(String.format("OAuth Config's key '%s' already exists", key));
      }
    }
    keysToFlatten.forEach(key -> flattenOAuthConfig(flatConfig, (ObjectNode) configToFlatten.get(key)));
    return flatConfig;
  }

  private static boolean isSecretNode(JsonNode node) {
    JsonNode secretNode = node.get("_secret");
    return secretNode != null;
  }

  /**
   * Merge JSON configs.
   *
   * @param mainConfig original config
   * @param fromConfig config with overwrites
   * @return merged config
   */
  public static JsonNode mergeJsons(final ObjectNode mainConfig, final ObjectNode fromConfig) {
    for (final String key : Jsons.keys(fromConfig)) {
      if (fromConfig.get(key).getNodeType() == OBJECT) {
        // nested objects are merged rather than overwrite the contents of the equivalent object in config
        if (mainConfig.get(key) == null) {
          mergeJsons(mainConfig.putObject(key), (ObjectNode) fromConfig.get(key));
        } else if (mainConfig.get(key).getNodeType() == OBJECT) {
          mergeJsons((ObjectNode) mainConfig.get(key), (ObjectNode) fromConfig.get(key));
        } else {
          throw new IllegalStateException("Can't merge an object node into a non-object node!");
        }
      } else {
        if (!mainConfig.has(key) || isSecretMask(mainConfig.get(key).asText())) {
          LOGGER.debug(String.format("injecting instance wide parameter %s into config", key));
          mainConfig.set(key, fromConfig.get(key));
        }
      }
    }
    return mainConfig;
  }

  private static boolean isSecretMask(final String input) {
    return Strings.isNullOrEmpty(input.replaceAll("\\*", ""));
  }

}
