import { FastField, FastFieldProps, FieldInputProps } from "formik";
import { ReactNode } from "react";
import { FormattedMessage } from "react-intl";

import { ControlLabels } from "components/LabeledControl";
import { LabeledSwitch } from "components/LabeledSwitch";
import { ComboBox, Option } from "components/ui/ComboBox";
import { DropDown } from "components/ui/DropDown";
import { Input } from "components/ui/Input";
import { TagInput } from "components/ui/TagInput";
import { Text } from "components/ui/Text";
import { TextArea } from "components/ui/TextArea";
import { InfoTooltip } from "components/ui/Tooltip/InfoTooltip";

import { FORM_PATTERN_ERROR } from "core/form/schemaToYup";

import styles from "./BuilderField.module.scss";

interface EnumFieldProps {
  options: string[];
  value: string;
  setValue: (value: string) => void;
  error: boolean;
}

interface ArrayFieldProps {
  name: string;
  value: string[];
  setValue: (value: string[]) => void;
  error: boolean;
  itemType?: string;
}

interface BaseFieldProps {
  // path to the location in the Connector Manifest schema which should be set by this component
  path: string;
  label: string;
  tooltip?: React.ReactNode;
  readOnly?: boolean;
  optional?: boolean;
  pattern?: string;
  adornment?: ReactNode;
  className?: string;
}

export type BuilderFieldProps = BaseFieldProps &
  (
    | {
        type: "string" | "number" | "integer";
        onChange?: (newValue: string) => void;
        onBlur?: (value: string) => void;
        disabled?: boolean;
      }
    | { type: "boolean"; onChange?: (newValue: boolean) => void }
    | { type: "array"; onChange?: (newValue: string[]) => void; itemType?: string }
    | { type: "textarea"; onChange?: (newValue: string[]) => void }
    | { type: "enum"; onChange?: (newValue: string) => void; options: string[] }
    | { type: "combobox"; onChange?: (newValue: string) => void; options: Option[] }
  );

const EnumField: React.FC<EnumFieldProps> = ({ options, value, setValue, error, ...props }) => {
  return (
    <DropDown
      {...props}
      options={options.map((option) => {
        return { label: option, value: option };
      })}
      onChange={(selected) => selected && setValue(selected.value)}
      value={value}
      error={error}
    />
  );
};

const ArrayField: React.FC<ArrayFieldProps> = ({ name, value, setValue, error, itemType }) => {
  return (
    <TagInput name={name} fieldValue={value} onChange={(value) => setValue(value)} itemType={itemType} error={error} />
  );
};

const InnerBuilderField: React.FC<BuilderFieldProps & FastFieldProps<unknown>> = ({
  path,
  label,
  tooltip,
  optional = false,
  readOnly,
  pattern,
  field,
  meta,
  form,
  adornment,
  ...props
}) => {
  const hasError = !!meta.error && meta.touched;

  if (props.type === "boolean") {
    return (
      <LabeledSwitch
        {...(field as FieldInputProps<string>)}
        checked={field.value as boolean}
        label={
          <>
            {label} {tooltip && <InfoTooltip placement="top-start">{tooltip}</InfoTooltip>}
          </>
        }
      />
    );
  }

  const setValue = (newValue: unknown) => {
    props.onChange?.(newValue as string & string[]);
    form.setFieldValue(path, newValue);
  };

  return (
    <ControlLabels className={styles.container} label={label} infoTooltipContent={tooltip} optional={optional}>
      {(props.type === "number" || props.type === "string" || props.type === "integer") && (
        <Input
          {...field}
          onChange={(e) => {
            field.onChange(e);
            if (e.target.value === "") {
              form.setFieldValue(path, undefined);
            }
            props.onChange?.(e.target.value);
          }}
          className={props.className}
          type={props.type}
          value={(field.value as string | number | undefined) ?? ""}
          error={hasError}
          readOnly={readOnly}
          adornment={adornment}
          disabled={props.disabled}
          onBlur={(e) => {
            field.onBlur(e);
            props.onBlur?.(e.target.value);
          }}
        />
      )}
      {props.type === "textarea" && (
        <TextArea
          {...field}
          onChange={(e) => {
            field.onChange(e);
            if (e.target.value === "") {
              form.setFieldValue(path, undefined);
            }
          }}
          className={props.className}
          value={(field.value as string) ?? ""}
          error={hasError}
          readOnly={readOnly}
          onBlur={(e) => {
            field.onBlur(e);
          }}
        />
      )}
      {props.type === "array" && (
        <div data-testid={`tag-input-${path}`}>
          <ArrayField
            name={path}
            value={(field.value as string[] | undefined) ?? []}
            itemType={props.itemType}
            setValue={setValue}
            error={hasError}
          />
        </div>
      )}
      {props.type === "enum" && (
        <EnumField
          options={props.options}
          value={field.value as string}
          setValue={setValue}
          error={hasError}
          data-testid={path}
        />
      )}
      {props.type === "combobox" && (
        <ComboBox
          options={props.options}
          value={field.value as string}
          onChange={setValue}
          error={hasError}
          adornment={adornment}
          data-testid={path}
          fieldInputProps={field}
          onBlur={(e) => {
            if (e.relatedTarget?.id.includes("headlessui-combobox-option")) {
              return;
            }
            field.onBlur(e);
          }}
          filterOptions={false}
        />
      )}
      {hasError && (
        <Text className={styles.error}>
          <FormattedMessage
            id={meta.error}
            values={meta.error === FORM_PATTERN_ERROR && pattern ? { pattern: String(pattern) } : undefined}
          />
        </Text>
      )}
    </ControlLabels>
  );
};

export const BuilderField: React.FC<BuilderFieldProps> = (props) => {
  return (
    // The key is set to enforce a re-render of the component if the type change, otherwise changes in props might not be reflected correctly
    <FastField name={props.path} key={`${props.type}_${props.label}`}>
      {({ field, form, meta }: FastFieldProps<unknown>) => (
        <InnerBuilderField {...props} field={field} form={form} meta={meta} />
      )}
    </FastField>
  );
};
