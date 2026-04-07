// Full-page loading spinner — shown while auth is hydrating or data is loading
import { Loader2 } from "lucide-react";

interface LoadingPageProps {
  message?: string;
}

export function LoadingPage({ message = "Loading…" }: LoadingPageProps) {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4">
      <Loader2 className="h-10 w-10 animate-spin text-primary" />
      <p className="text-sm text-muted-foreground">{message}</p>
    </div>
  );
}
