# chat2pay PLAN

Architecture and delivery plan for the next stage of Chat2Pay.

Use this file for sequencing, scope boundaries, and "why this before that".
Keep [TODO.md](TODO.md) for implementation-level checklists inside the current
phase.

Legend: `[ ]` planned · `[~]` in progress · `[x]` done · `[-]` intentionally deferred

---

## Current Assessment

- [x] V1 domestic payment flow is usable end to end.
- [x] Both domestic LLM modes are working: `COPILOT_PERSONAL` and `REMOTE_API`.
- [x] The backend already has useful foundations: capability registry, typed
      downstream clients, policy guard entry points, structured UI blocks, and a
      dedicated domestic journey service.
- [ ] The current runtime and state model are still domestic-first in ways that
      will make cross-border and email-channel work awkward if we keep extending
      them directly.

The main architectural gaps are:

1. Debit account is still profile-owned at execution time instead of
   payment-owned.
2. Draft/session state still assumes one domestic collect-confirm-execute path.
3. Runtime execution is still shaped around domestic methods instead of a stable
   journey/capability boundary.
4. Intent fallback is still too flat for cross-border fields and future channel
   metadata.
5. Session/message persistence does not yet model channel identity and external
   message envelopes.

---

## Working Rule

- `PLAN.md` answers: what order, why, and what must be true before the next
  phase starts.
- `TODO.md` answers: which concrete code changes are currently open.

If something changes the sequencing or architectural boundary, update this file
first. If something is just an implementation task within the current phase,
update `TODO.md`.

---

## Recommended Sequence

## Phase 0 — Stabilize the V1 Base

- [x] Domestic chat flow, confirmation gating, provider fallback, and local test
      isolation.
- [x] Capability registry introduced as the first step away from tool-name
      sprawl.
- [x] Domestic orchestration extracted out of the original monolith service.

Exit criteria:

- `mvn test` stays service-independent.
- Copilot and remote LLM remain swappable in yaml.
- No new V2 work bypasses the existing backend confirmation guardrails.

## Phase 1 — Make Debit Account a Payment-Level Choice

Status: next

This should be the first substantive V2 change.

Why first:

- It fixes a real domestic product gap immediately.
- Cross-border cannot be modelled cleanly if the funding account still lives
  outside the draft/journey.
- It forces the draft, UI blocks, and downstream request builder to start
  carrying user-selected source-account state instead of relying on profile
  defaults.

Scope:

- Add a typed `selectedDebitAccount` concept to the active payment draft.
- Stop building domestic payment payloads from
  `profile.requiredDebitAccountNumber()` alone.
- Add semantic capabilities for account discovery/selection, for example
  `list_debit_accounts` and `select_debit_account`, or the equivalent journey
  steps if the implementation remains UI-event driven.
- Show the selected debit account in the confirmation summary.
- Keep the profile-level debit account only as a bootstrap default or fallback
  during migration, not as the final source of truth for execution.

Likely touch points:

- `chat2pay-app/src/main/java/com/chat2pay/app/api/dto/ChatDtos.java`
- `chat2pay-app/src/main/java/com/chat2pay/app/application/conversation/ConversationStateMachine.java`
- `chat2pay-app/src/main/java/com/chat2pay/app/application/conversation/DomesticPaymentJourneyService.java`
- `chat2pay-app/src/main/java/com/chat2pay/app/integration/downstream/domestic/HttpDomesticPaymentClient.java`
- `chat2pay-app/src/main/resources/db/migration/`
- `chat2pay-web/src/features/`

Exit criteria:

- Domestic payment can be prepared and confirmed against a user-selected debit
  account.
- The selected account is persisted in the active draft.
- Execution no longer depends on a hidden profile hardcode.

## Phase 2 — Widen the Journey Model

Status: start immediately after Phase 1, or in parallel only where write scopes
do not overlap

Why second:

- Once debit account becomes payment-owned, the next bottleneck is the
  domestic-only state machine.
- Cross-border will need proposal review, expiry, and richer detail collection
  that do not fit the current generic `AWAITING_CONFIRMATION` shape.

Scope:

- Introduce a first-class journey model:
  `journeyType`, `journeyState`, and typed or well-bounded `journeyContext`.
- Stop creating every new draft as `DOMESTIC_PAYMENT` by default.
- Separate common session lifecycle from rail-specific journey lifecycle.
- Make the backend own transition rules for domestic and future cross-border
  states.

Suggested direction:

- Keep one active journey per session for now.
- Preserve a shared chat UI, but let the backend decide which journey is active.
- Prefer typed state/context over shoving more behavior into unstructured draft
  JSON.

Exit criteria:

- Domestic still works unchanged from a user perspective.
- A new cross-border journey type can be introduced without branching the entire
  orchestrator.

## Phase 3 — Replace Domestic-Centric Runtime Dispatch

Status: after Phase 2 foundations are in

Why third:

- The current `PaymentToolActions` and orchestrator contract still assume one
  domestic implementation boundary.
- Adding cross-border, account selection, or channel-triggered flows should
  plug into a stable execution contract instead of growing more
  `if domestic else ...` branches.

Scope:

- Replace domestic-specific runtime interfaces with a journey or capability
  execution boundary.
- Keep function calling and tool registry, but route tools into a registry of
  handlers/journeys rather than directly into domestic methods.
- Keep raw downstream REST details behind typed Java clients.

Non-goal:

- Do not move payment core logic into MCP or multi-agent orchestration.

Exit criteria:

- Domestic and cross-border can both register execution handlers behind the same
  backend contract.
- The model still sees safe semantic tools, not raw bank APIs.

## Phase 4 — Add Cross-Border ORTT Properly

Status: blocked on Phases 1-3

Why after the refactor:

- Cross-border needs proposal/confirm semantics, not the V1 domestic direct
  confirm path.
- It will add rail-specific data such as charge bearer, FX/fees, purpose code,
  beneficiary details, and proposal expiry.

Scope:

- Add `propose_cross_border_payment` and `confirm_cross_border_payment`
  capabilities.
- Persist proposal identifiers / execution tokens owned by the backend.
- Add review blocks for proposal details, fees, and expiry.
- Add typed cross-border downstream connector modules and policy checks.

Exit criteria:

- Cross-border confirmation never trusts model-supplied raw payment fields when
  executing.
- Backend executes from a persisted proposal created earlier in the journey.

## Phase 5 — Add Channel Envelope and Email Ingestion

Status: after core payment journeys are stable

Why later:

- The UI can stay unified, but channel ingestion should normalize into the same
  backend turn/journey system only after the core execution model is clean.
- Email-triggered payment is higher risk and should inherit the same proposal,
  confirmation, and audit model instead of inventing a parallel shortcut.

Scope:

- Add first-class channel metadata to sessions/messages:
  `channel`, `externalConversationId`, `externalMessageId`, `sender`,
  `receivedAt`, and delivery/audit metadata.
- Introduce an inbound envelope mapper so web chat and email both become the
  same internal "turn" type.
- Decide explicit policy for email-triggered payments:
  default to proposal creation or secondary confirmation rather than blind
  auto-execution.

Exit criteria:

- Web chat and email share the same journey engine.
- Audit, replay, and idempotency are channel-aware.

## Phase 6 — Optional MCP Facade

Status: deferred

MCP can help as an adapter layer for inboxes, knowledge systems, or safe
read-only enterprise integrations. It should not be the core runtime abstraction
for bank payment execution.

Use MCP only if:

- it cleanly wraps a connector boundary, and
- the underlying capability already exists inside chat2pay's own backend.

Do not use MCP to replace:

- typed downstream bank clients
- backend confirmation/policy gates
- persisted proposal semantics

---

## First Concrete Build Order

If we start implementing now, the order should be:

1. [ ] Add debit-account selection to the domestic draft and confirmation flow.
2. [ ] Switch domestic downstream execution to the selected account.
3. [ ] Widen draft/session state to carry journey type and rail-specific state.
4. [ ] Replace domestic-centric execution interfaces with a stable journey or
       capability handler boundary.
5. [ ] Add cross-border ORTT proposal/confirm.
6. [ ] Add channel envelope and email ingestion.

This means: yes, fixing the hardcoded debit account should come before starting
real cross-border implementation. But it should be done as the first step of a
broader journey-model cleanup, not as a one-off patch.
