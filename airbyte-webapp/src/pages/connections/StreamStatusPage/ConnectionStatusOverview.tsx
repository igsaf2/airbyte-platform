import dayjs from "dayjs";
import { FormattedMessage } from "react-intl";

import {
  ConnectionStatusIndicatorStatus,
  ConnectionStatusIndicator,
} from "components/connection/ConnectionStatusIndicator";
import { useConnectionSyncContext } from "components/connection/ConnectionSync/ConnectionSyncContext";
import {
  useErrorMultiplierExperiment,
  useLateMultiplierExperiment,
} from "components/connection/StreamStatus/streamStatusUtils";
import { Status as ConnectionSyncStatus } from "components/EntityTable/types";
import { Box } from "components/ui/Box";
import { FlexContainer } from "components/ui/Flex";
import { Text } from "components/ui/Text";

import { ConnectionScheduleType, WebBackendConnectionRead } from "core/request/AirbyteClient";
import { useSchemaChanges } from "hooks/connection/useSchemaChanges";
import { useConnectionEditService } from "hooks/services/ConnectionEdit/ConnectionEditService";

const MESSAGE_BY_STATUS: Readonly<Record<ConnectionStatusIndicatorStatus, string>> = {
  onTime: "connection.status.onTime",
  late: "connection.status.late",
  pending: "connection.status.pending",
  error: "connection.status.error",
  actionRequired: "connection.status.actionRequired",
  disabled: "connection.status.disabled",
  cancelled: "connection.status.cancelled",
};

const isUnscheduledConnection = (scheduleType: ConnectionScheduleType | undefined) =>
  ["cron", "manual", undefined].includes(scheduleType);

// `late` here refers to how long past the last successful sync until it is flagged
const isConnectionLate = (
  connection: WebBackendConnectionRead,
  lastSuccessfulSync: number | undefined,
  lateMultiplier: number
) => {
  if (lastSuccessfulSync === undefined) {
    return false;
  }

  return (
    connection.scheduleType &&
    !["cron", "manual"].includes(connection.scheduleType) &&
    connection.scheduleData?.basicSchedule?.units &&
    (lastSuccessfulSync ?? 0) * 1000 < // x1000 for a JS datetime
      dayjs()
        // Subtract 2x (default of lateMultiplier) the scheduled interval and compare it to last sync time
        .subtract(
          connection.scheduleData.basicSchedule.units * lateMultiplier,
          connection.scheduleData.basicSchedule.timeUnit
        )
        .valueOf()
  );
};

const useConnectionStatus = () => {
  const lateMultiplier = useLateMultiplierExperiment();
  const errorMultiplier = useErrorMultiplierExperiment();

  // Disabling the schema changes as it's the only actionable error and is per-connection, not stream
  const { connection } = useConnectionEditService();
  const { hasBreakingSchemaChange } = useSchemaChanges(connection.schemaChange);

  const { connectionEnabled, connectionStatus, lastSuccessfulSync } = useConnectionSyncContext();

  if (!connectionEnabled) {
    return ConnectionStatusIndicatorStatus.Disabled;
  }

  if (connectionStatus === ConnectionSyncStatus.EMPTY) {
    return ConnectionStatusIndicatorStatus.Pending;
  }

  // The `error` value is based on the `connection.streamCentricUI.errorMultiplyer` experiment
  if (
    !hasBreakingSchemaChange &&
    connectionStatus === ConnectionSyncStatus.FAILED &&
    (isUnscheduledConnection(connection.scheduleType) ||
      isConnectionLate(connection, lastSuccessfulSync, errorMultiplier))
  ) {
    return ConnectionStatusIndicatorStatus.Error;
  }

  if (hasBreakingSchemaChange && isConnectionLate(connection, lastSuccessfulSync, lateMultiplier)) {
    return ConnectionStatusIndicatorStatus.ActionRequired;
  }

  if (connectionStatus === ConnectionSyncStatus.CANCELLED) {
    return ConnectionStatusIndicatorStatus.Cancelled;
  }

  // The `late` value is based on the `connection.streamCentricUI.late` experiment
  if (isConnectionLate(connection, lastSuccessfulSync, lateMultiplier)) {
    return ConnectionStatusIndicatorStatus.Late;
  }

  return ConnectionStatusIndicatorStatus.OnTime;
};

export const ConnectionStatusOverview: React.FC = () => {
  const { nextSync, jobSyncRunning, jobResetRunning } = useConnectionSyncContext();

  const isLoading = jobSyncRunning || jobResetRunning;

  const status = useConnectionStatus();

  return (
    <FlexContainer alignItems="center" gap="sm">
      <ConnectionStatusIndicator status={status} withBox loading={isLoading} />
      <Box ml="md">
        <FormattedMessage id={MESSAGE_BY_STATUS[status]} />
        <Box as="span" ml="md">
          <Text color="grey" bold size="sm" as="span">
            {status === ConnectionStatusIndicatorStatus.OnTime && nextSync && (
              <FormattedMessage id="connection.stream.status.nextSync" values={{ sync: nextSync.fromNow() }} />
            )}
            {status === ConnectionStatusIndicatorStatus.Late && nextSync && (
              <FormattedMessage id="connection.stream.status.nextTry" values={{ sync: nextSync.fromNow() }} />
            )}
          </Text>
        </Box>
      </Box>
    </FlexContainer>
  );
};
