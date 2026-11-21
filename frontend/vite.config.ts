import { fileURLToPath, URL } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vite";

/**
 * swagger-parser imports Node's `util`, which Vite stubs out in browser builds, leaving `util.format` undefined. Only
 * swagger-parser's own import is redirected, to a shim with the two functions it uses. Workers need it too, so it is
 * listed for them as well.
 */
const swaggerUtilShim: Plugin = {
  name: "swagger-util-shim",
  enforce: "pre",
  resolveId(source, importer) {
    if (source !== "util" || !importer?.includes("@apidevtools/swagger-parser/")) return null;
    return fileURLToPath(new URL("./src/lib/shims/util.ts", import.meta.url));
  },
};

export default defineConfig({
  worker: { plugins: () => [swaggerUtilShim] },
  plugins: [
    swaggerUtilShim,
    // TanStack Router's plugin is listed before the React plugin, as its docs require.
    // It generates src/routeTree.gen.ts, which is committed.
    tanstackRouter({
      target: "react",
      routeFileIgnorePattern: "\\.test\\.",
    }),
    react(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
});
