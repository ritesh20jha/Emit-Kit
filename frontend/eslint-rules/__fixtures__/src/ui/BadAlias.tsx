// UI may only use lib/utils, and this reaches it through the @/ alias: must be reported.
import { fetchThing } from "@/lib/api";

export const badAlias = fetchThing;
