/** One thing the browser-side validator found. `line` is 1-based, or null when the finding has no place in the text. */
export interface ValidationIssue {
  message: string;
  path: string;
  line: number | null;
}

export interface ValidationRequest {
  id: number;
  content: string;
}

export type ValidationResponse = { id: number; issues: ValidationIssue[] } | { id: number; error: string };

interface Pending {
  resolve: (issues: ValidationIssue[]) => void;
  reject: (error: Error) => void;
}

let worker: Worker | undefined;
let lastId = 0;
const pending = new Map<number, Pending>();

/** The validator runs off the main thread, so the OpenAPI parser never blocks typing. One worker serves every check. */
function getWorker(): Worker {
  if (worker) return worker;
  const created = new Worker(new URL("./validate.worker.ts", import.meta.url), { type: "module" });
  created.onmessage = (event: MessageEvent<ValidationResponse>) => {
    const request = pending.get(event.data.id);
    pending.delete(event.data.id);
    if (!request) return;
    if ("error" in event.data) request.reject(new Error(event.data.error));
    else request.resolve(event.data.issues);
  };
  created.onerror = () => {
    for (const request of pending.values()) request.reject(new Error("The spec validator stopped. Try again."));
    pending.clear();
    created.terminate();
    worker = undefined;
  };
  worker = created;
  return created;
}

/** Validates the text against the OpenAPI schema. Resolves with every issue found; an empty list means the spec is valid. */
export function validateSpec(content: string): Promise<ValidationIssue[]> {
  const id = ++lastId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    getWorker().postMessage({ id, content } satisfies ValidationRequest);
  });
}
