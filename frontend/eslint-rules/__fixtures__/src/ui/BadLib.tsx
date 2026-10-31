// UI may only use lib/utils, not the rest of lib: must be reported.
import { fetchThing } from "../lib/api";

export const badLib = fetchThing;
