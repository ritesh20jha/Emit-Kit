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

const pending = new Map<number, { resolve: (issues: ValidationIssue[]) => void; reject: (error: Error) => void }>();
let worker: Worker | undefined;
let lastId = 0;

/** One worker serves every check, so the OpenAPI parser never blocks typing. */
function getWorker(): Worker {
  if (worker) return worker;
  worker = new Worker(new URL("./validate.worker.ts", import.meta.url), { type: "module" });
  worker.onmessage = ({ data }: MessageEvent<ValidationResponse>) => {
    const request = pending.get(data.id);
    pending.delete(data.id);
    if ("error" in data) request?.reject(new Error(data.error));
    else request?.resolve(data.issues);
  };
  worker.onerror = () => {
    for (const request of pending.values()) request.reject(new Error("The spec validator stopped. Try again."));
    pending.clear();
    worker?.terminate();
    worker = undefined;
  };
  return worker;
}

/** Validates the text against the OpenAPI schema. Resolves with every issue found; an empty list means the spec is valid. */
export function validateSpec(content: string): Promise<ValidationIssue[]> {
  const id = ++lastId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    getWorker().postMessage({ id, content } satisfies ValidationRequest);
  });
}
