import { Field, FieldProps, Formik, Form } from "formik";
import React from "react";
import { FormattedMessage, useIntl } from "react-intl";
import { NavigateOptions, To, useNavigate } from "react-router-dom";
import * as yup from "yup";

import { LabeledInput } from "components";
import { HeadTitle } from "components/common/HeadTitle";
import { Button } from "components/ui/Button";
import { FlexContainer } from "components/ui/Flex";
import { Link } from "components/ui/Link";

import { PageTrackingCodes, useTrackPage } from "hooks/services/Analytics";
import { useQuery } from "hooks/useQuery";
import { CloudRoutes } from "packages/cloud/cloudRoutePaths";
import { FieldError } from "packages/cloud/lib/errors/FieldError";
import { useAuthService } from "packages/cloud/services/auth/AuthService";
import { BottomBlock, FieldItem } from "packages/cloud/views/auth/components/FormComponents";
import { FormTitle } from "packages/cloud/views/auth/components/FormTitle";

import styles from "./LoginPage.module.scss";
import { Disclaimer, EmailField } from "../components/FormFields/FormFields";
import { LoginSignupNavigation } from "../components/LoginSignupNavigation";
import { OAuthLogin } from "../OAuthLogin";
import { Separator } from "../SignupPage/components/Separator";

const LoginPageValidationSchema = yup.object().shape({
  email: yup.string().email("form.email.error").required("form.empty.error"),
  password: yup.string().required("form.empty.error"),
});

export const LoginPage: React.FC = () => {
  const { formatMessage } = useIntl();
  const { login } = useAuthService();
  const query = useQuery<{ from?: string }>();
  const navigate = useNavigate();
  const replace = (path: To, state?: NavigateOptions) => navigate(path, { ...state, replace: true });
  useTrackPage(PageTrackingCodes.LOGIN);

  return (
    <FlexContainer direction="column" gap="xl" className={styles.container}>
      <HeadTitle titles={[{ id: "login.login" }]} />
      <FormTitle>
        <FormattedMessage id="login.loginTitle" />
      </FormTitle>

      <OAuthLogin />
      <Separator />
      <Formik
        initialValues={{
          email: "",
          password: "",
        }}
        validationSchema={LoginPageValidationSchema}
        onSubmit={async (values, { setFieldError }) => {
          return login(values)
            .then(() => replace(query.from ?? "/"))
            .catch((err) => {
              if (err instanceof FieldError) {
                setFieldError(err.field, err.message);
              } else {
                setFieldError("password", err.message);
              }
            });
        }}
        validateOnBlur
        validateOnChange={false}
      >
        {({ isSubmitting }) => (
          <Form>
            <FieldItem>
              <EmailField />
            </FieldItem>
            <FieldItem>
              <Field name="password">
                {({ field, meta }: FieldProps<string>) => (
                  <LabeledInput
                    {...field}
                    label={<FormattedMessage id="login.yourPassword" />}
                    placeholder={formatMessage({
                      id: "login.yourPassword.placeholder",
                    })}
                    type="password"
                    error={!!meta.error && meta.touched}
                    message={meta.touched && meta.error && formatMessage({ id: meta.error })}
                    data-testid="login.password"
                  />
                )}
              </Field>
            </FieldItem>
            <BottomBlock>
              <>
                <Link
                  to={CloudRoutes.ResetPassword}
                  className={styles.forgotPassword}
                  data-testid="reset-password-link"
                >
                  <FormattedMessage id="login.forgotPassword" />
                </Link>
                <Button size="lg" type="submit" isLoading={isSubmitting} data-testid="login.submit">
                  <FormattedMessage id="login.login" />
                </Button>
              </>
            </BottomBlock>
          </Form>
        )}
      </Formik>
      <Disclaimer />
      <LoginSignupNavigation to="signup" />
    </FlexContainer>
  );
};
