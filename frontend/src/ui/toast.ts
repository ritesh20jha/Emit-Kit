import { createElement } from "react";
import { toast as sonner } from "sonner";

/**
 * An API failure as the UI shows it: a plain-language message and the stable error code. Structural on
 * purpose, so AppError from the API layer satisfies it without ui/ importing lib/.
 */
export interface FeedbackError {
  message: string;
  code?: string;
}

export const toast = {
  success(message: string) {
    return sonner.success(message);
  },
  /** The message is the title; the code, when there is one, sits under it in mono so support can quote it. */
  error(error: FeedbackError) {
    return sonner.error(error.message, {
      description: error.code
        ? createElement("code", { className: "font-mono text-xs" }, error.code)
        : undefined,
    });
  },
  dismiss(id?: string | number) {
    return sonner.dismiss(id);
  },
};
