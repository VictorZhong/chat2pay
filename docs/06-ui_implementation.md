# chat2pay - UI Implementation

## 1. Purpose

This document defines how the current frontend UI should be used for the next
backend-backed version of the POC.

The direction is:

- keep the current visual shell
- simplify the interaction model
- remove the heavy transfer-flow treatment
- let the Java backend drive the conversation

## 2. What Stays Stable

The current UI structure is already close enough and should remain:

- profile selector page
- profile selector page with fixed POC access login
- left sidebar with session history
- right chat workspace
- branded chat message styling
- structured assistant blocks inside the conversation

This iteration is mainly a data-flow and behavior change, not a redesign.

## 3. What Changes

### 3.1 Remove "Transfer Flow"

Do not keep a large step-tracker mental model in the UI.

Specifically:

- remove the "Transfer Flow" framing
- do not model the payment experience as a separate wizard
- do not add payment-rail or proposal-specific panels in V1

If session progress needs to be visible, use a **small status summary** in the
header instead:

- `Draft`
- `Awaiting payee selection`
- `Awaiting confirmation`
- `Processing`
- `Completed`
- `Failed`

### 3.2 Narrow the Scope

The frontend only needs to support these V1 conversation outcomes:

- registered payee lookup
- domestic payment to a registered payee
- clarification questions
- payee disambiguation
- explicit confirmation
- success or failure result

No international flow UI is needed in V1.

## 4. Page-Level Direction

## 4.1 Profile Selection

Implemented in:

- `chat2pay-web/src/pages/profile-selector/ProfileSelectorPage.tsx`

Target behavior:

- fetch profiles from the Java backend
- submit the fixed POC access password `tb123`; do not ask the user for the
  downstream profile password
- allow the backend to return only one or a few demo profiles
- treat `username` as backend-owned integration context, not a frontend concern

## 4.2 Chat Workspace

Implemented in:

- `chat2pay-web/src/pages/chat-workspace/ChatWorkspacePage.tsx`

Target behavior:

- keep the existing layout
- replace mock orchestration with backend responses
- show streaming assistant output when enabled
- show backend-provided structured blocks for payee choices and confirmations

## 5. Streaming Interaction Model

The frontend should support **HTTP streaming from the Java backend**.

Recommended approach:

- use normal JSON REST for profile and session loading
- use streamed HTTP response handling for message turns
- parse backend SSE-style events from `fetch` response streams

Why this is enough:

- the frontend does not need a browser-to-LLM connection
- the backend is the only system that talks to the provider
- V1 does not justify WebSocket complexity

Expected streamed events:

- `user-message`
- `assistant-message-start`
- `assistant-message-delta`
- `assistant-message-complete`
- `turn-error`

The frontend should also support a non-streaming JSON fallback path.

## 6. Structured Blocks to Keep

The current chat UI already has the right primitives.

V1 should keep using:

- text blocks for normal assistant conversation
- selectable list blocks for payee disambiguation
- summary card blocks for confirmation and final outcome
- info or error blocks for unsupported actions and failures

Use simple forms sparingly.

Preferred order:

1. free-text user input
2. selectable payee list when needed
3. confirmation summary card

When a selectable list, confirmation card, or form is the required next step,
the chat composer should be disabled and the user should continue through the
provided control. The control should include a clear fallback path when none of
the options match the user's intent, so the next backend turn can return to
free-text clarification.

This keeps the conversation close to the successful Python demo behavior.

## 7. Frontend Responsibilities

| Area | Responsibility |
|---|---|
| Profile selector | List profiles and submit fixed POC access login |
| Sidebar | Create, switch, and list chat sessions |
| Chat workspace | Render messages, blocks, and streaming deltas |
| Structured interactions | Submit list selections and action clicks back to backend |
| Session header | Show light status, not a full transfer wizard |

## 8. Explicit Frontend Non-Responsibilities

- no intent detection in the frontend
- no direct downstream API access
- no direct LLM access
- no provider token handling
- no local branching for domestic vs international
- no hardcoded payment workflow graph

## 9. Mock Flow Guidance

Current mock behavior should be simplified to match the target backend scope.

Remove or stop expanding mock concepts such as:

- payment rail selection
- proposal flow
- limit check stages
- international placeholders
- extra workflow states that do not exist in the backend design

The mock layer, if still used temporarily, should only mirror:

- payee lookup
- domestic payment draft collection
- payee selection
- explicit confirmation
- success or failure

## 10. Source of Truth

For frontend implementation decisions, use this order:

1. current UI code in `chat2pay-web/`
2. `docs/01-system_design.md`
3. `docs/06-ui_implementation.md`
4. `docs/02-api_contract.yaml`
5. `docs/99-ref.md`
