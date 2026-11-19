import SwaggerParser from "@apidevtools/swagger-parser";
import { type Document, parseDocument } from "yaml";
import type { ValidationIssue } from "./validation";

/** The parsed document swagger-parser takes. A string would mean a file path or URL, which is never read here. */
type SpecDocument = Exclude<Parameters<typeof SwaggerParser.validate>[0], string>;

/**
 * Checks spec text against the OpenAPI schema: YAML syntax first, then the schema. Pure, so the worker only wraps it
 * and it can be tested directly. Each finding has a 1-based line when it can be placed in the text.
 */
export async function validateSpecText(content: string): Promise<ValidationIssue[]> {
  if (content.trim() === "") return [{ message: "The spec is empty.", path: "", line: 1 }];
  const doc = parseDocument(content);
  if (doc.errors.length > 0) {
    return doc.errors.map((error) => ({ message: error.message, path: "", line: error.linePos?.[0]?.line ?? null }));
  }
  try {
    await SwaggerParser.validate(doc.toJS() as SpecDocument);
    return [];
  } catch (error) {
    const message = error instanceof Error ? error.message : "The spec is not a valid OpenAPI document.";
    const pointer = pointerOf(error);
    return [{ message, path: pointer, line: lineOf(doc, segmentsOf(pointer), content) }];
  }
}

/** The JSON pointer of the first schema finding, from swagger-parser's `details`. Empty when there is none. */
function pointerOf(error: unknown): string {
  const details = (error as { details?: unknown } | null)?.details;
  const pointer = Array.isArray(details) ? (details[0] as { instancePath?: unknown } | undefined)?.instancePath : undefined;
  return typeof pointer === "string" ? pointer : "";
}

/** The keys of a JSON pointer, unescaped (`~1` is `/`, `~0` is `~`). */
function segmentsOf(pointer: string): string[] {
  return pointer === "" ? [] : pointer.slice(1).split("/").map((key) => key.replace(/~1/g, "/").replace(/~0/g, "~"));
}

/** The line where the deepest known part of the path starts, so a finding points at the nearest real place. */
function lineOf(doc: Document, path: string[], content: string): number | null {
  for (let depth = path.length; depth >= 0; depth--) {
    const node = doc.getIn(path.slice(0, depth), true) as { range?: [number, number, number] | null } | undefined;
    const offset = node?.range?.[0];
    if (offset !== undefined) return content.slice(0, offset).split("\n").length;
  }
  return null;
}
