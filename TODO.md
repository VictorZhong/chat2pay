# chat2pay TODO

Living checklist for the V1 → V1.x cleanup pass. Items are grouped by priority,
not by component. Tick a box when the change is merged. Add a one-line note when
something is intentionally deferred.

Architecture sequencing now lives in [PLAN.md](PLAN.md). Keep this file for
implementation-level tasks inside the current phase.

Legend: `[ ]` open · `[x]` done · `[~]` in progress · `[-]` won't do (POC)

---

## P0 — User-facing gaps that block daily use

- [x] **Chat history grows forever.** Add `DELETE /api/chat/sessions/{sessionId}`
      (backend cascades messages + draft), a delete affordance on each item in
      the sidebar (`ChatHistoryList`), and the equivalent in the mock server.
      Confirm before delete; if the deleted session is the active one, navigate
      away.
- [x] **My Accounts / My Payees quick actions don't actually start a chat.**
      Today they call `onQuickAction(prompt)` which only forwards to
      `startChatWithMessageMutation` if no session is active. They should
      *always* create a fresh session and post the prompt as a real user message
      (so the assistant turn appears in chat history).
- [x] **Loading feedback is too quiet.** When a turn is in flight, show an
      inline assistant skeleton entry (avatar + 3-dot pulse) inside
      `MessageList`, matching brand tokens. Reuse existing brand classes
      (`brand-message-entry`, `brand-status-indicator animate-pulse`).
      No new spinner libs.

## P1 — Correctness / data integrity

- [x] **Amount must be `BigDecimal` end-to-end.** Touch
      `ChatDtos.PaymentDraft.amount`, `DomesticPaymentClient.DomesticPaymentRequest.amount`,
      `ChatOrchestratorService` arithmetic + formatting, the orchestrator's
      `numberArg`, and the mock server / TS contract (still `number` in TS, but
      sent as a string from the backend with `JsonFormat(shape=STRING)` so we
      never lose precision over the wire).
- [x] **Persistence: one transaction per turn.** Replace the
      `WriteThroughMessages` AbstractList in `SessionStore` with a method like
      `applyTurn(profileId, sessionId, mutator)` that opens **one**
      `@Transactional` unit and writes user message, assistant message, session
      header, draft. Make the in-memory message list thread-safe (snapshot copy
      at read time, no synchronized blocks at the controller).
- [x] **`ChatStreamService` thread pool is unbounded.** Replace
      `Executors.newCachedThreadPool` with a small bounded pool (e.g. 16) +
      meaningful thread names. Reject excess work cleanly.

## P2 — UX polish

- [x] **Re-think `IDLE` / `ACTIVE` display.** `ConversationState=IDLE` plus
      `ChatSessionStatus=ACTIVE` confuses end users (the badge says "Active" on
      a session that's done its work). Decide:
      1. Hide `IDLE` entirely from end users; only surface
         `COLLECTING_DETAILS / AWAITING_PAYEE_SELECTION / AWAITING_CONFIRMATION /
         EXECUTING / COMPLETED / FAILED / CANCELLED`.
      2. Replace the binary `StatusBadge(value=status)` in the header with a
         single derived "Conversation status" pill backed by `state` first and
         `status` only when it's terminal.
      3. Drop the "ACT" abbreviation in the collapsed sidebar.
- [x] **Timestamps should show a timezone.** `formatDateTime` currently uses
      browser locale and drops the zone. Change it to format with `timeZoneName:
      'short'` (e.g. `Apr 26, 14:32 HKT`). For the chat history sidebar a
      shorter relative form (`2 hours ago`) is fine but tooltip should show the
      absolute ISO string.
- [x] **AI-generated session title.** Today the title is hard-coded
      ("New conversation") or derived from a regex (`Pay <Name>`). Two-step:
      1. After the second assistant turn (or on payment success), call the LLM
         with a short summarization prompt to suggest a 4–6 word title; the
         orchestrator updates `ctp_chat_session.title` if the user has not
         renamed.
      2. Add `PATCH /api/chat/sessions/{id}` (`{ title }`) and a small "rename"
         affordance on the active session header (pencil icon → inline edit).
         Manual renames should set a `title_locked` flag so step 1 stops
         touching that session.

## P3 — Code health

- [x] **`ChatOrchestratorService` is 1095 lines.** Split into:
      - `ConversationStateMachine` (transition + draft mutations)
      - `PaymentToolRegistry` (replaces the switch in `executeLlmToolCall`;
        `Map<String, PaymentTool>` so adding a tool is one new file)
      - `ChatBlockFactory` (text/info/error/summary/selectable builders)
      - `ChatOrchestratorService` keeps the public API (`handleUserMessage`,
        `handleUiEvent`, `welcomeMessage`) and just composes the above.
      No behavior change. All existing tests must keep passing as-is.
- [x] `normalizeToolCalls` silently drops `toolCalls.get(1..)`. Log a `warn`
      with the dropped tool names so we don't lose the signal.
- [-] (Deferred) Real LLM token-level streaming — the SSE stream is currently
      synthesized post-hoc. Worth noting in the design doc.

## P3.5 — Remote LLM (IB2B) + per-use-case model selection

Goal: keep the existing Copilot path untouched, add a real "remote LLM" backend
that lives on the corporate intranet (no proxy), make the provider+model
selection configurable per use-case (chat-title vs intent vs main chat) so
unimportant calls can use a cheaper local model, and keep a graceful fallback so
operators can flip primary REMOTE↔COPILOT in `application.yml` without code
changes.

- [x] **Config surface in `application.yml` / `Chat2PayProperties`.**
      Add `chat2pay.use-cases.{title,intent,chat}.{provider,model}` (each
      optional, falls back to the global `primary-provider`/`fallback-provider`).
      Add `chat2pay.remote.ib2b.{token-url,username,password,token-ttl-seconds}`
      and `chat2pay.remote.models[]` (each entry has `name`, `url`, `auth`
      (`BEARER`|`IB2B`), `api-key`, `user`, `max-completion-tokens`). Keep the
      existing `chat2pay.remote.{base-url,api-key,model,max-completion-tokens}`
      working as a single-model fallback when `models[]` is empty.
- [x] **`IB2BTokenClient`.** POST the credential→JWT translator, cache the
      `issued_token` for `token-ttl-seconds` (default 600s), expose
      `currentToken()` + a forced refresh on 401. Add per-request
      `X-zzzz-Request-Correlation-Id` UUID v4 helper.
- [x] **`LlmUseCase` enum** (`CHAT`, `INTENT`, `TITLE`) and
      `LlmRouter.select(LlmUseCase)` returning a `(LlmProvider, modelOverride)`
      bundle. `current()`/`currentIfAvailable()` keep working unchanged
      (CHAT is the default use case).
- [x] **`LlmCompletionRequest.model`.** Add an optional `model` field; both
      providers honor it when set, otherwise use their configured default.
- [x] **`RemoteApiLlmProvider` refactor.** Resolve `(url, auth, apiKey, user,
      maxTokens)` from the model registry by request model name. `auth=IB2B`
      adds `X-zzzz-E2E-Trust-Token` (from `IB2BTokenClient`) +
      `X-zzzz-Request-Correlation-Id`; `auth=BEARER` keeps current behavior.
      Falls back to legacy single-model config if the model name is unknown.
- [x] **Copilot model override.** `CopilotPersonalLlmProvider` honors
      `request.model()` so per-use-case YAML can pick a different Copilot model
      without changing the global default.
- [x] **Wire call sites.** `SessionTitleSuggester` → `TITLE`, `IntentInterpreter`
      → `INTENT`, `ChatOrchestratorService.handleWithLlmToolLoop` → `CHAT`. Each
      passes the resolved `modelOverride` into `LlmCompletionRequest`.
- [x] **Compile + smoke check.** `mvn -q compile` and `mvn -q test` both pass
      locally without PostgreSQL or any remote service dependency. The Spring
      Boot smoke test now runs with lazy init and DB/Flyway auto-config disabled,
      while targeted unit tests cover chat/intent/title routing. YAML toggle
      examples are documented in `README.md`.

## P4 — Docs

- [x] **Move `docs/05-project_structure.md` into the root `README.md`** under a
      "Project structure" section, then delete `docs/05-project_structure.md`.
- [x] **Delete `docs/06-ui_implementation.md`.** Its content is mostly absorbed
      into the running frontend; what remains useful (block primitives) should
      live in a short paragraph in `README.md`.
- [x] **Expand `docs/01-system_design.md`.** Add a "How the LLM tool loop
      really works" section with: which messages are forwarded to the model
      (system prompt + last 12 turns), how `tool_choice="auto"` is used, what
      counts as terminal, the `MAX_TOOL_LOOP_ITERATIONS` cap, the SSE delta
      synthesis, and where backend guardrails fire (confirmation gating, draft
      validation).
- [x] **Add `docs/04-extending.md`** with two cookbooks:
      - "Add a new payment tool" — register semantic metadata in
        `CapabilityRegistry`, add a handler in `PaymentToolRegistry`, extend
        `IntentInterpreter.fromToolCall` if needed, add a unit test.
      - "Add a new test profile" — insert into `ctp_profile`, configure
        capabilities + currency + debit account; how to seed mock payee
        fixtures in `V2__seed_payees.sql`; how to test against real downstream
        by setting `PAYMENT_MOCK_ENABLED=false`.
- [x] Cross-link `TODO.md` from `README.md`.

## V2 Architecture Readiness — Capability / Journey foundation

### P0 — Do before adding many downstream APIs

- [ ] **Make debit account a first-class payment choice.** Add selected source
      account data to the active draft, show it in the confirmation summary,
      and stop executing domestic payments from the hidden profile debit-account
      default alone. This is the first V2 change to land before real
      cross-border work.
- [x] **Define the semantic capability model.** Create a short design doc or
      section listing V2 capabilities (`listAccounts`, `getAccountDetails`,
      `listPayees`, `getPayeeDetails`, `listTransactionHistory`,
      `getPaymentOptions`, `checkEligibility`, `checkLimit`,
      `confirmDomesticPayment`, `proposeCrossBorderPayment`,
      `confirmCrossBorderPayment`, `runFraudCheck`), their inputs, outputs,
      risk level, required user confirmation, and whether each is read-only or
      side-effecting. Payee capabilities must be live downstream reads in real
      mode, not DB directory reads.
- [~] **Standardize V2 terminology and rail scope.** Use `cross-border payment`
      in docs/product/code when touching V2, model the POC rail as ORTT only,
      and leave GD/non-ORTT rails out of scope. Treat existing
      `INTERNATIONAL_PAYMENT` names as legacy placeholders to rename when the
      V2 contract/code is changed. Backend intent/tool names now use
      cross-border terminology with a legacy `unsupported_international_payment`
      alias; public enum cleanup is still pending.
- [~] **Retire DB-backed payee directory assumptions.** Real-mode
      `listPayees` / `getPayeeDetails` must call downstream APIs at request
      time. Keep `ctp_registered_payee` / `ctp_payee_alias` only as local mock
      fixtures or move them behind a test-only fixture module. Payee
      create/update flows should call downstream APIs directly. Current code
      uses `PayeeStore` as a live-downstream-or-mock facade; physical fixture
      table retirement is still pending.
- [x] **Introduce a `CapabilityRegistry`.** Move from payment-tool-name routing
      toward semantic capabilities with metadata: capability id, description,
      input schema, output type, risk level, required state, and confirmation
      policy. Keep LLM tool definitions generated from this registry where
      practical.
- [~] **Extract a real payment journey state machine.** Model V2 states for
      account selection, payee selection, payment-option selection,
      eligibility/limit/fraud checks, domestic direct-confirm review,
      cross-border proposal review, explicit confirmation, execution,
      held/rejected/completed/failed/cancelled. State transitions should be
      backend-owned and unit tested. Domestic V1 journey logic has been moved
      out of `ChatOrchestratorService` into `DomesticPaymentJourneyService`;
      V2 cross-border states are still pending.
- [ ] **Add cross-border ORTT proposal/confirm execution.** Domestic V1 remains
      direct confirm after backend validation and explicit user confirmation.
      Cross-border ORTT must introduce backend-owned `paymentProposalId` /
      execution token semantics so `confirmCrossBorderPayment` confirms a
      persisted proposal rather than trusting model-supplied raw
      account/payee/amount fields.

### P1 — Connector and policy hardening

- [ ] **Create typed REST connector modules by domain.** Split downstream
      clients into `accounts`, `payees`, `transactions`, `payment-options`,
      `limits`, `eligibility`, `fraud`, and `payments`, with consistent auth,
      timeout, retry, correlation-id, idempotency, and downstream error mapping.
- [~] **Add a policy guard layer.** Centralize rules for PII exposure,
      capability authorization, confirmation requirements, side-effect gating,
      idempotency keys, and audit metadata before any capability can call a
      downstream mutating API. A first `PaymentPolicyGuard` now gates domestic
      confirmation and blocks cross-border in V1; broader PII/idempotency/audit
      policy is still pending.
- [ ] **Normalize capability result envelopes.** Use a common result shape for
      success, missing-details, user-choice-needed, blocked-by-policy,
      downstream-error, and terminal execution states so the chat renderer does
      not need capability-specific branching.
- [ ] **Expand persistence for V2 journeys.** Add tables/columns for payment
      proposals for cross-border ORTT, selected source account, payment option,
      eligibility/limit/fraud results, audit correlation ids, and idempotency
      keys. Avoid storing sensitive raw downstream payloads unless explicitly
      needed, and do not store a durable real payee directory.

### P2 — LLM integration and observability

- [~] **Generate LLM tool specs from capabilities.** Derive model-facing tool
      schemas from the capability registry, but keep only the safe semantic
      capabilities visible to the model. Do not expose raw REST endpoints.
      V1 tool schemas now come from `CapabilityRegistry`; V2 capabilities are
      still pending.
- [ ] **Add capability-level tests.** For each capability, cover happy path,
      missing details, downstream failure, policy block, and side-effect gating.
      Add journey tests for common payment scenarios across multiple turns.
- [ ] **Add execution tracing.** Log capability id, journey state, proposal id,
      downstream correlation ids, policy decisions, and timing. Keep sensitive
      values redacted by default.
- [ ] **Plan MCP as an optional facade only.** Do not add MCP to the V2 payment
      core. Keep capability interfaces clean enough that a future MCP server can
      expose selected read-only or low-risk capabilities without duplicating
      business logic.

---

## Done

- 2026-04-27: Stop letting regex hijack capability/meta questions. The tool-loop
  no longer falls back to the deterministic intent path just because the user
  mentions "send"/"payment"; we now trust the LLM's text response unless the
  user is explicitly confirming/cancelling at AWAITING_CONFIRMATION. Tightened
  the LLM prompts (reject "a new"/"another"/"someone" as payee names; explicitly
  list out-of-scope items including new-payee creation and how-Chat2Pay-works
  questions) and added a defensive `sanitizePayeeQuery` helper applied to LLM
  tool-call arguments. Confirmation card now ships an editable-payment-date
  control: backend emits `editableFields` metadata, FE renders an `<input
  type="date">`, the picked value is sent in `formValues` on
  CONFIRM_PAYMENT, and the orchestrator updates the draft before executing.
- 2026-04-26: Completed P0, P1, P2, P4, plus `normalizeToolCalls` warn logging.
- 2026-04-26: Completed P3 orchestrator split into `ChatBlockFactory`, `ConversationStateMachine`, and `PaymentToolRegistry`.
- 2026-04-26: Started V2 refactor foundation: added `CapabilityRegistry`,
  `PaymentPolicyGuard`, `DomesticPaymentJourneyService`, canonical cross-border
  unsupported tool naming with legacy alias, and a cross-border ORTT client
  boundary. Preserved both `COPILOT_PERSONAL` and `REMOTE_API` providers.
- 2026-04-26: Moved downstream source system ids into profile-scoped runtime
  config and kept debit account number, product category code, and payment
  currency profile-owned for real downstream payment calls.
