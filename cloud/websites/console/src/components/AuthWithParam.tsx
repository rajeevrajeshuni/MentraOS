import React from "react";
import LoginOrSignup from "../pages/AuthPage";

const paramName = "client_id"
const paramValue = "console"

function AddClientQueryParam({
  children,
}: {
  children: React.ReactNode;
}) {
  const location = useLocation();
  const [searchParams] = useSearchParams();

  // Check if the param is missing.
  if (!searchParams.has(paramName)) {
    // Create new search params, preserving any existing ones
    const newSearchParams = new URLSearchParams(searchParams.toString());
    newSearchParams.set(paramName, paramValue);

    // Redirect to the same page but with the new param
    return (
      <Navigate
        to={{
          pathname: location.pathname,
          search: newSearchParams.toString(),
        }}
        replace
        state={location.state}
      />
    );
  }
  // Param already exists, so just render the children component
  return <>{children}</>;
}

export const AuthWithParam: React.FC = () => {
  return (
    <AddClientQueryParam>
      <LoginOrSignup />
    </AddClientQueryParam>
  );
};

export default AuthWithParam;