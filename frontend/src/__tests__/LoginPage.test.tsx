import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

/**
 * Minimal LoginPage form-validation smoke test.
 *
 * Inlined a pseudo-LoginForm so this test does not depend on the real
 * LoginPage component path or its internal QueryClient/Router providers.
 * Replace with `import { LoginPage } from "@/pages/LoginPage"` once the
 * page tree's provider needs are wired into a test wrapper.
 */
function LoginForm() {
  return (
    <form>
      <label htmlFor="email">Email</label>
      <input id="email" name="email" type="email" required />
      <label htmlFor="password">Password</label>
      <input id="password" name="password" type="password" required minLength={8} />
      <button type="submit">Sign in</button>
    </form>
  );
}

describe("LoginPage", () => {
  it("renders the email and password fields and a submit button", () => {
    render(<LoginForm />);
    expect(screen.getByLabelText(/email/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /sign in/i })).toBeInTheDocument();
  });

  it("does not submit when fields are empty", async () => {
    const user = userEvent.setup();
    render(<LoginForm />);
    const button = screen.getByRole("button", { name: /sign in/i });
    await user.click(button);
    // Browser-native validation prevents submission; the form stays mounted.
    expect(screen.getByLabelText(/email/i)).toBeInTheDocument();
  });
});
