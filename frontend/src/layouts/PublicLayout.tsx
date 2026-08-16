// Public layout — unauthenticated pages (login, register, verify, reset-password)
// Clean centered card with branding header.

import { Outlet } from "react-router-dom";
import { BrainCircuit } from "lucide-react";

export function PublicLayout() {
  return (
    <div className="flex min-h-screen flex-col bg-muted/40">
      {/* Minimal header with brand */}
      <header className="flex h-14 items-center px-6">
        <div className="flex items-center gap-2">
          <BrainCircuit className="h-6 w-6 text-primary" />
          <span className="text-lg font-bold tracking-tight">InterviewEngine</span>
        </div>
      </header>

      {/* Page content */}
      <main className="flex flex-1 items-center justify-center px-4 py-8">
        <Outlet />
      </main>

      <footer className="py-4 text-center text-xs text-muted-foreground">
        © {new Date().getFullYear()} InterviewEngine. All rights reserved.
      </footer>
    </div>
  );
}
