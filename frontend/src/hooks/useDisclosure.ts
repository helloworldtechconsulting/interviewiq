// =============================================================================
// useDisclosure — boolean open/close state helper (dialogs, drawers, menus)
// =============================================================================

import { useCallback, useState } from "react";

interface UseDisclosureReturn {
  isOpen: boolean;
  open: () => void;
  close: () => void;
  toggle: () => void;
  onOpenChange: (open: boolean) => void;
}

/**
 * Manages open/close boolean state with stable callbacks.
 *
 * @example
 * const { isOpen, open, close, onOpenChange } = useDisclosure();
 * return <Dialog open={isOpen} onOpenChange={onOpenChange}>...</Dialog>
 */
export function useDisclosure(defaultOpen = false): UseDisclosureReturn {
  const [isOpen, setIsOpen] = useState(defaultOpen);

  const open = useCallback(() => setIsOpen(true), []);
  const close = useCallback(() => setIsOpen(false), []);
  const toggle = useCallback(() => setIsOpen((v) => !v), []);
  const onOpenChange = useCallback((v: boolean) => setIsOpen(v), []);

  return { isOpen, open, close, toggle, onOpenChange };
}
