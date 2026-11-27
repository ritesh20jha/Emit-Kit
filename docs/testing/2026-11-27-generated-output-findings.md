# Generated output review: Bookshelf playground, SDK v0.1.0

What EmitKit generated for [emitkit-playground](https://github.com/ritesh20jha/emitkit-playground) (run `01M8EXF0369M3TYXSQYZWWKH7D`, PR #1, tag `sdk/v0.1.0`), checked by building it and calling it against a recording mock of the API.

Rerun the checks with `harness/run.sh` in the playground repo. It copies `.emitkit/` into a temporary directory, builds each package, runs the same calls through every SDK, the CLI and the MCP server, and prints what each one sent and decoded.

## Summary

| Output | Builds | Works against the mock | Verdict |
|---|---|---|---|
| TypeScript SDK | yes (`tsc`) | 9/9 happy-path calls, typed 404 | Usable. Header and cookie params missing. |
| Python SDK | yes; `mypy --strict` clean | 9/9 once resources are wired by hand | **Not usable as shipped**: `client.books` does not exist. |
| Java SDK | yes (Gradle, release 17) | 5/9. Every call that returns a `Book` throws. | **Broken**: oneOf discriminator. |
| CLI | yes | 12/12 commands behave as expected | Good. Small gaps. |
| MCP server | yes | 9 tools; all calls correct | Good. Thin tool descriptions. |

Things that work everywhere: path templating and encoding (`a b/c` → `a%20b%2Fc`), query serialisation, reserved-word properties (`class`, `import`, `default` keep their JSON names), allOf (`Book`), oneOf serialisation, nullable fields in TS and Python, 204 responses, typed 4xx/5xx errors, retry with backoff.

## Findings

Severity: **P0** the output is broken for normal use. **P1** wrong behaviour for a valid spec. **P2** quality or ergonomics.

### P0

1. **Java: oneOf with a discriminator mapping cannot be deserialised.** `BookFormat` registers `@JsonSubTypes` names `PrintFormat`, `EbookFormat` and `AudioFormat`, but the spec maps `print`, `ebook` and `audio`. Every response containing `format` fails with `Could not resolve type id 'print'`, which breaks `listBooks`, `createBook`, `getBook` and `updateBook`.
   Cause: `OneOfSchema` keeps only the discriminator property and drops `discriminator.mapping`. See the TODO at `generators/java-sdk/.../JavaTypeRenderer.java:339`. Fix in spec-engine: carry the mapping into the model, or fall back to each option's single-value `enum` for the discriminator property. Then use it in the Java renderer. TS and Python already work, because their unions don't need type ids.

2. **Python: resources are never attached to the client.** `client.py.mustache` doesn't reference the resources, so `BookshelfApiClient(...).books` raises `AttributeError`. The SDK can only be used by building `BooksResource(client)` by hand. The same applies to the async client. The README doesn't show any operation call, so this slipped through.

### P1

3. **SDKs drop header and cookie parameters.** TS, Python and Java never send `Idempotency-Key` (a *required* header on `createBook`) or the `session` cookie on `createLoan`. The TS `RequestOptions` has no `headers` field at all. The model has these parameters, and the CLI and MCP server send both correctly, so the gap is only in the three SDK generators.

4. **Required request bodies are optional in TS.** `createBook(body?: BookInput)`, `updateBook(params, body?)` and `createLoan(body?)` are all `required: true` in the spec. Python and Java get this right.

5. **Per-operation security is ignored.** Every SDK, the CLI and the MCP server send every configured credential (both `Authorization` and `X-Api-Key`) on every request. They do so even for `listAuthors`, which declares `security: []`, and `createLoan`, which accepts only `apiKeyAuth` or `oauth2`. The result is credentials sent where they don't belong, and the wrong one sent when an API rejects extra auth.

6. **Non-idempotent requests are retried.** A `POST /books` that returns 503 is sent 3 times by TS, Python and Java. Combined with finding 3, a retried `createBook` can create duplicates. Retry only idempotent methods (GET, HEAD, PUT, DELETE, OPTIONS), or POST/PATCH only when an idempotency key is sent. Also honour `Retry-After` on 429 and 503.

7. **Property order is scrambled in every generated model.** Order should follow the spec (`title, subtitle, authorIds, …`). Instead it is `isbn, class, genre, price, title, format, import, default, keywords, subtitle, authorIds, pageCount`, which is Postgres `jsonb` key order (shortest key first, then bytewise). The parsed `ApiModel` is stored in `spec_snapshots.api_model` (`jsonb`, `V3__runs.sql:11`). `RunGeneratorsStep` reads it back with `ApiModelJson.fromJson`, and by then the order is lost. In Java this also sets the positional order of record constructors, so any later change of order breaks every caller's code. Fix: store the model as `json` or `text`, or serialise properties as an ordered array.

8. **Java `BookPatch` cannot clear a nullable field.** The mapper uses `Include.NON_NULL`, so `new BookPatch(null, null, null, null)` sends `{}`. There is no way to send `"subtitle": null`. TS and Python send it correctly.

9. **int64 loses precision in TS and the CLI.** The author id `9007199254740993` reads back as `9007199254740992`. The CLI pretty-prints JSON by parsing it, so it shows the wrong id even though the server sent the right one. The MCP server passes text through and is unaffected. Options: map `int64` to `bigint` or `string` in TS, or have the CLI print the raw body when not using `--output table`.

10. **The TS SDK and the MCP server have the same npm name, `emitkit-playground`. The CLI and the MCP server also expose the same bin name, `emitkit-playground`.** Installing both globally makes them conflict. The configured npm scope (`@bookshelf`) is not used. Derive the names from the scope and add suffixes, for example `@bookshelf/sdk`, `@bookshelf/cli` and `@bookshelf/mcp`.

11. **The Python package has no `py.typed`.** mypy and pyright ignore the SDK's types once it is installed (`Package 'bookshelf_sdk' cannot be type checked due to missing py.typed marker`).

### P2

12. **Doc text.**
    - TS doc comments run summary and description together: `/** List books Returns a page of books… */`.
    - Python and Java docstrings keep only the summary. MCP tool descriptions are only the summary (`"List books"`), which gives an LLM little to go on. Join them with a blank line, and add parameter context to MCP tools.
    - "Bookshelf API **API**" appears in the client docs, `pyproject.toml` and the READMEs.

13. **Inline enums lose their type in Java.** `listAuthors` gets `ListAuthorsOptions(String sort)`, while TS gets `"asc" | "desc"` and Python gets the `ListAuthorsSort` enum.

14. **Python oneOf is a plain `Union`.** It decodes correctly here, but `Field(discriminator="kind")` would give exact matching and clearer errors.

15. **Python models aren't exported from the package root.** `from bookshelf_sdk import Book` fails, because only the clients and errors are in `__all__`.

16. **Dates are strings.** `date` and `date-time` are `string`, `str` and `String` everywhere. That's acceptable, but it should be a deliberate choice, either documented or made optional.

17. **The CLI doesn't validate input.** It sends `--genre romance` even though the value isn't in the enum, and it sends `--json '{not json'` as-is. The MCP server also doesn't check arguments against its `inputSchema`.

18. **The MCP server requires `API_BASE_URL`** even though the spec declares a server, while the SDKs and CLI default to it. It also fails with an uncaught stack trace at import time instead of a clear message.

19. **CLI command names repeat the group:** `books list-books`. Consider `books list`.

## Not covered

- Docs output (`.emitkit/docs/v0.1.0`) and the publish workflows. These weren't run, because there are no registry secrets, by choice.
- The async Python client. It shares templates with the sync client, so finding 2 applies to it too.
- Pagination helpers. None are generated for `cursor` and `nextCursor`, which is a possible enhancement.
