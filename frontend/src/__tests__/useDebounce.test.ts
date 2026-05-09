import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";

// Inlined hook so this test does not depend on the location of the real
// useDebounce hook in the codebase. Once the user confirms the hook's path,
// replace the inlined version with `import { useDebounce } from "@/hooks/useDebounce";`.
import { useState, useEffect } from "react";
function useDebounce<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return debounced;
}

describe("useDebounce", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("returns the initial value immediately", () => {
    const { result } = renderHook(() => useDebounce("hello", 200));
    expect(result.current).toBe("hello");
  });

  it("delays updating until the timeout elapses", () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounce(value, 300),
      { initialProps: { value: "first" } },
    );

    rerender({ value: "second" });
    expect(result.current).toBe("first");

    act(() => vi.advanceTimersByTime(299));
    expect(result.current).toBe("first");

    act(() => vi.advanceTimersByTime(1));
    expect(result.current).toBe("second");
  });
});
