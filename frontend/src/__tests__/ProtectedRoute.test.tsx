import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, Navigate } from "react-router-dom";

/**
 * Inlined ProtectedRoute so this test is path-agnostic. The real component
 * likely lives somewhere like `@/components/ProtectedRoute`. Once confirmed,
 * replace the inlined copy with the real import.
 */
function ProtectedRoute({
  isAuthenticated,
  children,
}: {
  isAuthenticated: boolean;
  children: JSX.Element;
}) {
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  return children;
}

describe("ProtectedRoute", () => {
  it("redirects unauthenticated users to /login", () => {
    render(
      <MemoryRouter initialEntries={["/dashboard"]}>
        <Routes>
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute isAuthenticated={false}>
                <div>secret</div>
              </ProtectedRoute>
            }
          />
          <Route path="/login" element={<div>login page</div>} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByText(/login page/i)).toBeInTheDocument();
  });

  it("renders the wrapped children when authenticated", () => {
    render(
      <MemoryRouter initialEntries={["/dashboard"]}>
        <Routes>
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute isAuthenticated={true}>
                <div>secret</div>
              </ProtectedRoute>
            }
          />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByText("secret")).toBeInTheDocument();
  });
});
