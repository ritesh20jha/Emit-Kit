# EmitKit Rebuild — v3: AI Features & Multi-VCS

**Date:** 2026-10-31
**Status:** Approved design, pending spec review
**Depends on:** [Overview](2026-10-31-emitkit-rebuild-overview-design.md), [v1](2026-10-31-emitkit-rebuild-v1-design.md), [v2](2026-10-31-emitkit-rebuild-v2-design.md) (shipped)

---

## 1. Goal & exit criteria

v3 adds AI features that help users write better specs and understand releases. It also opens EmitKit to GitLab and Bitbucket through the `VcsProvider` abstraction built in v1.

**Exit criteria**
1. The AI spec assistant, AI release notes and AI migration guides are GA (flags on by default), and every AI feature works without AI by falling back to non-AI output.
2. GitLab (gitlab.com and self-managed ≥ 16) is GA. Bitbucket Cloud is GA.
3. The full v1 and v2 end-to-end suites pass on all three VCS providers (GitHub, GitLab, Bitbucket), running in parallel against sandbox accounts.
4. No AI request ever contains secrets from EmitKit storage. Enforced by a test (§3.5).

---

## 2. Scope

| Group | Features |
|---|---|
| Spec editing | 26 AI spec assistant |
| Releases | 65 AI release notes · 66 AI migration guide on major bumps |
| Integrations | 70 GitLab + Bitbucket providers |

---

## 3. AI platform

### 3.1 Provider abstraction
v1 shipped `AiProvider` with a single Gemini implementation used by docs. v3 widens the interface and adds providers:

```java
public interface AiProvider {
  AiProviderId id();                                  // GEMINI, ANTHROPIC, OPENAI
  AiResponse complete(AiRequest req);                 // system + messages, maxTokens, temperature, jsonSchema?
  Stream<AiChunk> stream(AiRequest req);              // for the editor assistant
}
```
- Providers: Gemini (existing), Anthropic, OpenAI. Each uses the vendor's official Java SDK when one exists, otherwise its HTTP API.
- Config per project: `ai_provider`, `ai_model`. The model is a free-text field validated against a provider's model list fetched at config save, so no model IDs are hard-coded. The key stays encrypted (`ai_api_key_enc`).
- **Key ownership stays BYO (bring your own):** EmitKit does not provide a platform key, because billing is out of scope. If no key is set, AI features are hidden in the UI and the pipeline uses the template fallback.
- Structured output: features that need JSON (release notes sections, migration steps) pass a JSON Schema. The provider adapter maps it to the vendor's structured-output or tool-use mode and validates the response. On a validation failure it retries once, then uses the fallback.

### 3.2 Context building
A `SpecContextBuilder` turns an `ApiModel` (or a slice of one) into compact text for prompts:
- Operation signatures are always included; full schemas only for operations in focus.
- A token budget per feature, estimated as chars / 4 and set in configuration.
- Never included: AI keys, webhook secrets, registry credentials, OAuth tokens, `url_auth_header_enc`, or `servers[].url` values that point to private IPs (they are redacted).

### 3.3 Usage tracking
```
ai_usage(id, org_id FK, project_id FK, feature ENUM(DOCS, ASSISTANT, RELEASE_NOTES, MIGRATION),
         provider, model, input_tokens, output_tokens, latency_ms, status, created_at)
```
- Shown on the v2 analytics dashboard as a new "AI usage" panel.
- Limits are set per org, per day, per feature: `ai_daily_request_limit`, default 500. This protects the user's own key from runaway loops. Over the limit → HTTP 429 `AI_LIMIT_REACHED`.

### 3.4 Failure handling
- Timeouts: 30 s for non-streaming calls, 120 s for streams.
- Provider errors are shown in the UI as `AI_PROVIDER_ERROR` with the vendor message (sanitized).
- AI never blocks a run. Pipeline AI steps are best effort and log a warning.

### 3.5 Safety tests
- A prompt-capture test double records every `AiRequest` in the integration tests. A test fails if any captured request contains a value from the encrypted-secrets fixture set.
- Output from AI is treated as untrusted text:
  - Markdown is sanitized before it is shown (`rehype-sanitize`).
  - AI-suggested spec edits are only applied after the user confirms (§4).
  - AI output is never executed.

---

## 4. AI spec assistant (26)

### UX
- An "Assistant" side panel in the Editor tab (toggle with ⌘K / Ctrl+K).
- Inputs: a free-form request and quick actions:
  - "Write missing descriptions";
  - "Add pagination to selected operation";
  - "Suggest operationIds";
  - "Fix lint issues";
  - "Explain this schema".
- Context: the current editor buffer plus the selection (or the operation under the cursor) plus lint results.
- Output is one of:
  - an **explanation** (markdown, streamed);
  - a **proposed edit**: a unified diff against the buffer, shown as an inline diff in CodeMirror with *Accept*, *Reject* and *Accept part* (per hunk).
- Accepted edits change only the editor buffer. Saving still uses the v1 save flow (sha check, commit). Nothing is committed automatically.

### Backend
- `POST /api/v1/projects/{id}/assistant` (permission `SPEC_EDIT`) with body `{ specSourceId, buffer, selection?, action, message, history[] }`. It returns an SSE stream of `{type: text|edit|done|error}` events.
- Edit generation: the model returns the full replacement text for the operations or components it targets, plus their JSON Pointers, in structured output. The server splices them into the buffer, re-parses with swagger-parser, re-lints, and then sends the unified diff. If the result no longer parses, the edit is dropped and the model's explanation is returned with a warning.
- History: the last 10 turns, kept by the client only (not stored).
- Audit: `assistant.edit_accepted` is logged when the user saves a buffer that contains accepted edits. The client marks accepted edits; the prompt text is not stored.

---

## 5. AI release notes (65)

- A new pipeline step **ReleaseNotes** runs after CalcVersion and before RunGenerators, only when the version changed.
- Input: `SpecDiff` (v1 §5.4), the old and new operation signatures for changed operations, the project name, and the version change.
- Output (structured): `{ summary, highlights[], breaking[], added[], changed[], deprecated[] }`. Each item has `operationId` refs, so the UI can link to the diff viewer.
- It is rendered to markdown and saved as `sdk_versions.changelog`. The structured JSON is saved in a new `sdk_versions.changelog_json` column. It is also used as the PR body and written to `{out}/CHANGELOG.md`, prepended.
- **Fallback:** a deterministic template changelog built from `SpecDiff`. This is the default when AI is off.
- Editable: on the Versions tab an editor (or admin) can edit the notes before the PR is merged. The edit updates the PR body through `VcsClient.updatePr`, a new method.

---

## 6. AI migration guide (66)

- Runs only on a **major** bump (or a minor bump below 1.0.0 that has breaking changes), right after ReleaseNotes.
- Input: the breaking changes from `SpecDiff`, plus old and new **generated SDK signatures** for each selected language. The signatures come from the generator, which gains `List<SymbolSignature> signatures(ApiModel)`: method names, parameters, return types.
- Output: `{ overview, steps[ { title, why, before: {lang → code}, after: {lang → code} } ] }`.
- Written to `{out}/docs/migrations/v{old}-to-v{new}.md`, linked from the release notes, and shown on the Versions tab.
- Code snippets are compiled in the sandbox for TS, Python and Java when possible. Snippets that fail to compile get a "⚠ unverified" note; they are not dropped.
- Fallback: a template guide that lists each breaking change with the before/after signature, without prose.

---

## 7. GitLab & Bitbucket (70)

### 7.1 Identity & accounts
- Login options: GitHub, GitLab, Bitbucket (Spring Security OAuth2, one registration per provider). Self-managed GitLab instances are configured by platform operators (`vcs_instances` table: `base_url`, client id/secret); they are not added by users.
- A user can link several providers to one EmitKit user from `/me/accounts`. Linking needs a verified email match, or an explicit "link" while already signed in.
- `users.github_id` moves into `oauth_accounts` (it already holds `provider_account_id`). The migration copies it and drops the column in a contract step.
- Org mapping:
  - GitLab **groups** (including subgroups, flattened by full path) → organizations.
  - Bitbucket **workspaces** → organizations.
  - Personal namespace or workspace → personal org.
- Role mapping:

| Provider | → ADMIN | → EDITOR | → VIEWER |
|---|---|---|---|
| GitLab | Owner, Maintainer | Developer | Reporter, Guest |
| Bitbucket | workspace admin | member with write | member read-only |

### 7.2 Provider implementations
| Capability | GitLab (REST v4) | Bitbucket Cloud (REST 2.0) |
|---|---|---|
| list/create repos | `/projects`, `POST /projects` | `/repositories/{ws}`, `POST /repositories/{ws}/{slug}` |
| get/put file w/ optimistic lock | Repository Files API with `last_commit_id` | `GET /src/{ref}/{path}`. Lock check: compare the file's last commit hash before `POST /src` |
| multi-file commit | Commits API with `actions[]` | `POST /src` multipart (all files in one commit) |
| branch / PR | `POST /repository/branches`, Merge Requests API | `POST /refs/branches`, Pull Requests API |
| comment | MR notes | PR comments |
| tag | `POST /repository/tags` | `POST /refs/tags` |
| webhook | Project hooks. Verification: `X-Gitlab-Token` secret, constant-time compare | Repo webhooks with secret. Verification: `X-Hub-Signature` HMAC-SHA256 |
| rate limits | `RateLimit-Remaining` / `RateLimit-Reset` | `X-RateLimit-Remaining` (+ 429 Retry-After) |

- Webhook endpoints: `/webhooks/gitlab` and `/webhooks/bitbucket`. Each normalizes events into the same internal `VcsEvent` (`Push`, `PrOpened`, `PrUpdated`, `PrMerged`), so all trigger logic (v1 §9, v2 §5.2) is shared.
- `VcsClient` gains `updatePr` (needed by §5). The v2 methods `listComments`, `updateComment` and `setCommitStatus` are implemented for each provider: GitLab commit statuses, Bitbucket build statuses.
- If a provider can't do something, the method throws `UnsupportedVcsCapability` and the UI hides that option. For example, Bitbucket can't target a separate output repo in another workspace when the token lacks access.

### 7.3 Provider-specific outputs
- CI files are generated for the project's provider:
  - GitHub: `.github/workflows/*.yml` (unchanged).
  - GitLab: `.gitlab-ci.yml` with an `include:` of `.emitkit/ci/publish-*.yml` jobs on tag pipelines.
  - Bitbucket: `bitbucket-pipelines.yml` with `tags: 'sdk/v*'` pipelines.
- If the user's repo already has a root CI file, EmitKit **doesn't modify it**. It writes `.emitkit/ci/*` and adds a PR comment with the one-line `include:` to add by hand.
- The v2 docs site deploys to GitLab Pages (a `pages` job). Bitbucket has no Pages equivalent, so the option is hidden and the docs-site artifact is still produced.
- Direct publishing (v2) is provider-independent and works unchanged.

### 7.4 UI
- The login page has three provider buttons. Self-managed GitLab shows an instance picker if any are configured.
- The org switcher shows a provider icon per org. The new-project repo picker lists repos for the org's provider.
- Wording follows the provider ("Merge request" for GitLab), from a `vcsTerms(provider)` helper.

---

## 8. API additions (`/api/v1`)

| Method & path | Permission |
|---|---|
| `POST /projects/{id}/assistant` (SSE) | SPEC_EDIT |
| `GET /versions/{vid}/release-notes` · `PUT /versions/{vid}/release-notes` | RUN_VIEW · SPEC_EDIT |
| `GET /versions/{vid}/migration-guide` | RUN_VIEW |
| `GET /orgs/{orgId}/ai-usage?from&to` | ORG_VIEW |
| `GET /ai/providers/{provider}/models` (needs a key in the body or a projectId) | CONFIG_EDIT |
| `GET /me/accounts` · `DELETE /me/accounts/{aid}` · `/oauth2/authorization/{gitlab,bitbucket}` | — |
| `POST /webhooks/gitlab` · `POST /webhooks/bitbucket` | token / HMAC |
| `GET/POST /admin/vcs-instances` | platform admin |

---

## 9. Testing

- AI: a test double `AiProvider` with recorded responses (golden JSON). Contract tests against each real provider run nightly, with a small budget-capped key from CI secrets. The secrets-in-prompt test (§3.5) runs on every PR.
- Assistant: property test that any accepted edit still parses (fuzz with random selections over the fixture specs).
- Release notes and migration guides: snapshot tests of the fallback templates. Schema-validation tests for the AI structured output.
- VCS: a WireMock suite for each provider, sharing **one** contract test class (`VcsClientContractTest`) that every provider implementation must pass. Nightly end-to-end tests against real sandbox accounts on GitLab.com and Bitbucket Cloud. A self-managed GitLab test uses Testcontainers `gitlab/gitlab-ce`, weekly because it's slow.
- Migration: test of the `github_id` → `oauth_accounts` move on a staging copy.

---

## 10. Milestones

1. **AI platform:** interface v2, Anthropic and OpenAI providers, usage tracking, limits, safety tests.
2. **AI release notes:** the deterministic fallback first, then AI.
3. **AI migration guide:** generator signature extraction, sandbox snippet compilation.
4. **AI spec assistant:** backend streaming plus edit splicing, then the editor panel.
5. **Multi-account identity:** `oauth_accounts` migration, account linking, provider-aware orgs.
6. **GitLab provider:** client, webhooks, CI output, Pages, self-managed instances.
7. **Bitbucket provider:** client, webhooks, Pipelines output.
8. **Three-provider end-to-end matrix in CI**, then GA.

Each milestone gets its own implementation plan and ships behind its flag.
