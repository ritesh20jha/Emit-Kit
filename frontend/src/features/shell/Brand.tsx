/** The EmitKit wordmark: a cobalt mark (a spec block emitting three lines) beside the name. Decorative; the link names it. */
export function Brand() {
  return (
    <span className="flex items-center gap-2">
      <svg aria-hidden="true" viewBox="0 0 24 24" className="size-6 shrink-0">
        <rect width="24" height="24" rx="6" className="fill-accent" />
        <path d="M7 7.5h4.5M7 12h7M7 16.5h10" strokeWidth="2" strokeLinecap="round" className="stroke-accent-fg" />
      </svg>
      <span className="font-display text-base font-semibold tracking-tight text-fg">EmitKit</span>
    </span>
  );
}
