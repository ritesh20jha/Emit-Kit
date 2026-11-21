import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { MergeView as CodeMirrorMerge } from "@codemirror/merge";
import { EditorState } from "@codemirror/state";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { useEffect, useRef, useState } from "react";
import { languageFor, theme } from "./CodeEditor";

export interface MergeViewProps {
  /** The server's spec. Shown read-only on the left. */
  theirs: string;
  /** The text to merge, which the user edits on the right. Read once, when the view mounts. */
  mine: string;
  /** "yaml" or "json", which picks the language of both panes. */
  format: string;
  onMineChange: (text: string) => void;
  /** Called once the view is on screen, so the caller knows the merge can be saved. */
  onReady: () => void;
}

/**
 * The server's spec beside the editor's text, with the changes highlighted. Only the right side is editable, and each
 * change in the left side can be taken into the right with its revert control. It is loaded on its own, with the
 * conflict dialog, so CodeMirror's merge code stays out of the main editor bundle.
 */
export default function MergeView({ theirs, mine, format, onMineChange, onReady }: MergeViewProps) {
  const host = useRef<HTMLDivElement>(null);
  const handler = useRef({ onMineChange, onReady });
  // The view is built once from these, so later edits belong to the view, not to props.
  const [seed] = useState(() => ({ theirs, mine, format }));

  useEffect(() => {
    handler.current = { onMineChange, onReady };
  });

  useEffect(() => {
    const shared = [languageFor(seed.format), lineNumbers(), EditorView.lineWrapping, theme];
    const view = new CodeMirrorMerge({
      parent: host.current ?? undefined,
      revertControls: "a-to-b",
      a: {
        doc: seed.theirs,
        extensions: [EditorState.readOnly.of(true), EditorView.editable.of(false), EditorView.contentAttributes.of({ "aria-label": "Saved version (read-only)" }), ...shared],
      },
      b: {
        doc: seed.mine,
        extensions: [
          history(),
          keymap.of([...historyKeymap, ...defaultKeymap]),
          EditorView.contentAttributes.of({ "aria-label": "Your version" }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) handler.current.onMineChange(update.state.doc.toString());
          }),
          ...shared,
        ],
      },
    });
    handler.current.onReady();
    return () => view.destroy();
  }, [seed]);

  return <div ref={host} className="h-full min-h-0 overflow-auto" />;
}
