import classnames from "classnames";
import { Formik } from "formik";
import debounce from "lodash/debounce";
import React, { useCallback, useEffect, useMemo, useRef } from "react";
import { useIntl } from "react-intl";

import { HeadTitle } from "components/common/HeadTitle";
import { Builder } from "components/connectorBuilder/Builder/Builder";
import { StreamTestingPanel } from "components/connectorBuilder/StreamTestingPanel";
import { builderFormValidationSchema, BuilderFormValues } from "components/connectorBuilder/types";
import { YamlEditor } from "components/connectorBuilder/YamlEditor";
import { ResizablePanels } from "components/ui/ResizablePanels";

import { Action, Namespace } from "core/analytics";
import { useAnalyticsService } from "hooks/services/Analytics";
import { ConnectorBuilderLocalStorageProvider } from "services/connectorBuilder/ConnectorBuilderLocalStorageService";
import {
  ConnectorBuilderTestStateProvider,
  ConnectorBuilderFormStateProvider,
  useConnectorBuilderFormState,
  ConnectorBuilderFormManagementStateProvider,
  ConnectorBuilderMainFormikContext,
} from "services/connectorBuilder/ConnectorBuilderStateService";

import styles from "./ConnectorBuilderEditPage.module.scss";

// eslint-disable-next-line @typescript-eslint/no-empty-function
const noop = function () {};

const ConnectorBuilderEditPageInner: React.FC = React.memo(() => {
  const { builderFormValues, editorView, setEditorView } = useConnectorBuilderFormState();
  const analyticsService = useAnalyticsService();

  useEffect(() => {
    analyticsService.track(Namespace.CONNECTOR_BUILDER, Action.CONNECTOR_BUILDER_EDIT, {
      actionDescription: "Connector Builder UI /edit page opened",
    });
  }, [analyticsService]);

  const switchToUI = useCallback(() => setEditorView("ui"), [setEditorView]);
  const switchToYaml = useCallback(() => setEditorView("yaml"), [setEditorView]);

  const initialFormValues = useRef(builderFormValues);

  return useMemo(
    () => (
      <Formik
        initialValues={initialFormValues.current}
        validateOnBlur
        validateOnChange={false}
        validateOnMount
        onSubmit={noop}
        validationSchema={builderFormValidationSchema}
      >
        {(props) => (
          <ConnectorBuilderMainFormikContext.Provider value={props}>
            <Panels
              editorView={editorView}
              validateForm={props.validateForm}
              switchToUI={switchToUI}
              values={props.values}
              switchToYaml={switchToYaml}
            />
          </ConnectorBuilderMainFormikContext.Provider>
        )}
      </Formik>
    ),
    [editorView, switchToUI, switchToYaml]
  );
});

export const ConnectorBuilderEditPage: React.FC = () => (
  <ConnectorBuilderFormManagementStateProvider>
    <ConnectorBuilderLocalStorageProvider>
      <ConnectorBuilderFormStateProvider>
        <ConnectorBuilderTestStateProvider>
          <HeadTitle titles={[{ id: "connectorBuilder.title" }]} />
          <ConnectorBuilderEditPageInner />
        </ConnectorBuilderTestStateProvider>
      </ConnectorBuilderFormStateProvider>
    </ConnectorBuilderLocalStorageProvider>
  </ConnectorBuilderFormManagementStateProvider>
);

const Panels = React.memo(
  ({
    editorView,
    switchToUI,
    switchToYaml,
    values,
    validateForm,
  }: {
    editorView: string;
    switchToUI: () => void;
    values: BuilderFormValues;
    switchToYaml: () => void;
    validateForm: () => void;
  }) => {
    const { formatMessage } = useIntl();
    const { setBuilderFormValues } = useConnectorBuilderFormState();

    const debouncedSetBuilderFormValues = useMemo(
      () =>
        debounce((values) => {
          // kick off formik validation
          validateForm();
          // update upstream state
          setBuilderFormValues(values, builderFormValidationSchema.isValidSync(values));
        }, 200),
      [setBuilderFormValues, validateForm]
    );
    useEffect(() => {
      debouncedSetBuilderFormValues(values);
    }, [values, debouncedSetBuilderFormValues]);

    return (
      <ResizablePanels
        className={classnames({ [styles.gradientBg]: editorView === "yaml", [styles.solidBg]: editorView === "ui" })}
        firstPanel={{
          children: (
            <>
              {editorView === "yaml" ? (
                <YamlEditor toggleYamlEditor={switchToUI} />
              ) : (
                <Builder values={values} validateForm={validateForm} toggleYamlEditor={switchToYaml} />
              )}
            </>
          ),
          className: styles.leftPanel,
          minWidth: 550,
        }}
        secondPanel={{
          children: <StreamTestingPanel />,
          className: styles.rightPanel,
          flex: 0.33,
          minWidth: 60,
          overlay: {
            displayThreshold: 325,
            header: formatMessage({ id: "connectorBuilder.testConnector" }),
            rotation: "counter-clockwise",
          },
        }}
      />
    );
  }
);
