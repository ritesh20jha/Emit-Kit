import { useCallback, useEffect, useRef, useState } from "react";

/**
 * connecting: no connection yet (also the state of a null url). open: receiving events.
 * reconnecting: the link dropped and is being retried. done: the server sent `done`. error: EventSource is unavailable.
 */
export type EventStreamStatus = "connecting" | "open" | "reconnecting" | "done" | "error";

export interface EventStreamOptions {
  /** Called once, when the server sends `done`. */
  onDone?: () => void;
  /** The named event that carries items. Defaults to "log". */
  eventName?: string;
}

export interface EventStream<T> {
  items: T[];
  status: EventStreamStatus;
  /** Drops the items and connects again from the start. Used by a manual retry. */
  reconnect: () => void;
}

const BACKOFF_START_MS = 1000;
const BACKOFF_CAP_MS = 10_000;
/** Items that arrive within this window are added to state in one render, so a replay does not render per line. */
const FLUSH_MS = 50;

interface Connection<T> {
  /** Identifies the url, event name and reconnect generation the items belong to. */
  scope: string;
  items: T[];
  status: EventStreamStatus;
}

function idle<T>(scope: string): Connection<T> {
  return { scope, items: [], status: "connecting" };
}

/** Parses one event payload. Anything that is not JSON with a numeric seq is dropped. */
function parseItem<T extends { seq: number }>(data: unknown): T | undefined {
  if (typeof data !== "string") return undefined;
  try {
    const value: unknown = JSON.parse(data);
    const hasSeq = typeof value === "object" && value !== null && typeof (value as { seq?: unknown }).seq === "number";
    return hasSeq ? (value as T) : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Reads a server-sent event stream (EventSource, with credentials) and keeps the items it carries.
 *
 * Items with `seq` at or below the last one received are dropped, so a replay after a reconnect adds nothing twice.
 * While the browser retries on its own (readyState CONNECTING) the status is "reconnecting". When the browser gives up
 * (readyState CLOSED) this hook opens a new stream after a backoff from 1 s, doubling to a 10 s cap.
 * A null url means nothing is connected.
 */
export function useEventStream<T extends { seq: number }>(url: string | null, opts: EventStreamOptions = {}): EventStream<T> {
  const { onDone, eventName = "log" } = opts;
  const onDoneRef = useRef(onDone);
  const [generation, setGeneration] = useState(0);
  // A new scope starts with no items. The state is reset lazily, so no state is set inside the effect.
  const scope = JSON.stringify([url, eventName, generation]);
  const [connection, setConnection] = useState<Connection<T>>(() => idle<T>(scope));

  useEffect(() => {
    onDoneRef.current = onDone;
  }, [onDone]);

  useEffect(() => {
    if (url === null) return;
    const target: string = url;

    // Everything below belongs to this effect. Cleanup closes the source and clears the timers, so nothing from an
    // old scope can reach the state.
    let source: EventSource | undefined;
    let retryTimer: number | undefined;
    let flushTimer: number | undefined;
    let backoff = BACKOFF_START_MS;
    let lastSeq = Number.NEGATIVE_INFINITY;
    let pending: T[] = [];

    const update = (change: (current: Connection<T>) => Connection<T>) =>
      setConnection((previous) => change(previous.scope === scope ? previous : idle<T>(scope)));

    function flush() {
      window.clearTimeout(flushTimer);
      flushTimer = undefined;
      if (pending.length === 0) return;
      const batch = pending;
      pending = [];
      update((current) => ({ ...current, items: current.items.concat(batch) }));
    }

    function receive(item: T) {
      if (item.seq <= lastSeq) return;
      lastSeq = item.seq;
      pending.push(item);
      flushTimer ??= window.setTimeout(flush, FLUSH_MS);
    }

    function connect() {
      let opened: EventSource;
      try {
        opened = new globalThis.EventSource(target, { withCredentials: true });
      } catch {
        update((current) => ({ ...current, status: "error" }));
        return;
      }
      source = opened;

      opened.onopen = () => {
        backoff = BACKOFF_START_MS;
        update((current) => ({ ...current, status: "open" }));
      };
      opened.addEventListener(eventName, (event) => {
        const item = parseItem<T>((event as MessageEvent).data);
        if (item) receive(item);
      });
      opened.addEventListener("done", () => {
        flush();
        opened.close();
        update((current) => ({ ...current, status: "done" }));
        onDoneRef.current?.();
      });
      opened.onerror = () => {
        if (opened.readyState === EventSource.CLOSED) {
          scheduleReconnect();
        } else {
          update((current) => ({ ...current, status: "reconnecting" }));
        }
      };
    }

    function scheduleReconnect() {
      update((current) => ({ ...current, status: "reconnecting" }));
      retryTimer = window.setTimeout(connect, backoff);
      backoff = Math.min(backoff * 2, BACKOFF_CAP_MS);
    }

    connect();

    return () => {
      window.clearTimeout(retryTimer);
      window.clearTimeout(flushTimer);
      source?.close();
    };
  }, [scope, url, eventName]);

  const reconnect = useCallback(() => setGeneration((value) => value + 1), []);

  const view = connection.scope === scope ? connection : idle<T>(scope);
  return { items: view.items, status: view.status, reconnect };
}
