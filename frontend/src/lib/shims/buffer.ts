/**
 * swagger-parser calls `Buffer.isBuffer` on the values it resolves. Browsers have no `Buffer`, so the check throws in the
 * validation worker and every valid spec reports an internal error. Specs arrive as parsed data, never as bytes, so a
 * stub that reports no buffers is enough. Import this before swagger-parser.
 */
const scope = globalThis as { Buffer?: { isBuffer: (value: unknown) => boolean } };
scope.Buffer ??= { isBuffer: () => false };
