import SwaggerParser from "@apidevtools/swagger-parser";
import { type Document, parseDocument } from "yaml";
import type { ValidationIssue, ValidationRequest, ValidationResponse } from "./validation";

/** The parsed document swagger-parser takes. A string would mean a file path or URL, which the worker never reads. */
type SpecDocument = Exclude<Parameters<typeof SwaggerParser.validate>[0], string>;

self.onmessage = async (event: MessageEvent<ValidationRequest>) => {
  const { id, content } = event.data;
  try {
    const issues = await validate(content);
    self.postMessage({ id, issues } satisfies ValidationResponse);
  } catch (error) {
    self.postMessage({ id, error: error instanceof Error ? error.message : "Validation failed." } satisfies ValidationResponse);
  }
};

/** Parses the text (YAML, which also reads JSON), then checks it against the OpenAPI schema. */
async function validate(content: string): Promise<ValidationIssue[]> {
  if (content.trim() === "") return [{ message: "The spec is empty.", path: "", line: 1 }];
  const doc = parseDocument(content);
  if (doc.errors.length > 0) {
    return doc.errors.map((error) => ({ message: error.message, path: "", line: error.linePos?.[0]?.line ?? null }));
  }
  try {
    await SwaggerParser.validate(doc.toJS() as SpecDocument);
    return [];
  } catch (error) {
    const path = pathOf(error);
    const message = error instanceof Error ? error.message : "The spec is not a valid OpenAPI document.";
    return [{ message, path: path.map(String).join("/"), line: lineOf(doc, path, content) }];
  }
}

function pathOf(error: unknown): unknown[] {
  const path = (error as { path?: unknown } | null)?.path;
  return Array.isArray(path) ? path : [];
}

/** The line where the deepest known part of the path starts, so a finding points at the nearest real place. */
function lineOf(doc: Document, path: unknown[], content: string): number | null {
  for (let depth = path.length; depth >= 0; depth--) {
    const node = doc.getIn(path.slice(0, depth), true) as { range?: [number, number, number] | null } | undefined;
    const offset = node?.range?.[0];
    if (offset !== undefined) return content.slice(0, offset).split("\n").length;
  }
  return null;
}
