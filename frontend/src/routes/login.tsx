import { createFileRoute } from "@tanstack/react-router";
import { LogIn } from "lucide-react";
import { STEP_LABELS } from "@/features/run-logs";
import { Brand } from "@/features/shell";
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
    <main className="grid min-h-dvh grid-cols-1 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <section className="flex flex-col justify-between gap-10 px-6 py-8 sm:px-10 lg:px-16 lg:py-12">
        <Brand />
        <div className="flex w-full max-w-md flex-col gap-6">
          <div className="flex flex-col gap-3">
            <p className="font-mono text-2xs font-medium tracking-[0.06em] text-accent uppercase">OpenAPI in · SDKs out</p>
            <h1 className="text-3xl font-semibold tracking-tight text-fg">Sign in to EmitKit</h1>
            <p className="text-base text-fg-muted">EmitKit uses your GitHub account to read your organizations and repositories.</p>
          </div>
          {error && (
            <p role="alert" className="rounded-control border border-status-failed/35 bg-status-failed/8 px-3 py-2 text-sm text-status-failed">
              Sign-in didn&apos;t complete. Try again.
            </p>
          )}
          <Button className="h-10 self-start px-4" onClick={() => { window.location.href = `/oauth2/authorization/github?next=${encodeURIComponent(target)}`; }}>
            <LogIn aria-hidden="true" className="size-4" />
            Sign in with GitHub
          </Button>
        </div>
        {/* Balances the wordmark, so the form sits in the optical centre. */}
        <span aria-hidden="true" className="h-6" />
      </section>
      {/* The pipeline every run goes through: the product, shown as what it does. Decorative beside the form. */}
      <aside aria-label="The EmitKit pipeline" className="hidden border-l border-border bg-surface lg:flex lg:items-center lg:justify-center lg:p-16">
        <div className="w-full max-w-sm">
          <p className="mb-4 font-mono text-2xs font-medium tracking-[0.06em] text-fg-subtle uppercase">Every run, in order</p>
          <ol className="flex flex-col">
            {Object.values(STEP_LABELS).map((label, index, all) => (
              <li key={label} className="relative flex items-center gap-3 py-1.5">
                {index < all.length - 1 && <span aria-hidden="true" className="absolute top-6 bottom-[-6px] left-[11px] w-px bg-border-strong" />}
                <span className="relative inline-flex size-6 shrink-0 items-center justify-center rounded-full border border-border-strong bg-bg font-mono text-2xs text-fg-muted">
                  {String(index + 1).padStart(2, "0")}
                </span>
                <span className="text-sm text-fg">{label}</span>
              </li>
            ))}
          </ol>
        </div>
      </aside>
    </main>
  );
}
