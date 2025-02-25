import React, { Suspense, useMemo } from "react";
import { useIntl } from "react-intl";
import { Outlet, useNavigate, useParams } from "react-router-dom";

import { LoadingPage } from "components";
import { ApiErrorBoundary } from "components/common/ApiErrorBoundary";
import { HeadTitle } from "components/common/HeadTitle";
import { ConnectorNavigationTabs } from "components/connector/ConnectorNavigationTabs";
import { ItemTabs, StepsTypes } from "components/ConnectorBlocks";
import { Breadcrumbs } from "components/ui/Breadcrumbs";
import { PageHeader } from "components/ui/PageHeader";

import { useTrackPage, PageTrackingCodes } from "hooks/services/Analytics";
import { useAppMonitoringService } from "hooks/services/AppMonitoringService";
import { useExperiment } from "hooks/services/Experiment";
import { useGetDestination } from "hooks/services/useDestinationHook";
import { ResourceNotFoundErrorBoundary } from "views/common/ResourceNotFoundErrorBoundary";
import { StartOverErrorView } from "views/common/StartOverErrorView";
import { ConnectorDocumentationWrapper } from "views/Connector/ConnectorDocumentationLayout";

export const DestinationItemPage: React.FC = () => {
  useTrackPage(PageTrackingCodes.DESTINATION_ITEM);
  const params = useParams() as { "*": StepsTypes | ""; id: string };
  const navigate = useNavigate();
  const { formatMessage } = useIntl();
  const isNewConnectionFlowEnabled = useExperiment("connection.updatedConnectionFlow", false);
  const currentStep = useMemo<string>(() => (params["*"] === "" ? StepsTypes.OVERVIEW : params["*"]), [params]);
  const { trackError } = useAppMonitoringService();

  const destination = useGetDestination(params.id);

  const onSelectStep = (id: string) => {
    const path = id === StepsTypes.OVERVIEW ? "." : id.toLowerCase();
    navigate(path);
  };

  const breadcrumbsData = [
    {
      label: formatMessage({ id: "admin.destinations" }),
      to: "..",
    },
    { label: destination.name },
  ];

  return (
    <ResourceNotFoundErrorBoundary errorComponent={<StartOverErrorView />} trackError={trackError}>
      <ConnectorDocumentationWrapper>
        <HeadTitle titles={[{ id: "admin.destinations" }, { title: destination.name }]} />
        <PageHeader
          title={<Breadcrumbs data={breadcrumbsData} />}
          middleComponent={
            !isNewConnectionFlowEnabled && <ItemTabs currentStep={currentStep} setCurrentStep={onSelectStep} />
          }
        />
        {isNewConnectionFlowEnabled && <ConnectorNavigationTabs connectorType="destination" />}

        <Suspense fallback={<LoadingPage />}>
          <ApiErrorBoundary>
            <Outlet context={{ destination }} />
          </ApiErrorBoundary>
        </Suspense>
      </ConnectorDocumentationWrapper>
    </ResourceNotFoundErrorBoundary>
  );
};
