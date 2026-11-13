import { createFileRoute } from "@tanstack/react-router";
import { Button } from "@/ui";

/** Only same-site paths are returned to after sign-in. A backslash counts too: browsers read it as a slash. */
function safeNext(next: string | undefined): string {
  const same = next !== undefined && next.startsWith("/") && !next.startsWith("//") && !next.includes("\\");
  return same ? next : "/";
}

export const Route = createFileRoute("/login")({
  // Spring Security redirects to /login?error when the GitHub round trip fails.
  validateSearch: (search: Record<string, unknown>): { next?: string; error?: true } => ({
    next: typeof search.next === "string" ? search.next : undefined,
    error: search.error === undefined ? undefined : true,
  }),
  component: LoginPage,
});

function LoginPage() {
  const { next, error } = Route.useSearch();
  const target = safeNext(next);
  return (
    <main className="flex min-h-dvh items-center justify-center p-4">
      <section className="flex w-full max-w-sm flex-col gap-4 rounded-card border border-border p-6">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold text-fg">Sign in to EmitKit</h1>
          <p className="text-sm text-fg-muted">EmitKit uses your GitHub account to read your organizations and repositories.</p>
        </div>
        {error && <p role="alert" className="text-sm text-status-failed">Sign-in didn&apos;t complete. Try again.</p>}
        <Button onClick={() => { window.location.href = `/oauth2/authorization/github?next=${encodeURIComponent(target)}`; }}>
          Sign in with GitHub
        </Button>
      </section>
    </main>
  );
}
