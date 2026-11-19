import { validateSpecText } from "./validate-spec";
import type { ValidationRequest, ValidationResponse } from "./validation";

self.onmessage = async (event: MessageEvent<ValidationRequest>) => {
  const { id, content } = event.data;
  try {
    const issues = await validateSpecText(content);
    self.postMessage({ id, issues } satisfies ValidationResponse);
  } catch (error) {
    self.postMessage({ id, error: error instanceof Error ? error.message : "Validation failed." } satisfies ValidationResponse);
  }
};
