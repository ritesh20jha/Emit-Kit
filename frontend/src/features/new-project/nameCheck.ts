import { useQuery } from "@tanstack/react-query";
import { reposQuery } from "./api";
import { CHECK_DELAY_MS, useDebouncedValue } from "./debounce";
import type { RepoMode } from "./schemas";

/**
 * The message when `owner/name` already exists on the host, else undefined. Only a new repository is
 * checked. The match is on the owner-qualified name, case-insensitively, as the name is typed.
 */
export function useNameCheck(orgId: string, owner: string, mode: RepoMode, name: string): string | undefined {
  const checked = useDebouncedValue(name.trim(), CHECK_DELAY_MS);
  const repos = useQuery({ ...reposQuery(orgId, checked), enabled: mode === "new" && checked !== "" });
  const wanted = `${owner}/${checked}`.toLowerCase();
  const match = repos.data?.find((repo) => repo.fullName.toLowerCase() === wanted);
  // A disabled query still returns what it cached, so a switch away from "new" must not keep the message.
  if (mode !== "new" || match === undefined) return undefined;
  return `${match.fullName} already exists on GitHub. Choose another name.`;
}
