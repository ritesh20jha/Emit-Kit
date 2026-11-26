/**
 * Stands in for Node's `util` where swagger-parser imports it in the browser. Vite stubs `util` out there, which
 * leaves `util.format` undefined. swagger-parser only formats its error message (one argument) and calls `inherits`.
 */
export const format = (message: string) => message;
export const inherits = () => {};
