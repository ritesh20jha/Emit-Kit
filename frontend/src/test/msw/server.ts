import { setupServer } from "msw/node";
import { meHandler } from "./handlers/me";

// Default handlers survive server.resetHandlers(), so every test starts with a signed-in user.
export const server = setupServer(meHandler);
