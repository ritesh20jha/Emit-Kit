import { MergeView as CodeMirrorMerge } from "@codemirror/merge";
import { EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { useEffect, useRef, useState } from "react";
import { theme } from "./CodeEditor";

export interface MergeViewProps {
  /** The server's spec. Shown read-only on the left. */
  theirs: string;
  /** The text to merge, which the user edits on the right. Read once, when the view mounts. */
  mine: string;
  onMineChange: (text: string) => void;
}

/**
 * The server's spec beside the editor's text, with the changes highlighted. Only the right side is editable. It is
 * loaded on its own, with the conflict dialog, so CodeMirror's merge code stays out of the main editor bundle.
 */
export default function MergeView({ theirs, mine, onMineChange }: MergeViewProps) {
  const host = useRef<HTMLDivElement>(null);
  const handler = useRef(onMineChange);
  // The view is built once from these, so later edits belong to the view, not to props.
  const [seed] = useState(() => ({ theirs, mine }));

  useEffect(() => {
    handler.current = onMineChange;
  });

  useEffect(() => {
    const view = new CodeMirrorMerge({
      parent: host.current ?? undefined,
      a: { doc: seed.theirs, extensions: [EditorState.readOnly.of(true), EditorView.editable.of(false), theme] },
      b: {
        doc: seed.mine,
        extensions: [
          EditorView.updateListener.of((update) => {
            if (update.docChanged) handler.current(update.state.doc.toString());
          }),
          theme,
        ],
      },
    });
    return () => view.destroy();
  }, [seed]);

  return <div ref={host} className="h-full min-h-0 overflow-auto" />;
}
