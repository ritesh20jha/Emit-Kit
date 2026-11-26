import { setupServer } from "msw/node";
import { meHandler, vcsQuotaHandler } from "./handlers/me";

// Default handlers survive server.resetHandlers(), so every test starts with a signed-in user.
export const server = setupServer(meHandler, vcsQuotaHandler);
