import { createFileRoute } from "@tanstack/react-router";
import { Button } from "@/ui";

/**
 * Only same-site paths are returned to after sign-in. Anything else goes to the home page. Browsers read a
 * backslash as a slash, so `/\evil.com` is as dangerous as `//evil.com` and is refused too.
 */
function safeNext(next: string | undefined): string {
  const same = next !== undefined && next.startsWith("/") && !next.startsWith("//") && !next.includes("\\");
  return same ? next : "/";
}

export const Route = createFileRoute("/login")({
  validateSearch: (search: Record<string, unknown>): { next?: string } => ({
    next: typeof search.next === "string" ? search.next : undefined,
  }),
  component: LoginPage,
});

function LoginPage() {
  const { next } = Route.useSearch();
  const target = safeNext(next);
  return (
    <main className="flex min-h-dvh items-center justify-center p-4">
      <section className="flex w-full max-w-sm flex-col gap-4 rounded-card border border-border p-6">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold text-fg">Sign in to EmitKit</h1>
          <p className="text-sm text-fg-muted">EmitKit uses your GitHub account to read your organizations and repositories.</p>
        </div>
        <Button
          onClick={() => {
            window.location.href = `/oauth2/authorization/github?next=${encodeURIComponent(target)}`;
          }}
        >
          Sign in with GitHub
        </Button>
      </section>
    </main>
  );
}
