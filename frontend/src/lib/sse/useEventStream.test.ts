import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { useEventStream } from "./useEventStream";

interface Line {
  seq: number;
  message: string;
}

const STREAM_URL = "/api/v1/runs/r1/logs/stream";

/** Stands in for the browser EventSource. The test drives it: open, deliver events, fail with a readyState. */
class FakeEventSource {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 2;
  static instances: FakeEventSource[] = [];

  readyState = FakeEventSource.CONNECTING;
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  closed = false;
  private handlers = new Map<string, Array<(event: MessageEvent) => void>>();

  constructor(
    readonly url: string,
    readonly init?: EventSourceInit,
  ) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, handler: (event: MessageEvent) => void) {
    this.handlers.set(type, [...(this.handlers.get(type) ?? []), handler]);
  }

  close() {
    this.closed = true;
    this.readyState = FakeEventSource.CLOSED;
  }

  open() {
    this.readyState = FakeEventSource.OPEN;
    this.onopen?.(new Event("open"));
  }

  send(type: string, data: string) {
    for (const handler of this.handlers.get(type) ?? []) handler(new MessageEvent(type, { data }));
  }

  fail(readyState: number) {
    this.readyState = readyState;
    this.onerror?.(new Event("error"));
  }
}

function latest(): FakeEventSource {
  const source = FakeEventSource.instances.at(-1);
  if (!source) throw new Error("no EventSource was created");
  return source;
}

const line = (seq: number): Line => ({ seq, message: `line ${seq}` });
const logData = (seq: number) => JSON.stringify(line(seq));

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal("EventSource", FakeEventSource);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("useEventStream", () => {
  test("reconnect de-duplicates by seq", async () => {
    const { result } = renderHook(() => useEventStream<Line>(STREAM_URL));
    const first = latest();

    act(() => {
      first.open();
      first.send("log", logData(1));
      first.send("log", logData(2));
      first.send("log", logData(3));
    });
    act(() => first.fail(FakeEventSource.CONNECTING));
    expect(result.current.status).toBe("reconnecting");

    // The browser retries on its own; the replay repeats 2 and 3 and adds 4.
    act(() => {
      first.open();
      first.send("log", logData(2));
      first.send("log", logData(3));
      first.send("log", logData(4));
    });

    await waitFor(() => expect(result.current.items.map((item) => item.seq)).toEqual([1, 2, 3, 4]));
    expect(result.current.status).toBe("open");
  });

  test("done closes the stream", async () => {
    const onDone = vi.fn();
    const { result } = renderHook(() => useEventStream<Line>(STREAM_URL, { onDone }));
    const source = latest();

    act(() => {
      source.open();
      source.send("log", logData(1));
      source.send("done", "[DONE]");
    });

    expect(source.closed).toBe(true);
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(result.current.status).toBe("done");
    await waitFor(() => expect(result.current.items.map((item) => item.seq)).toEqual([1]));
  });

  test("recreates a closed stream with backoff from 1 s, doubling to a 10 s cap", () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useEventStream<Line>(STREAM_URL));

    act(() => {
      latest().open();
      latest().send("log", logData(1));
      latest().fail(FakeEventSource.CLOSED);
    });
    expect(result.current.status).toBe("reconnecting");
    expect(FakeEventSource.instances).toHaveLength(1);

    act(() => vi.advanceTimersByTime(999));
    expect(FakeEventSource.instances).toHaveLength(1);
    act(() => vi.advanceTimersByTime(1));
    expect(FakeEventSource.instances).toHaveLength(2);

    // Each failure doubles the wait: 2 s, 4 s, 8 s, then the 10 s cap.
    for (const wait of [2000, 4000, 8000, 10000]) {
      act(() => latest().fail(FakeEventSource.CLOSED));
      const before = FakeEventSource.instances.length;
      act(() => vi.advanceTimersByTime(wait - 1));
      expect(FakeEventSource.instances).toHaveLength(before);
      act(() => vi.advanceTimersByTime(1));
      expect(FakeEventSource.instances).toHaveLength(before + 1);
    }
  });

  test("reports error when EventSource cannot be created", () => {
    vi.stubGlobal("EventSource", function UnsupportedEventSource(): never {
      throw new Error("unsupported");
    });
    const { result } = renderHook(() => useEventStream<Line>(STREAM_URL));
    expect(result.current.status).toBe("error");
  });

  test("a null url connects to nothing", () => {
    const { result } = renderHook(() => useEventStream<Line>(null));
    expect(FakeEventSource.instances).toHaveLength(0);
    expect(result.current.items).toEqual([]);
  });
});
