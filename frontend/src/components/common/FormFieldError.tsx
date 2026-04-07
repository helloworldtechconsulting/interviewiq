// =============================================================================
// FormFieldError — renders a validation message below a form field
// =============================================================================

interface FormFieldErrorProps {
  message?: string;
}

/**
 * Renders a small red error message for use inside form field wrappers.
 * Renders nothing when `message` is undefined/empty.
 *
 * @example
 * <FormFieldError message={errors.email?.message} />
 */
export function FormFieldError({ message }: FormFieldErrorProps) {
  if (!message) return null;
  return <p className="text-xs text-destructive">{message}</p>;
}
