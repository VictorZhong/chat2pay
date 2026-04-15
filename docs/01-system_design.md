# chat2pay - System Design

## 1. Overview

**chat2pay** is an internal web-based POC that allows a user to complete a money transfer through a conversational interface.  
The user first selects a predefined **profile** on the landing page. For the POC, profile selection acts as a lightweight login.  
After login, the user enters a ChatGPT-style workspace with:

- a collapsible left sidebar
- a right-side chat workspace
- chat history
- a fixed profile section at the bottom of the expanded sidebar
- assistant responses rendered as text, structured cards, selectable lists, and simple forms

The system is intentionally designed as a **deterministic transfer orchestration backend with LLM assistance**, not as a fully autonomous agent.

## 2. Goals

### In Scope

- Web-based internal POC
- Profile-based pseudo-login
- Conversational transfer journey
- Chat history persistence in PostgreSQL
- Structured assistant responses:
  - text
  - summary cards
  - selectable lists
  - simple forms
- Integration with existing downstream APIs over HTTP + JSON:
  - account list / details
  - payee list / details
  - transaction history
  - payment options
  - limit check / update
  - propose transaction
  - confirm transaction
- Lightweight workflow state machine
- Internal LLM integration (for example Copilot 5.4-compatible internal model endpoints)

### Out of Scope

- Real authentication / authorization
- MFA / step-up verification
- Production-grade fraud controls
- Voice input
- File upload
- Full admin console
- Full implementation of left-menu utility features such as **My Account**, **My Payee**, and **Transaction History**

## 3. Design Principles

1. **LLM assists; backend controls.**  
   The LLM is used for intent parsing, entity extraction, clarification phrasing, and response generation.  
   The backend remains responsible for workflow progression and all downstream transaction actions.

2. **One session, one active transfer draft at a time.**  
   Each chat session can carry one active `TransactionDraft` that is gradually enriched through the conversation.

3. **Deterministic transfer execution.**  
   Downstream APIs such as propose / confirm are never called directly by the model.

4. **UI is conversational but structured.**  
   Free-text remains the primary input, but the assistant can return structured UI blocks to reduce ambiguity and speed up completion.

5. **Persistence focuses on user value and recoverability.**  
   Chat history, workflow state, and transfer draft must survive page refresh and history reopening.

## 4. High-Level Architecture

```mermaid
flowchart LR
    subgraph FE[React Web Frontend]
        A[Profile Selector]
        B[App Shell]
        C[Sidebar]
        D[Chat Workspace]
        E[Message Renderer]
        F[Text Input]
        G[Settings Popover<br/>Logout only]
    end

    subgraph BE[Spring Boot Backend]
        H[Chat API]
        I[Profile API]
        J[Chat Orchestrator]
        K[Conversation Manager]
        L[Intent and Slot Service]
        M[LLM Gateway]
        N[Transfer Workflow Engine]
        O[Transfer Domain Service]
        P[Response Renderer]
        Q[External API Clients]
    end

    subgraph DB[PostgreSQL]
        R[(poc_profile)]
        S[(chat_session)]
        T[(chat_message)]
        U[(transaction_draft)]
        V[(workflow_transition_log)]
    end

    subgraph LLM[Internal LLM]
        W[Copilot 5.4 / compatible models]
    end

    subgraph DS[Existing Transfer APIs]
        X[Account APIs]
        Y[Payee APIs]
        Z[Txn History APIs]
        AA[Payment Option APIs]
        AB[Limit APIs]
        AC[Propose APIs]
        AD[Confirm APIs]
    end

    A --> I
    B --> H
    C --> H
    D --> H
    F --> H
    G --> I

    H --> J
    I --> K
    J --> K
    J --> L
    L --> M
    M --> W

    J --> N
    N --> O
    O --> Q
    J --> P

    K --> S
    K --> T
    K --> U
    N --> V
    I --> R

    Q --> X
    Q --> Y
    Q --> Z
    Q --> AA
    Q --> AB
    Q --> AC
    Q --> AD
```

## 5. Frontend Architecture

### 5.1 Pages and Major Areas

#### A. Profile Selection Page
- Landing page for the POC
- Shows 4–5 mock profiles
- Selecting a profile acts as login
- On success, routes to the main chat page

#### B. Main Workspace
Layout:
- **Left sidebar**
  - New Chat
  - Placeholder utility items:
    - My Account
    - My Payee
    - Transaction History
  - Chat History list
  - Fixed bottom user identity area with avatar + username
- **Right workspace**
  - chat title / session header
  - assistant and user messages
  - structured cards / lists / forms
  - primary text input box

#### C. User Settings Popover
- Triggered from the bottom-left avatar/username section
- POC options:
  - Logout

### 5.2 Frontend Modules

| Module | Responsibility |
|---|---|
| `ProfileSelectorPage` | Fetch profiles, display cards, perform pseudo-login |
| `AppLayout` | Overall layout shell and routing |
| `Sidebar` | New chat, placeholders, chat history list |
| `UserMenu` | Bottom fixed avatar + username + logout popover |
| `ChatPage` | Main conversation workspace |
| `MessageList` | Render ordered message stream |
| `MessageRenderer` | Render text, cards, lists, forms, error blocks |
| `ChatInputBar` | Free-text input, send action, busy state |
| `StructuredActionPanel` | Handle selectable list and form events |
| `HistoryLoader` | Load and display an existing session |
| `ProfileStore` | Keep selected profile in client state |
| `ChatStore` | Current session, message stream, pagination, draft snapshot |

## 6. Backend Architecture

### 6.1 Core Modules

| Module | Responsibility |
|---|---|
| `ProfileApiController` | Public endpoints for profile list, profile login, logout, current user context |
| `ChatApiController` | Session and message endpoints consumed by the frontend |
| `ChatOrchestrator` | Central application coordinator for every user turn |
| `ConversationManager` | Load / save session, messages, active draft, workflow snapshots |
| `IntentAndSlotService` | Convert user input into structured intent + entities |
| `LlmGateway` | Prompt management, model calls, schema-constrained parsing |
| `TransferWorkflowEngine` | Lightweight state machine for transfer progression |
| `TransferDomainService` | Business-oriented orchestration and downstream decisioning |
| `ResponseRenderer` | Convert domain outcomes into frontend-ready content blocks |
| `ExternalApiClients` | Typed HTTP clients for account, payee, payment, limit, propose, confirm, history APIs |
| `WorkflowTransitionLogger` | Persist state transitions and action results for diagnostics |

### 6.2 Why the Orchestrator-Centric Design

The backend is deliberately centered around a **Chat Orchestrator** because the conversation may require:

- loading prior state
- extracting new slots from the latest user message
- resolving ambiguity
- calling downstream APIs
- deciding whether to ask for more input or proceed
- rendering a structured response

This reduces controller complexity and prevents workflow logic from leaking into transport or client code.

## 7. Message Handling Flow

```mermaid
flowchart TD
    A[User submits text or structured UI event] --> B[Chat API]
    B --> C[Chat Orchestrator]
    C --> D[Load session, messages, draft]
    D --> E[Intent and slot parsing]
    E --> F[Merge extracted data into draft]
    F --> G[Workflow engine evaluates current state]

    G --> H{Need more user input?}
    H -- Yes --> I[Build clarification or selection response]
    I --> J[Persist assistant message blocks]
    J --> K[Return response to frontend]

    H -- No --> L{Need downstream calls?}
    L -- Yes --> M[Call domain service and external APIs]
    M --> N[Update draft and state]
    N --> O{Ready for propose or confirm?}
    O -- No --> I
    O -- Yes --> P[Run controlled transaction step]
    P --> Q[Persist outcome]
    Q --> R[Render summary / success / error response]
    R --> K
```

## 8. Transfer Workflow

### 8.1 Workflow State Machine

```mermaid
stateDiagram-v2
    [*] --> IDLE
    IDLE --> COLLECTING_TRANSFER_INFO: transfer intent detected

    COLLECTING_TRANSFER_INFO --> RESOLVING_AMBIGUITY: multiple accounts / payees / rails
    COLLECTING_TRANSFER_INFO --> READY_FOR_PAYMENT_OPTIONS: mandatory slots filled
    COLLECTING_TRANSFER_INFO --> CANCELLED: cancel requested

    RESOLVING_AMBIGUITY --> COLLECTING_TRANSFER_INFO: ambiguity resolved
    RESOLVING_AMBIGUITY --> READY_FOR_PAYMENT_OPTIONS: resolution completed
    RESOLVING_AMBIGUITY --> CANCELLED: cancel requested

    READY_FOR_PAYMENT_OPTIONS --> READY_FOR_LIMIT_CHECK: payment option selected
    READY_FOR_PAYMENT_OPTIONS --> FAILED: option retrieval failure

    READY_FOR_LIMIT_CHECK --> READY_FOR_PROPOSE: limit passed
    READY_FOR_LIMIT_CHECK --> FAILED: limit failed

    READY_FOR_PROPOSE --> AWAITING_USER_CONFIRMATION: propose succeeded
    READY_FOR_PROPOSE --> FAILED: propose failed

    AWAITING_USER_CONFIRMATION --> READY_FOR_CONFIRM: user confirmed
    AWAITING_USER_CONFIRMATION --> CANCELLED: user cancelled

    READY_FOR_CONFIRM --> COMPLETED: confirm succeeded
    READY_FOR_CONFIRM --> FAILED: confirm failed

    FAILED --> [*]
    COMPLETED --> [*]
    CANCELLED --> [*]
```

### 8.2 Workflow State Enumeration

| State | Meaning |
|---|---|
| `IDLE` | Session created, no active transfer flow yet |
| `COLLECTING_TRANSFER_INFO` | User is providing source account, payee, amount, currency, or note |
| `RESOLVING_AMBIGUITY` | Backend needs the user to resolve multiple candidates |
| `READY_FOR_PAYMENT_OPTIONS` | Basic draft is complete; payment method options must be loaded or selected |
| `READY_FOR_LIMIT_CHECK` | Draft is sufficient for a limit check |
| `READY_FOR_PROPOSE` | Draft is sufficient for propose |
| `AWAITING_USER_CONFIRMATION` | Propose completed; assistant is waiting for explicit user confirmation |
| `READY_FOR_CONFIRM` | User confirmed and backend may call confirm |
| `COMPLETED` | Transfer successfully completed |
| `FAILED` | Flow failed due to business or technical error |
| `CANCELLED` | User explicitly cancelled the in-progress transfer |

### 8.3 Workflow Event Enumeration

| Event | Meaning |
|---|---|
| `SESSION_CREATED` | New chat session created |
| `USER_TEXT_RECEIVED` | User submitted free-text input |
| `USER_UI_EVENT_RECEIVED` | User interacted with a list or form |
| `TRANSFER_INTENT_DETECTED` | Intent parser recognized transfer initiation |
| `SLOTS_UPDATED` | One or more draft fields were merged |
| `AMBIGUITY_DETECTED` | Multiple candidate accounts / payees / rails found |
| `AMBIGUITY_RESOLVED` | User selected a concrete candidate |
| `PAYMENT_OPTIONS_LOADED` | Payment options returned successfully |
| `PAYMENT_OPTION_SELECTED` | User or system selected the final transfer rail |
| `LIMIT_CHECK_PASSED` | Limit API returned success |
| `LIMIT_CHECK_FAILED` | Limit API returned failure |
| `PROPOSE_SUCCEEDED` | Propose API succeeded |
| `PROPOSE_FAILED` | Propose API failed |
| `USER_CONFIRMED` | User explicitly confirmed the transfer |
| `USER_CANCELLED` | User explicitly cancelled the transfer |
| `CONFIRM_SUCCEEDED` | Confirm API succeeded |
| `CONFIRM_FAILED` | Confirm API failed |
| `SYSTEM_ERROR` | Unexpected internal error |

## 9. Core End-to-End Sequences

### 9.1 Profile Login and Workspace Entry

```mermaid
sequenceDiagram
    participant U as User
    participant FE as Frontend
    participant API as Profile API
    participant DB as PostgreSQL

    U->>FE: Open chat2pay landing page
    FE->>API: GET /api/profiles
    API->>DB: Load predefined profiles
    DB-->>API: Profiles
    API-->>FE: Profile list

    U->>FE: Select profile
    FE->>API: POST /api/auth/profile-login
    API->>DB: Validate profile exists
    DB-->>API: Profile details
    API-->>FE: Current user context

    FE-->>U: Route to main workspace
```

### 9.2 New Chat and Transfer Journey

```mermaid
sequenceDiagram
    participant U as User
    participant FE as Frontend
    participant API as Chat API
    participant ORC as Chat Orchestrator
    participant CM as Conversation Manager
    participant IS as Intent and Slot Service
    participant LLM as LLM Gateway
    participant WF as Workflow Engine
    participant DS as Transfer Domain Service
    participant EXT as External APIs
    participant DB as PostgreSQL

    U->>FE: Click New Chat
    FE->>API: POST /api/chat/sessions
    API->>CM: Create session
    CM->>DB: Insert chat_session and welcome message
    DB-->>CM: Session created
    CM-->>API: Session + welcome block
    API-->>FE: Display new session

    U->>FE: "Pay Tom 5000 HKD"
    FE->>API: POST /api/chat/sessions/{id}/messages
    API->>ORC: handleMessage()

    ORC->>CM: Load session + active draft
    CM->>DB: Query session, messages, draft
    DB-->>CM: Context
    CM-->>ORC: Context

    ORC->>IS: Parse intent and slots
    IS->>LLM: Structured parsing request
    LLM-->>IS: intent + entities
    IS-->>ORC: Parsed result

    ORC->>WF: Evaluate state transition
    WF-->>ORC: Need account/payee resolution

    ORC->>DS: Resolve candidates
    DS->>EXT: Account / Payee APIs
    EXT-->>DS: Candidate data
    DS-->>ORC: Resolved or ambiguous candidates

    ORC->>CM: Persist updated draft + assistant blocks
    CM->>DB: Save draft and messages
    DB-->>CM: Saved
    CM-->>ORC: Done
    ORC-->>API: Response blocks
    API-->>FE: Render text + selectable lists

    U->>FE: Select payee/account or send more details
    FE->>API: POST /api/chat/sessions/{id}/events or /messages
    API->>ORC: Continue orchestration

    ORC->>WF: Re-evaluate
    WF-->>ORC: READY_FOR_LIMIT_CHECK

    ORC->>DS: Run limit check
    DS->>EXT: Limit API
    EXT-->>DS: Pass
    DS-->>ORC: Limit ok

    ORC->>DS: Run propose
    DS->>EXT: Propose API
    EXT-->>DS: Summary + proposalId
    DS-->>ORC: Proposal result

    ORC->>CM: Persist proposal summary
    CM->>DB: Save draft
    DB-->>CM: Saved

    ORC-->>API: Summary card + ask for confirmation
    API-->>FE: Render summary card

    U->>FE: "Confirm"
    FE->>API: POST /api/chat/sessions/{id}/messages
    API->>ORC: handleMessage()

    ORC->>DS: Confirm transfer
    DS->>EXT: Confirm API
    EXT-->>DS: Success + reference
    DS-->>ORC: Completion result

    ORC->>CM: Persist success state
    CM->>DB: Save messages, draft, workflow log
    DB-->>CM: Saved

    ORC-->>API: Success card
    API-->>FE: Render completed state
```

### 9.3 Open Existing Chat History

```mermaid
sequenceDiagram
    participant U as User
    participant FE as Frontend
    participant API as Chat API
    participant CM as Conversation Manager
    participant DB as PostgreSQL

    U->>FE: Click a chat session in sidebar history
    FE->>API: GET /api/chat/sessions/{sessionId}
    API->>CM: Load session details
    CM->>DB: Query session, draft, latest state
    DB-->>CM: Session details
    CM-->>API: Session detail payload
    API-->>FE: Session metadata + state snapshot

    FE->>API: GET /api/chat/sessions/{sessionId}/messages
    API->>CM: Load messages
    CM->>DB: Query ordered messages
    DB-->>CM: Message list
    CM-->>API: Message list
    API-->>FE: Render history

    alt session status is ACTIVE
        FE-->>U: Input remains enabled
    else session status is COMPLETED / CANCELLED
        FE-->>U: Read-only history view
    end
```

## 10. Transfer Flowchart

```mermaid
flowchart TD
    A[User starts or continues a chat] --> B{Transfer intent?}

    B -- No --> C[Handle as general chat or future utility query]
    B -- Yes --> D[Create or load active TransactionDraft]

    D --> E[Extract slots from user input]
    E --> F{Mandatory fields complete?}

    F -- No --> G[Ask for missing fields]
    G --> Z[Return assistant response]

    F -- Yes --> H{Ambiguity exists?}
    H -- Yes --> I[Render selectable list or form]
    I --> Z

    H -- No --> J[Load payment options]
    J --> K{Payment rail selected?}
    K -- No --> L[Ask user to choose a payment option]
    L --> Z

    K -- Yes --> M[Run limit check]
    M --> N{Limit passed?}
    N -- No --> O[Render business failure message]
    O --> Z

    N -- Yes --> P[Run propose]
    P --> Q{Propose success?}
    Q -- No --> R[Render propose failure]
    R --> Z

    Q -- Yes --> S[Render transfer summary]
    S --> T[Wait for explicit confirmation]

    T --> U{User confirms?}
    U -- No, cancel --> V[Mark cancelled]
    V --> Z
    U -- Yes --> W[Run confirm]
    W --> X{Confirm success?}
    X -- No --> Y[Render confirm failure]
    X -- Yes --> AA[Render success reference]

    Y --> Z
    AA --> Z
```

## 11. Key DTOs

### 11.1 Current User and Profile

#### `ProfileSummary`
- `id`
- `code`
- `displayName`
- `avatarUrl`
- `mockCustomerId`
- `locale`
- `status`

#### `CurrentUserContext`
- `profileId`
- `username`
- `displayName`
- `avatarUrl`
- `locale`
- `loginMode` = `PROFILE_SELECTION`

### 11.2 Chat Session

#### `ChatSessionSummary`
- `sessionId`
- `title`
- `status`
- `createdAt`
- `updatedAt`
- `lastAssistantText`
- `workflowState`

#### `ChatSessionDetail`
- `sessionId`
- `title`
- `status`
- `createdAt`
- `updatedAt`
- `activeDraft`
- `workflowSnapshot`

### 11.3 Messages

#### `ChatMessage`
- `messageId`
- `sessionId`
- `role` (`USER`, `ASSISTANT`, `SYSTEM`)
- `messageType` (`TEXT`, `CARD`, `LIST`, `FORM`, `UI_EVENT`)
- `text`
- `contentBlocks`
- `createdAt`

#### `ContentBlock`
Shared fields:
- `blockId`
- `type`
- `title`
- `metadata`

Block variants:
- `TextBlock`
- `SummaryCardBlock`
- `SelectableListBlock`
- `SimpleFormBlock`
- `ErrorCardBlock`
- `InfoCardBlock`

### 11.4 Requests

#### `CreateChatSessionRequest`
- `title` (optional)

#### `SendMessageRequest`
- `messageText`
- `clientMessageId` (optional)

#### `UiEventRequest`
- `eventType`
- `sourceMessageId`
- `sourceBlockId`
- `selectedItemIds`
- `formValues`
- `clientEventId` (optional)

### 11.5 Chat Turn Response

#### `ChatTurnResponse`
- `session`
- `userEchoMessage` (optional)
- `assistantMessages`
- `draftSummary`
- `workflowState`
- `suggestedActions`
- `serverTimestamp`

### 11.6 Transaction Draft

#### `TransactionDraft`
- `draftId`
- `sessionId`
- `status`
- `workflowState`
- `sourceAccountId`
- `sourceAccountDisplay`
- `payeeId`
- `payeeDisplay`
- `amount`
- `currency`
- `paymentRail`
- `note`
- `limitCheckStatus`
- `proposalId`
- `proposalSummary`
- `transferReference`
- `lastUpdatedAt`

## 12. Technology Choices

### Backend
- **Java 21**
- **Spring Boot 3**
- Spring Web
- Spring Validation
- Spring Data JPA
- PostgreSQL
- OpenAPI-generated DTOs / interfaces
- Feign or Spring HTTP Interface / RestClient for downstream API integration
- Jackson for JSON and JSONB payload mapping

### Frontend
- **React**
- **Ant Design 6**
- **Tailwind CSS**
- React Router
- TanStack Query or equivalent for API fetching/caching
- Zustand / Redux Toolkit / Context for local session state

### Database
- **PostgreSQL**
- JSONB for structured message blocks and proposal summaries
- Application-generated ULIDs for ordered identifiers

## 13. Suggested Non-Functional Rules for the POC

- One active draft per session
- Explicit confirmation required before confirm API call
- Every state transition logged
- Every assistant structured block persisted
- Recoverable page refresh:
  - current session reloads from backend
  - history view can reconstruct the full conversation
- Protected APIs trust the selected profile in POC mode

## 14. Future Evolution

The current design leaves room for:

- real authentication
- MFA / transaction signing
- richer profile settings
- reusable utility flows for **My Account**, **My Payee**, and **Transaction History**
- recommendation cards
- multi-step draft editing
- observability dashboards
- policy-based transfer validation
- model routing across multiple internal LLMs
