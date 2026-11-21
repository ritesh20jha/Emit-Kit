import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { foldGutter, foldKeymap, HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { json } from "@codemirror/lang-json";
import { yaml } from "@codemirror/lang-yaml";
import { type Diagnostic, lintGutter, setDiagnostics } from "@codemirror/lint";
import { search, searchKeymap } from "@codemirror/search";
import { EditorState, type Extension, type Text } from "@codemirror/state";
import { EditorView, highlightActiveLine, keymap, lineNumbers } from "@codemirror/view";
import { tags } from "@lezer/highlight";
import { type Ref, useEffect, useImperativeHandle, useRef, useState } from "react";
import type { Problem } from "./problems";

export interface CodeEditorHandle {
  /** Puts the cursor at the start of a line and scrolls it into view. */
  jumpTo(line: number): void;
}

export interface CodeEditorProps {
  ref?: Ref<CodeEditorHandle>;
  /** The text the editor opens with. It is read once, when the editor mounts. */
  initialText: string;
  /** "yaml" or "json", which picks the language. */
  format: string;
  problems: Problem[];
  onChange: (text: string) => void;
  /** Mod+S. */
  onSave: () => void;
}

type Handlers = Pick<CodeEditorProps, "onChange" | "onSave">;

/**
 * The spec's code editor: line numbers, folding, search and inline problem markers. It is loaded on its own so
 * CodeMirror only ships with the editor route.
 */
export default function CodeEditor({ ref, initialText, format, problems, onChange, onSave }: CodeEditorProps) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const handlers = useRef<Handlers>({ onChange, onSave });
  // The document and language are set once. Later changes to the text belong to the editor, not to props.
  const [seed] = useState(() => ({ initialText, format }));

  useEffect(() => {
    handlers.current = { onChange, onSave };
  });

  useEffect(() => {
    const instance = new EditorView({
      parent: host.current ?? undefined,
      state: EditorState.create({ doc: seed.initialText, extensions: extensionsFor(seed.format, handlers) }),
    });
    view.current = instance;
    return () => {
      instance.destroy();
      view.current = null;
    };
  }, [seed]);

  useImperativeHandle(ref, () => ({ jumpTo: (line: number) => jump(view.current, line) }), []);

  useEffect(() => {
    const instance = view.current;
    if (instance) instance.dispatch(setDiagnostics(instance.state, markersFor(problems, instance.state.doc)));
  }, [problems]);

  return <div ref={host} className="h-full min-h-0" />;
}

function extensionsFor(format: string, handlers: { current: Handlers }): Extension[] {
  return [
    keymap.of([{ key: "Mod-s", preventDefault: true, run: () => (handlers.current.onSave(), true) }, ...searchKeymap, ...historyKeymap, ...foldKeymap, ...defaultKeymap]),
    lineNumbers(),
    foldGutter(),
    lintGutter(),
    history(),
    search({ top: true }),
    syntaxHighlighting(highlight),
    highlightActiveLine(),
    format === "json" ? json() : yaml(),
    EditorView.lineWrapping,
    EditorView.updateListener.of((update) => {
      if (update.docChanged) handlers.current.onChange(update.state.doc.toString());
    }),
    theme,
  ];
}

/** Syntax colours are design tokens too, so dark mode keeps their contrast. */
const highlight = HighlightStyle.define([
  { tag: [tags.keyword, tags.atom, tags.bool, tags.null, tags.operatorKeyword], color: "var(--color-accent)" },
  { tag: [tags.string, tags.special(tags.string)], color: "var(--color-status-success)" },
  { tag: tags.number, color: "var(--color-status-awaiting)" },
  { tag: tags.propertyName, color: "var(--color-status-running)" },
  { tag: tags.comment, color: "var(--color-fg-subtle)", fontStyle: "italic" },
  { tag: [tags.punctuation, tags.meta], color: "var(--color-fg-muted)" },
  { tag: tags.invalid, color: "var(--color-status-failed)" },
]);

/** Colours come from the design tokens, so the editor follows light and dark mode with the rest of the app. */
const theme = EditorView.theme({
  "&": { height: "100%", color: "var(--color-fg)", backgroundColor: "var(--color-bg)", fontSize: "13px" },
  ".cm-scroller": { fontFamily: "var(--font-mono)", lineHeight: "20px" },
  ".cm-content": { caretColor: "var(--color-fg)" },
  ".cm-gutters": { backgroundColor: "var(--color-bg-subtle)", color: "var(--color-fg-subtle)", borderRight: "1px solid var(--color-border)" },
  ".cm-activeLine, .cm-activeLineGutter": { backgroundColor: "var(--color-bg-muted)" },
  "&.cm-focused": { outline: "2px solid var(--color-focus)", outlineOffset: "-2px" },
});

/** Puts the cursor at the start of a line, clamped to the document, and scrolls to it. */
function jump(instance: EditorView | null, line: number) {
  if (!instance) return;
  const target = instance.state.doc.line(Math.min(Math.max(line, 1), instance.state.doc.lines));
  instance.dispatch({ selection: { anchor: target.from }, scrollIntoView: true });
  instance.focus();
}

/** Markers for the problems that have a line. A problem can be a check behind the text, so its line is clamped. */
function markersFor(problems: Problem[], doc: Text): Diagnostic[] {
  return problems.flatMap(({ line, severity, message }) => {
    if (line === null) return [];
    const at = doc.line(Math.min(Math.max(line, 1), doc.lines));
    return [{ from: at.from, to: at.to, severity, message }];
  });
}
