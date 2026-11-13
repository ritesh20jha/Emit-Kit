import { describe, expect, test } from "vitest";
import { filterLines, type LogLine, parseFilters } from "./logFilters";

const lines: LogLine[] = [
  { seq: 1, ts: "2026-11-13T15:30:00.000Z", level: "INFO", step: "PARSE_SPEC", message: "Parsed 12 operations" },
  { seq: 2, ts: "2026-11-13T15:30:01.000Z", level: "ERROR", step: "PARSE_SPEC", message: "Unexpected TIMEOUT" },
  { seq: 3, ts: "2026-11-13T15:30:02.000Z", level: "ERROR", step: "FINALIZE", message: "Commit failed" },
];

describe("parseFilters", () => {
  test("keeps known values and ignores the rest", () => {
    expect(parseFilters({ step: "PARSE_SPEC", level: "WARN", q: "x" })).toEqual({ step: "PARSE_SPEC", level: "WARN", q: "x" });
    expect(parseFilters({ step: "NOT_A_STEP", level: "DEBUG", q: 3 })).toEqual({ step: "", level: "", q: "" });
    expect(parseFilters({})).toEqual({ step: "", level: "", q: "" });
  });
});

describe("filterLines", () => {
  test("with no filter returns the same array", () => {
    expect(filterLines(lines, { step: "", level: "", q: "  " })).toBe(lines);
  });

  test("combines step, level and a case-insensitive search", () => {
    expect(filterLines(lines, { step: "PARSE_SPEC", level: "ERROR", q: "" }).map((line) => line.seq)).toEqual([2]);
    expect(filterLines(lines, { step: "", level: "", q: "commit" }).map((line) => line.seq)).toEqual([3]);
    expect(filterLines(lines, { step: "", level: "ERROR", q: "timeout" }).map((line) => line.seq)).toEqual([2]);
  });
});
