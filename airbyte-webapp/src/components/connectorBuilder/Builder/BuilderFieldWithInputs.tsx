import { faPlus, faUser } from "@fortawesome/free-solid-svg-icons";
import { FontAwesomeIcon } from "@fortawesome/react-fontawesome";
import { useField } from "formik";
import { useMemo, useState } from "react";
import React from "react";
import { FormattedMessage } from "react-intl";

import { FlexContainer } from "components/ui/Flex";
import { ListBox, ListBoxControlButtonProps, Option } from "components/ui/ListBox";
import { Text } from "components/ui/Text";
import { Tooltip } from "components/ui/Tooltip";

import { useConnectorBuilderFormState } from "services/connectorBuilder/ConnectorBuilderStateService";

import { BuilderField, BuilderFieldProps } from "./BuilderField";
import styles from "./BuilderFieldWithInputs.module.scss";
import { InputForm, newInputInEditing } from "./InputsForm";
import { useInferredInputs } from "../useInferredInputs";

export const BuilderFieldWithInputs: React.FC<BuilderFieldProps> = (props) => {
  const [field, , helpers] = useField(props.path);

  return (
    <BuilderField
      {...props}
      adornment={<UserInputHelper setValue={helpers.setValue} currentValue={field.value} />}
      className={styles.inputWithHelper}
    />
  );
};

interface UserInputHelperProps {
  setValue: (value: string) => void;
  currentValue: string;
}

export const UserInputHelper = (props: UserInputHelperProps) => {
  const { builderFormValues } = useConnectorBuilderFormState();
  const inferredInputs = useInferredInputs();
  const listOptions = useMemo(() => {
    const options: Array<Option<string | undefined>> = [...builderFormValues.inputs, ...inferredInputs].map(
      (input) => ({
        label: input.definition.title || input.key,
        value: input.key,
      })
    );
    return options;
  }, [builderFormValues.inputs, inferredInputs]);
  return <InnerUserInputHelper {...props} listOptions={listOptions} />;
};

const InnerUserInputHelper = React.memo(
  ({
    setValue,
    currentValue,
    listOptions,
  }: UserInputHelperProps & { listOptions: Array<Option<string | undefined>> }) => {
    const [modalOpen, setModalOpen] = useState(false);
    return (
      <>
        <ListBox<string | undefined>
          buttonClassName={styles.button}
          optionClassName={styles.option}
          className={styles.container}
          selectedOptionClassName={styles.selectedOption}
          controlButton={UserInputHelperControlButton}
          selectedValue={undefined}
          onSelect={(selectedValue) => {
            if (selectedValue) {
              setValue(`${currentValue || ""}{{ config['${selectedValue}'] }}`);
            }
          }}
          options={listOptions}
          footerOption={
            <button
              type="button"
              onClick={() => {
                // This hack is necessary because listbox will put the focus back when the option list gets hidden, which conflicts with the auto-focus setting of the modal.
                // As it's not possible to prevent listbox from forcing the focus back on the button component, this will wait until the focus went to the button, then opens the modal
                // so it can move it to the first input
                setTimeout(() => {
                  setModalOpen(true);
                }, 50);
              }}
              className={styles.newInput}
            >
              <Text as="div">
                <FlexContainer alignItems="center">
                  <FontAwesomeIcon icon={faPlus} />
                  <FormattedMessage id="connectorBuilder.inputModal.newTitle" />
                </FlexContainer>
              </Text>
            </button>
          }
        />
        {modalOpen && (
          <InputForm
            inputInEditing={newInputInEditing()}
            onClose={(newInput) => {
              setModalOpen(false);
              if (!newInput) {
                return;
              }
              setValue(`${currentValue || ""}{{ config['${newInput.key}'] }}`);
            }}
          />
        )}
      </>
    );
  }
);

const UserInputHelperControlButton: React.FC<ListBoxControlButtonProps<string | undefined>> = () => {
  return (
    <Tooltip
      control={
        <div className={styles.buttonContent}>
          {"{{"}
          <FontAwesomeIcon icon={faUser} />
          {"}}"}
        </div>
      }
      placement="top"
    >
      <FormattedMessage id="connectorBuilder.interUserInputValue" />
    </Tooltip>
  );
};
