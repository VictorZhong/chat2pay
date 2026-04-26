# chat2pay - System Design

## 1. Purpose

This document defines the updated POC design for **chat2pay**.

The goal is to keep chat2pay as a backend-orchestrated payment assistant: the
frontend stays conversational, the backend owns payment policy and downstream
API sequencing, and the model only sees safe semantic tools.

This document is also the source of truth for the current product boundary:
V1 covers domestic registered-payee payment with direct confirmation; V2 grows
into cross-border payment on the ORTT rail with a propose-then-confirm flow.

The implementation stack stays the same:

- frontend: React + TypeScript
- backend: Spring Boot
- database: PostgreSQL

The frontend never talks to an LLM directly and never calls downstream payment
APIs directly.

## 2. Scope

### 2.1 Version 1

Version 1 covers the domestic registered-payee payment journey in the Java
backend:

- support personal-subscription GitHub Copilot access from the backend
- support registered payee lookup
- support domestic payment to a registered payee
- execute V1 domestic payment by calling confirmation directly after backend
  validation and explicit user confirmation; there is no propose step in V1
- support multi-turn clarification with the user
- support exactly two business downstream APIs:
  - `PAYEE_URL` for live registered-payee lookup
  - `CONFIRM_DOMESTIC_PAYMENT_URL` for direct domestic confirmation
- obtain downstream SAML token before each downstream business call
- persist sessions, messages, and active draft in PostgreSQL
- no Redis

### 2.2 Version 2

Version 2 extends the same architecture:

- add cross-border payment on the ORTT rail
- execute cross-border ORTT payment through separate propose and confirm APIs
- defer GD and other non-ORTT rails outside the POC
- add more downstream tools and APIs
- use a real LLM API as the primary provider
- keep GitHub Copilot personal-subscription access as a fallback option

## 3. Explicit Non-Goals for V1

- no frontend-to-LLM connection
- no frontend-to-downstream API connection
- no Redis
- no WebSocket requirement
- no code generation from the API contract
- no duplicate contract copy under a separate `api-contract/` directory
- no complex frontend transfer wizard or "Transfer Flow" modal
- no cross-border payment execution in V1
- no GD rail support in the POC
- no payee creation or update in V1; future create/update flows should call
  downstream APIs directly, not write a local payee directory

## 4. Design Principles

1. **Backend owns orchestration.**
   The Java backend decides which LLM provider to use, which tools are exposed,
   when a tool may run, and how downstream responses become frontend blocks.

2. **Frontend stays thin.**
   The current frontend shell is kept, but it only renders sessions, messages,
   and backend-provided structured blocks.

3. **Provider access must be pluggable.**
   V1 uses GitHub Copilot personal-subscription access. V2 can switch the
   primary provider to a real API without changing frontend contracts.

4. **Tool execution must be extensible.**
   V1 only needs two downstream business tools, but the backend should be built
   as a tool registry so V2 can add cross-border ORTT payment and more checks
   without rewriting the chat controller.

5. **Critical side effects stay guarded.**
   The LLM may infer intent and request tool calls, but the backend must enforce
   confirmation and payload validation before payment execution. V1 domestic
   payment directly confirms a complete draft; V2 cross-border ORTT payment
   must confirm a backend-owned proposal or execution token.

6. **PostgreSQL is the only shared state store in V1.**
   Session state, message history, and active payment draft live in PostgreSQL.
   Any in-memory cache is optional, local, and non-authoritative.

7. **Payee data is not owned by chat2pay.**
   Real payee lookup, creation, and update flows use downstream APIs as the
   source of truth. Any local payee rows are mock fixtures only.

8. **The UI should feel conversational, not workflow-heavy.**
   The user interacts through chat messages, payee choices, and confirmation
   cards. We do not build a separate transfer wizard.

## 5. High-Level Architecture

```mermaid
flowchart LR
    subgraph FE["React Frontend"]
        FE1["Profile Selector"]
        FE2["Sidebar"]
        FE3["Chat Workspace"]
        FE4["Structured Block Renderer"]
        FE5["Streaming Response Reader"]
    end

    subgraph BE["Spring Boot Backend"]
        BE1["Profile API"]
        BE2["Chat API"]
        BE3["Conversation Orchestrator"]
        BE4["Provider Router"]
        BE5["Policy Guard"]
        BE6["Payment Tool Facade"]
        BE7["Response Renderer"]
        BE8["Session Service"]
        BE9["Copilot Personal Provider"]
        BE10["Future Remote API Provider"]
        BE11["Downstream Auth Service"]
        BE12["Registered Payee Client"]
        BE13["Domestic Payment Client"]
        BE14["Capability Registry"]
        BE15["Domestic Payment Journey Service"]
    end

    subgraph DB["PostgreSQL"]
        DB1[("ctp_profile")]
        DB2[("ctp_chat_session")]
        DB3[("ctp_chat_message")]
        DB4[("ctp_payment_draft")]
        DB5[("mock ctp_registered_payee / ctp_payee_alias")]
        DB6[("ctp_llm_credential")]
    end

    subgraph LLM["LLM Providers"]
        L1["GitHub Copilot Personal"]
        L2["Future Real LLM API"]
    end

    subgraph DS["Downstream APIs"]
        D1["LOGIN_URL"]
        D2["PAYEE_URL"]
        D3["CONFIRM_DOMESTIC_PAYMENT_URL"]
        D4["Future Cross-Border ORTT APIs"]
    end

    FE1 --> BE1
    FE2 --> BE2
    FE3 --> BE2
    FE4 --> BE2
    FE5 --> BE2

    BE2 --> BE3
    BE3 --> BE4
    BE3 --> BE5
    BE3 --> BE6
    BE3 --> BE7
    BE3 --> BE8
    BE3 --> BE14
    BE3 --> BE15
    BE15 --> BE5
    BE15 --> BE6

    BE4 --> BE9
    BE4 --> BE10
    BE9 --> L1
    BE10 --> L2

    BE6 --> BE12
    BE6 --> BE13
    BE12 --> BE11
    BE13 --> BE11
    BE11 --> D1
    BE12 --> D2
    BE13 --> D3
    BE6 -. future .-> D4

    BE8 --> DB1
    BE8 --> DB2
    BE8 --> DB3
    BE8 --> DB4
    BE12 -. mock only .-> DB5
    BE9 --> DB6
```

## 6. Capability Model

### 6.1 V1 Capabilities

V1 exposes only two payment-related business capabilities:

| Capability | User-facing purpose | Downstream tool |
|---|---|---|
| `REGISTERED_PAYEE_LOOKUP` | Find one or more registered payees | `get_registered_payees` |
| `DOMESTIC_PAYMENT` | Pay a registered domestic payee | `confirm_domestic_payment` |

`REGISTERED_PAYEE_LOOKUP` may be used on its own, or as part of the domestic
payment journey. V1 domestic payment goes directly to the confirmation API
after the backend has a complete draft and the user explicitly confirms.

### 6.2 V2 Extension Direction

V2 adds capabilities without changing the outer chat contract:

- `CROSS_BORDER_PAYMENT_ORTT`
- extra downstream checks
- more tool definitions
- real API LLM provider as primary
- no GD rail support in the POC

### 6.3 V2 Capability Layering Direction

V2 should not model every downstream REST endpoint as a separate LLM-visible
tool. The mature REST APIs should stay behind strongly typed backend connectors,
while the model sees a smaller set of semantic business capabilities.

Target layering:

```text
Chat API / SSE
  -> Conversation Orchestrator
  -> Journey State Machine + Policy Guard
  -> Semantic Capability Registry
  -> Capability Services
  -> REST API Connectors
  -> Downstream Banking / Payment APIs
```

The capability layer should expose business operations such as:

- `listAccounts`
- `getAccountDetails`
- `listPayees`
- `getPayeeDetails`
- `listTransactionHistory`
- `getPaymentOptions`
- `checkEligibility`
- `checkLimit`
- `confirmDomesticPayment`
- `proposeCrossBorderPayment`
- `confirmCrossBorderPayment`
- `runFraudCheck`

Initial implementation status:

| Capability | Inputs | Output type | Risk | Confirmation |
|---|---|---|---|---|
| `listPayees` | optional `nameQuery` | `payeeList` | read-only | none |
| `prepareDomesticPayment` | payee query, amount, payment date | `paymentDraft` | draft mutation | none |
| `confirmDomesticPayment` | active validated draft | `paymentConfirmation` | side-effecting | explicit user confirmation |
| `cancelPayment` | active draft | `paymentCancellation` | draft mutation | none |
| `unsupportedCrossBorderPayment` | user request context | `unsupportedCapability` | unsupported | blocked in V1 |

The Java code now has `CapabilityRegistry` metadata for the implemented V1
capabilities and derives model-facing tool definitions from that registry. The
V2 table should extend this same registry with account, transaction,
eligibility, limit, fraud, payment-option, and cross-border ORTT propose/confirm
capabilities instead of adding raw REST endpoints as tools.

Those names are intentionally semantic. A capability may call one or more
downstream APIs, normalize errors, apply policy, and return frontend-ready
blocks or structured intermediate state. This keeps the model from having to
understand endpoint sequencing, auth details, idempotency, or raw downstream
payload quirks.

High-risk side effects must use backend-owned state. V1
`confirmDomesticPayment` can directly confirm a validated domestic draft after
explicit user confirmation. V2 `confirmCrossBorderPayment` must confirm a
backend-generated cross-border proposal or execution token returned by
`proposeCrossBorderPayment`; it should not accept a model-reconstructed
payee/account/amount payload as the source of truth.

### 6.4 MCP Decision

MCP is useful as a standardized AI integration protocol for tools, resources,
and prompts. It can be useful later as an interoperability facade around
chat2pay capabilities, especially if external agents or multiple AI clients
need to discover and call the same banking capabilities. See the MCP
architecture and tools documentation:

- https://modelcontextprotocol.io/docs/concepts/architecture
- https://modelcontextprotocol.io/docs/concepts/tools

MCP should not be the core payment orchestration architecture for V2. Payment
journeys need strict ordering, policy gates, explicit confirmation,
idempotency, auditability, and downstream error handling. Those belong in the
backend journey state machine and policy guard, not in protocol-level tool
wrappers.

Recommended stance:

- do not add MCP to V2 core orchestration yet
- design capabilities so an MCP server facade can be added later without
  rewriting business logic
- expose read-only capabilities through MCP first if needed
- keep high-risk payment execution behind backend direct-confirm or
  proposal/confirmation gates, depending on the rail
- never expose raw mature REST APIs directly as model-controlled MCP tools

## 7. Conversation and Tool Orchestration

The backend should use a **tool-based orchestration loop**, not a frontend
wizard and not a large monolithic service.

### 7.1 Turn Lifecycle

For each user message or UI event:

1. load session history and the active draft from PostgreSQL
2. choose the current LLM provider through the provider router
3. ask the selected LLM provider to either answer within scope or call one of
   the backend-owned payment tools
4. execute the selected tool inside the backend and append the tool result to
   the provider-agnostic loop
5. continue until the provider returns final assistant text, a backend tool
   reaches a terminal UI state, or the hard iteration limit is reached
6. validate the selected backend action and required fields before any side
   effect
7. attach turn timing metadata to the final assistant message
   (`metadata.processingMs`)
8. persist the resulting assistant message and updated draft
9. stream or return structured blocks to the frontend

The current V1 Java implementation runs a real provider-agnostic LLM tool loop
for text turns when a provider is available. Personal-subscription Copilot and
the future OpenAI-compatible `REMOTE_API` provider share the same
`LlmCompletionRequest`/tool-call contract. Regex parsing remains as a fallback
and hint source only; it is not the sole intent mechanism.

### 7.2 Backend Guardrails

The backend, not the model, enforces these rules:

- `confirm_domestic_payment` is unavailable until required fields are complete
- explicit user confirmation is required before payment execution
- opaque fields such as `payeeIdIndex` are never shown to the user
- downstream response errors are normalized before returning to the frontend
- the loop has a hard iteration limit
- SSE turn errors are emitted as `turn-error` events and the stream is then
  completed normally, so global JSON exception handling does not try to write
  into an existing `text/event-stream` response

### 7.3 Current Interfaces

The provider boundary is `LlmProvider.complete(LlmCompletionRequest)`.
`LlmCompletionRequest` carries chat messages, tool definitions, tool choice,
assistant tool calls, and tool-result messages. Model-facing payment tool
definitions are generated from `CapabilityRegistry`; prompt text remains in
`PaymentToolDefinitions`. `PaymentToolRegistry` selects a `PaymentTool`
implementation for execution, and backend capability/journey services own
payment state transitions and side effects.

This keeps the controller stable while V2 adds more providers and tools.

### 7.4 How the LLM Tool Loop Really Works

For each text turn, the orchestrator first builds a provider-agnostic tool-loop
request. The model sees:

- the payment assistant system prompt from `PaymentToolDefinitions`
- the current date
- the current `ConversationState`
- a compact active-draft summary
- the last 12 persisted chat messages, mapped back to user/assistant roles

The request includes all V1 capability-derived tool definitions and sets
`tool_choice` to `auto`.
The provider can return plain assistant content, one or more tool calls, or no
usable decision. If more than one tool call is returned, the backend logs the
dropped tool names and executes only the first call for this V1 loop.

The loop stops when one of these conditions is met:

- the provider returns final assistant content and no tool calls
- a backend tool handler returns a terminal assistant message
- payment execution reaches `COMPLETED`, `FAILED`, or `CANCELLED`
- the hard `MAX_TOOL_LOOP_ITERATIONS` cap is hit
- the provider path fails and the deterministic fallback takes over

The current cap is 4 iterations. Tool results are appended as tool-result
messages and sent back to the same provider until one of the terminal
conditions above occurs.

SSE streaming is synthesized after the synchronous backend turn completes. The
backend emits `user-message`, `assistant-message-start`, one or more
`assistant-message-delta` events, then `assistant-message-complete`. Leading
assistant text is chunked into small deltas; structured blocks are emitted as
block deltas. This is not provider token-level streaming in V1.

Backend guardrails fire outside the model decision:

- confirmation is gated by explicit user confirmation text or UI action
- domestic payment execution validates payee, amount, and payment date
- payee identifiers are resolved from the latest downstream payee lookup, or
  from mock fixtures only when mock mode is enabled
- unsupported cross-border payment and GD-rail requests are stopped before
  downstream work
- downstream failures are normalized into error blocks and failed draft state

## 8. LLM Provider Strategy

### 8.1 V1 Primary Provider

V1 uses a backend adapter around personal-subscription GitHub Copilot access.

Responsibilities:

- read personal credentials and cached session token from `ctp_llm_credential`
- refresh or reacquire short-lived Copilot session tokens inside the backend
- hide provider-specific request details from the rest of the application
- support chat-completions tool calls and tool-result messages for the unified
  conversation loop
- read provider endpoint URLs from yaml/environment configuration and fail fast
  when required URLs are missing
- honor the configured corporate proxy for `https://api.github.com` token
  exchange

Suggested implementation name:

- `CopilotPersonalLlmProvider`

### 8.2 V2 Primary Provider

V2 adds or promotes:

- `RemoteApiLlmProvider`

That provider can become the primary path for production-like usage, while
`CopilotPersonalLlmProvider` remains available as a fallback. It uses the same
tool loop and backend guardrails as Copilot.

### 8.3 Provider Routing

The provider router should support:

- configured primary provider
- configured fallback provider
- explicit session-level provider logging for troubleshooting

Example:

- V1: `COPILOT_PERSONAL`
- V2: `REMOTE_API`, fallback `COPILOT_PERSONAL`

## 9. Downstream Integration Strategy

### 9.1 Shared Authentication Pattern

Every real downstream business call uses the same sequence:

1. load the active profile runtime config from `ctp_profile`
2. use the profile `username` and plaintext downstream `password` to call the configured `PAYMENT_LOGIN_URL`
3. get the SAML3 token
4. call the target business API with the required header

The login URL and stable downstream endpoints stay in yaml. Profile-specific
values such as login username/password, debit account number, debit product
category, and payment currency live on `ctp_profile` and are cached by the
backend for one day. This auth sequence belongs in a shared backend service,
not inside each tool.

Real payee data is always owned by downstream payee APIs. Local POC runs may
leave `PAYMENT_MOCK_ENABLED=true`; in that mode payee lookup can use seeded
`ctp_registered_payee` / `ctp_payee_alias` rows as mock fixtures and domestic
confirmation returns a mock reference. Those tables are not the target
production payee store and should not be used for real payee create/update
flows.

### 9.2 V1 Tool Set

V1 tools:

| Tool | Purpose | Downstream dependency |
|---|---|---|
| `get_registered_payees` | Retrieve and optionally filter registered payees | `PAYEE_URL` |
| `confirm_domestic_payment` | Directly execute confirmed domestic payment | `CONFIRM_DOMESTIC_PAYMENT_URL` |

Recommended internal split:

- `HttpRegisteredPayeeClient`
- `HttpDomesticPaymentClient`
- `PaymentDownstreamAuthService`

### 9.3 Future Tool Growth

When V2 adds cross-border payment, the ORTT propose and confirm APIs should
arrive as new capability/tool classes, not as conditionals inside
`DomesticPaymentTool`. GD and other non-ORTT rails stay outside the POC.

## 10. FE/BE Streaming Choice

### 10.1 Decision

Use **SSE-style event streaming over HTTP** between frontend and backend.

Do **not** use WebSocket in V1.

### 10.2 Why SSE Instead of WebSocket

- the traffic is request-driven and mostly server-to-client during a turn
- we need progressive assistant text, not a long-lived bidirectional channel
- HTTP infrastructure, auth, logging, and failure handling stay simpler
- Spring Boot and browser `fetch` both handle streaming well enough for this
  use case
- the frontend still sends user input through normal HTTP requests

### 10.3 Usage Pattern

- profile, auth, session list, and history APIs stay normal JSON REST endpoints
- message submission and structured UI events support:
  - normal JSON response
  - streamed `text/event-stream` response

Expected stream events:

- `user-message`
- `assistant-message-start`
- `assistant-message-delta`
- `assistant-message-complete`
- `turn-error`

The frontend reads the response stream from the Java backend. It never connects
to the LLM directly. On `turn-error`, the frontend renders a temporary assistant
error card and includes the backend-provided processing time when available.

## 11. Session and Draft State

### 11.1 Session State

Persisted session state should stay simple:

| State | Meaning |
|---|---|
| `IDLE` | No active payment draft |
| `COLLECTING_DETAILS` | Need payee, amount, or payment date |
| `AWAITING_PAYEE_SELECTION` | Multiple payees matched |
| `AWAITING_CONFIRMATION` | Draft is ready and waiting for explicit confirmation |
| `EXECUTING` | Backend is calling payment confirmation |
| `COMPLETED` | Payment succeeded |
| `FAILED` | Payment failed |
| `CANCELLED` | User cancelled the active draft |

Payee lookup without a transfer can leave the session in `IDLE`.

### 11.2 Draft State

Persist a payment draft only when a payment is being prepared.

Minimum V1 draft fields:

- payee input text
- selected payee id
- selected payee name
- selected payee type
- selected bank details
- amount
- currency
- payment date
- confirmation status
- downstream result summary

V2 cross-border ORTT should add proposal-specific state, either as targeted
columns or a dedicated proposal table: selected source account, payment option,
proposal id or execution token, eligibility/limit/fraud results, downstream
correlation ids, and idempotency key. V1 domestic payment does not require a
proposal record.

## 12. Core Sequences

### 12.1 Profile Login

```mermaid
sequenceDiagram
    participant U as User
    participant FE as Frontend
    participant API as Spring Boot
    participant DB as PostgreSQL

    U->>FE: Open app
    FE->>API: GET /api/profiles
    API->>DB: Load profiles
    DB-->>API: Profiles
    API-->>FE: Profile list

    U->>FE: Select profile
    FE->>API: POST /api/auth/profile-login (profileId + tb123)
    API->>DB: Validate profile
    API->>API: Validate POC access password
    API-->>FE: Current user context
```

### 12.2 Registered Payee Lookup

```mermaid
sequenceDiagram
    participant U as User
    participant FE as Frontend
    participant API as Chat API
    participant ORC as Conversation Orchestrator
    participant LLM as LLM Provider
    participant TOOL as Registered Payee Client
    participant AUTH as Downstream Auth Service
    participant PAYEE as PAYEE_URL
    participant DB as PostgreSQL

    U->>FE: "Do I have Bob registered?"
    FE->>API: POST /api/chat/sessions/{id}/messages
    API->>ORC: handle turn
    ORC->>LLM: session context + intent/tool-decision prompt
    LLM-->>ORC: PAYEE_LOOKUP + get_registered_payees(nameQuery=Bob)
    ORC->>TOOL: execute
    TOOL->>AUTH: acquire SAML token
    AUTH-->>TOOL: token
    TOOL->>PAYEE: fetch payees
    PAYEE-->>TOOL: payee data
    TOOL-->>ORC: normalized payee result
    ORC->>DB: persist messages
    ORC-->>FE: stream or return assistant blocks
```

### 12.3 Domestic Payment (Direct Confirm)

Domestic payment in V1 does not call a propose API. Once the payee, amount,
currency, payment date, and explicit user confirmation are present, the backend
calls the domestic confirmation API directly.

```mermaid
sequenceDiagram
    participant U as User
    participant FE as Frontend
    participant API as Chat API
    participant ORC as Conversation Orchestrator
    participant LLM as LLM Provider
    participant GUARD as Policy Guard
    participant PAYEE_TOOL as Registered Payee Client
    participant PAY_TOOL as Domestic Payment Client
    participant AUTH as Downstream Auth Service
    participant PAYEE as PAYEE_URL
    participant CONFIRM as CONFIRM_DOMESTIC_PAYMENT_URL
    participant DB as PostgreSQL

    U->>FE: "Pay Bob 500 HKD today"
    FE->>API: POST /api/chat/sessions/{id}/messages
    API->>ORC: handle turn
    ORC->>LLM: session context + intent/tool-decision prompt
    LLM-->>ORC: DOMESTIC_PAYMENT + extracted slots
    ORC->>PAYEE_TOOL: execute
    PAYEE_TOOL->>AUTH: acquire SAML token
    AUTH-->>PAYEE_TOOL: token
    PAYEE_TOOL->>PAYEE: list payees
    PAYEE-->>PAYEE_TOOL: payees
    PAYEE_TOOL-->>ORC: normalized payee result
    ORC->>DB: persist draft and assistant message
    ORC-->>FE: confirmation summary

    U->>FE: Confirm
    FE->>API: POST /api/chat/sessions/{id}/events
    API->>ORC: handle turn
    ORC->>GUARD: validate explicit confirmation + draft completeness
    GUARD-->>ORC: allowed
    ORC->>PAY_TOOL: execute
    PAY_TOOL->>AUTH: acquire SAML token
    AUTH-->>PAY_TOOL: token
    PAY_TOOL->>CONFIRM: confirm payment
    CONFIRM-->>PAY_TOOL: success or failure
    PAY_TOOL-->>ORC: normalized result
    ORC->>DB: persist terminal state
    ORC-->>FE: success or failure blocks
```

### 12.4 Cross-Border ORTT Payment (V2)

Cross-border payment is a V2 journey and should use the professional
cross-border terminology in product, code, and docs. The POC only targets ORTT;
GD and other rails are out of scope.

```mermaid
sequenceDiagram
    participant U as User
    participant FE as Frontend
    participant API as Chat API
    participant ORC as Conversation Orchestrator
    participant CAP as Capability Service
    participant GUARD as Policy Guard
    participant PAYEE as Payee API
    participant PROPOSE as PROPOSE_CROSS_BORDER_PAYMENT_URL
    participant CONFIRM as CONFIRM_CROSS_BORDER_PAYMENT_URL
    participant DB as PostgreSQL

    U->>FE: "Send EUR to Alice overseas"
    FE->>API: POST /api/chat/sessions/{id}/messages
    API->>ORC: handle turn
    ORC->>CAP: collect account, payee, amount, option, and checks
    CAP->>PAYEE: fetch live payee details
    PAYEE-->>CAP: payee details
    CAP->>PROPOSE: propose ORTT cross-border payment
    PROPOSE-->>CAP: proposal id, fees, rates, warnings
    CAP->>DB: persist proposal state and idempotency context
    ORC-->>FE: proposal review summary

    U->>FE: Confirm
    FE->>API: POST /api/chat/sessions/{id}/events
    API->>ORC: handle turn
    ORC->>GUARD: validate explicit confirmation + proposal state
    GUARD-->>ORC: allowed
    ORC->>CAP: confirmCrossBorderPayment(proposalId)
    CAP->>CONFIRM: confirm ORTT cross-border payment
    CONFIRM-->>CAP: success, held, rejected, or failure
    CAP->>DB: persist terminal state
    ORC-->>FE: terminal result blocks
```

## 13. Frontend Implications

The frontend keeps the current shell, but should simplify behavior:

- keep profile selector, sidebar, and chat workspace
- remove the heavy "Transfer Flow" treatment
- render only backend-provided blocks and status
- prefer conversational text plus light cards and lists
- avoid frontend-owned branching logic
- while a backend-provided selectable list or form is awaiting action, lock the
  free-text composer and route the user through the control; provide an explicit
  "none of these" style path when the offered options do not fit

## 14. V2 Compatibility Notes

This design intentionally leaves room for V2:

- more tools can be added without changing the controller contract
- the provider router can switch from Copilot to a real API
- cross-border ORTT payment can be added as new capabilities plus proposal
  persistence
- PostgreSQL remains sufficient for the current scale; Redis is not required
  for V1

## 15. Design Summary

The updated design is:

- backend-orchestrated
- tool-based
- PostgreSQL-backed
- SSE-streamed
- GitHub Copilot personal-subscription compatible in V1
- ready to grow into real API LLM + cross-border ORTT payment in V2
