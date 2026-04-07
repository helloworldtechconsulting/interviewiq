// Candidate interview room layout — minimal, no employer navigation
import { Outlet } from "react-router-dom";
import { BrainCircuit } from "lucide-react";

export function InterviewLayout() {
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="flex h-14 items-center border-b px-6">
        <div className="flex items-center gap-2">
          <BrainCircuit className="h-5 w-5 text-primary" />
          <span className="font-semibold">InterviewIQ</span>
        </div>
        <div className="ml-auto text-sm text-muted-foreground">
          Candidate Interview Portal
        </div>
      </header>

      <main className="flex-1">
        <Outlet />
      </main>
    </div>
  );
}
