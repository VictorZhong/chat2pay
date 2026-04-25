# chat2pay TODO

Living checklist for the V1 → V1.x cleanup pass. Items are grouped by priority,
not by component. Tick a box when the change is merged. Add a one-line note when
something is intentionally deferred.

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
      - "Add a new payment tool" — register in `PaymentToolDefinitions.all()`,
        add a handler in the new `PaymentToolRegistry`, extend
        `IntentInterpreter.fromToolCall` if needed, add a unit test.
      - "Add a new test profile" — insert into `ctp_profile`, configure
        capabilities + currency + debit account; how to seed payees in
        `V2__seed_payees.sql` for mock mode; how to test against real
        downstream by setting `PAYMENT_MOCK_ENABLED=false`.
- [x] Cross-link `TODO.md` from `README.md`.

---

## Done

- 2026-04-26: Completed P0, P1, P2, P4, plus `normalizeToolCalls` warn logging.
- 2026-04-26: Completed P3 orchestrator split into `ChatBlockFactory`, `ConversationStateMachine`, and `PaymentToolRegistry`.
