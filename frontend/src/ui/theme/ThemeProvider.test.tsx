import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { ThemeProvider } from "./ThemeProvider";
import { useTheme } from "./useTheme";

const STORAGE_KEY = "emitkit.theme";

/** Fakes window.matchMedia("(prefers-color-scheme: dark)") and lets a test flip the OS setting. */
function mockSystemDark(initial: boolean) {
  let matches = initial;
  const listeners = new Set<(event: { matches: boolean }) => void>();
  window.matchMedia = ((query: string) => ({
    get matches() {
      return matches;
    },
    media: query,
    addEventListener: (_: string, listener: (event: { matches: boolean }) => void) => listeners.add(listener),
    removeEventListener: (_: string, listener: (event: { matches: boolean }) => void) => listeners.delete(listener),
  })) as unknown as typeof window.matchMedia;
  return (next: boolean) => {
    matches = next;
    listeners.forEach((listener) => listener({ matches: next }));
  };
}

const wrapper = ({ children }: { children: ReactNode }) => <ThemeProvider>{children}</ThemeProvider>;

const originalMatchMedia = window.matchMedia;

beforeEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute("data-theme");
});

afterEach(() => {
  window.matchMedia = originalMatchMedia;
  vi.restoreAllMocks();
});

describe("ThemeProvider", () => {
  it("applies and persists an explicit mode", () => {
    mockSystemDark(false);
    const { result } = renderHook(() => useTheme(), { wrapper });

    act(() => result.current.setMode("dark"));

    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(localStorage.getItem(STORAGE_KEY)).toBe("dark");
    expect(result.current.resolved).toBe("dark");
  });

  it("restores the stored mode on mount", () => {
    localStorage.setItem(STORAGE_KEY, "light");
    mockSystemDark(true);
    const { result } = renderHook(() => useTheme(), { wrapper });

    expect(result.current.mode).toBe("light");
    expect(document.documentElement.dataset.theme).toBe("light");
  });

  it("in system mode follows prefers-color-scheme and updates live", () => {
    const setSystemDark = mockSystemDark(false);
    const { result } = renderHook(() => useTheme(), { wrapper });

    expect(result.current.mode).toBe("system");
    expect(document.documentElement.dataset.theme).toBe("light");

    act(() => setSystemDark(true));

    expect(result.current.resolved).toBe("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
  });

  it("still switches themes when localStorage throws", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("storage denied");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("storage denied");
    });
    mockSystemDark(false);
    const { result } = renderHook(() => useTheme(), { wrapper });

    act(() => result.current.setMode("dark"));

    expect(result.current.mode).toBe("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
  });

  it("useTheme throws a clear error outside the provider", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    expect(() => renderHook(() => useTheme())).toThrow(/ThemeProvider/);
  });
});
