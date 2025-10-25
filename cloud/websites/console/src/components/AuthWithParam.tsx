import React from "react";
import LoginOrSignup from "../pages/AuthPage";
import {
  useLocation,
  useSearchParams,
  Navigate,
} from "react-router-dom";

const paramName = "client_id"

function AddClientQueryParam({
  children,
  client_id,
}: {
  children: React.ReactNode;
  client_id: string;
}) {
  const location = useLocation();
  const [searchParams] = useSearchParams();

  // Check if the param is missing.
  if (!searchParams.has(paramName)) {
    // Create new search params, preserving any existing ones
    const newSearchParams = new URLSearchParams(searchParams.toString());
    newSearchParams.set(paramName, client_id);

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

interface AuthWithParamProps {
  client_id: string;
}

export const AuthWithParam = ({ client_id }: AuthWithParamProps) => {
  return (
    <AddClientQueryParam client_id={client_id}>
      <LoginOrSignup />
    </AddClientQueryParam>
  );
};

export default AuthWithParam;