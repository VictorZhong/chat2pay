# chat2pay - Project Structure

## 1. Purpose

This document defines the recommended repository and module structure for the **chat2pay** POC.
It is intended to support:

- fast internal delivery
- clean separation between frontend, backend, API contract, and database assets
- OpenAPI-driven model/interface generation
- future evolution from POC into a more formal internal product

The structure below is optimized for the agreed POC scope:

- **frontend:** React + Ant Design 6 + Tailwind CSS
- **backend:** Java 21 + Spring Boot 3 + PostgreSQL
- **API contract:** OpenAPI-based contract-first development
- **workflow:** lightweight state machine, not a heavyweight BPM/workflow platform
- **login model:** profile-based pseudo-login for internal demo usage

---

## 2. Recommended Repository Strategy

For the POC, the recommended approach is a **single monorepo**.

### Why monorepo is recommended for this POC

1. The frontend, backend, OpenAPI contract, and DB scripts are tightly coupled.
2. The team can version the API contract and implementation together.
3. It simplifies local development and internal demo packaging.
4. It makes contract changes easier to review because UI, API, DTO, and schema impact are visible in one place.
5. It reduces coordination overhead compared with multiple small repositories.

### Suggested repository name

`chat2pay`

---

## 3. Top-Level Repository Structure

```text
chat2pay/
├── README.md
├── .gitignore
├── docs/
│   ├── 01-system_design.md
|   |-- 02-api_contract.yaml
│   ├── 03-db_design.md
│   ├── 04-UI_spec.md
│   └── 05-project_structure.md
├── api-contract/
│   └── chat2pay-api.yaml
├── apps/
│   ├── chat2pay-backend/
│   └── chat2pay-web/
├── db/
│   ├── ddl/
│   ├── migration/
│   └── seed/
├── scripts/
│   ├── dev/
│   ├── codegen/
│   └── ci/
└── tools/
    └── local/
```

### Top-level responsibility summary

| Path | Responsibility |
|---|---|
| `docs/` | Architecture, design, UI, DB, and project documents |
| `openapi/` | Canonical API contract for frontend/backend generation |
| `apps/chat2pay-backend/` | Spring Boot backend application |
| `apps/chat2pay-web/` | React frontend application |
| `db/` | DDL, migrations, seed data |
| `scripts/` | Local dev, code generation, CI helper scripts |
| `tools/local/` | Optional local tooling, helper configs, dev bootstrap assets |

---

## 4. Development Model

The recommended development model is:

- **contract-first** for backend/frontend interaction
- **generated DTO/API bindings** where practical
- **handwritten business logic** for orchestration and workflow
- **clear separation** between generated code and manually maintained code

### What should be generated

- frontend API client
- frontend OpenAPI models
- backend request/response DTO base models if desired
- backend API interfaces/controllers stubs if desired

### What should remain handwritten

- orchestrator logic
- workflow state machine
- LLM integration logic
- downstream API adapters
- domain decision logic
- UI page components and layout composition
- persistence entities and repositories

---

## 5. Backend Project Structure

Suggested path:

```text
apps/chat2pay-backend/
├── build.gradle / pom.xml
├── README.md
├── src/
│   ├── main/
│   │   ├── java/com/company/chat2pay/
│   │   │   ├── Chat2PayApplication.java
│   │   │   ├── config/
│   │   │   ├── api/
│   │   │   ├── application/
│   │   │   ├── domain/
│   │   │   ├── integration/
│   │   │   ├── persistence/
│   │   │   ├── security/
│   │   │   └── common/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-local.yml
│   │       ├── db/
│   │       ├── prompts/
│   │       └── logback-spring.xml
│   └── test/
│       ├── java/com/company/chat2pay/
│       └── resources/
└── target/ or build/
```

### Package design

```text
com.company.chat2pay
├── config
├── api
│   ├── profile
│   ├── auth
│   └── chat
├── application
│   ├── orchestrator
│   ├── conversation
│   ├── workflow
│   ├── renderer
│   └── usecase
├── domain
│   ├── profile
│   ├── session
│   ├── message
│   ├── transfer
│   └── llm
├── integration
│   ├── llm
│   ├── account
│   ├── payee
│   ├── payment
│   ├── limit
│   ├── transaction
│   └── history
├── persistence
│   ├── entity
│   ├── repository
│   ├── mapper
│   └── converter
├── common
│   ├── dto
│   ├── enums
│   ├── exception
│   ├── util
│   └── validation
└── security
```

---

## 6. Backend Module Responsibilities

### 6.1 `api`
Owns REST controllers and transport-level request/response handling.

Recommended subpackages:

```text
api/
├── profile/
│   ├── ProfileApiController.java
│   └── ProfileApiMapper.java
├── auth/
│   └── AuthApiController.java
└── chat/
    ├── ChatSessionApiController.java
    ├── ChatMessageApiController.java
    └── UiActionApiController.java
```

Responsibilities:
- receive HTTP requests
- validate transport payloads
- delegate to application services
- map results to API contract response shape

Should not contain:
- workflow logic
- downstream orchestration logic
- database logic beyond delegation

### 6.2 `application`
Owns application flow coordination.

Recommended subpackages:

```text
application/
├── orchestrator/
│   ├── ChatOrchestrator.java
│   ├── ChatTurnContext.java
│   └── ChatTurnResult.java
├── conversation/
│   ├── ConversationManager.java
│   ├── SessionLoader.java
│   └── SessionWriter.java
├── workflow/
│   ├── TransferWorkflowEngine.java
│   ├── WorkflowDecision.java
│   ├── WorkflowActionPlan.java
│   ├── WorkflowEventResolver.java
│   └── WorkflowTransitionLogger.java
├── renderer/
│   ├── ResponseRenderer.java
│   ├── MessageBlockFactory.java
│   └── DraftSummaryBuilder.java
└── usecase/
    ├── CreateSessionUseCase.java
    ├── ListSessionsUseCase.java
    ├── SendMessageUseCase.java
    ├── SubmitUiActionUseCase.java
    └── LogoutUseCase.java
```

Responsibilities:
- coordinate the full chat turn lifecycle
- call LLM parsing and domain services
- drive the lightweight state machine
- persist state changes
- return UI-ready response blocks

### 6.3 `domain`
Owns business concepts and rules.

Recommended subpackages:

```text
domain/
├── profile/
│   ├── PocProfile.java
│   └── ProfileService.java
├── session/
│   ├── ChatSession.java
│   ├── ChatSessionStatus.java
│   └── ChatSessionService.java
├── message/
│   ├── ChatMessage.java
│   ├── MessageRole.java
│   └── MessageType.java
├── transfer/
│   ├── TransactionDraft.java
│   ├── WorkflowState.java
│   ├── WorkflowEvent.java
│   ├── TransferIntent.java
│   ├── TransferDomainService.java
│   ├── SlotFillResult.java
│   ├── PayeeResolutionResult.java
│   ├── AccountResolutionResult.java
│   └── TransferSummary.java
└── llm/
    ├── IntentParseResult.java
    ├── SlotExtractionResult.java
    └── AssistantReplyInstruction.java
```

Responsibilities:
- define core domain objects
- define enums used across layers
- define business rules independent of transport and persistence

### 6.4 `integration`
Owns all external interactions.

Recommended subpackages:

```text
integration/
├── llm/
│   ├── LlmGateway.java
│   ├── LlmPromptRepository.java
│   ├── LlmModelClient.java
│   └── LlmResponseParser.java
├── account/
│   ├── AccountClient.java
│   ├── AccountClientConfig.java
│   ├── AccountClientDto.java
│   └── AccountMapper.java
├── payee/
├── payment/
├── limit/
├── transaction/
└── history/
```

Responsibilities:
- call internal LLM endpoints
- call downstream transfer APIs over HTTP+JSON
- convert external DTOs to internal domain models
- centralize timeouts, retries, and error mapping

### 6.5 `persistence`
Owns database mapping and repositories.

Recommended subpackages:

```text
persistence/
├── entity/
│   ├── PocProfileEntity.java
│   ├── ChatSessionEntity.java
│   ├── ChatMessageEntity.java
│   ├── TransactionDraftEntity.java
│   └── WorkflowTransitionLogEntity.java
├── repository/
│   ├── PocProfileRepository.java
│   ├── ChatSessionRepository.java
│   ├── ChatMessageRepository.java
│   ├── TransactionDraftRepository.java
│   └── WorkflowTransitionLogRepository.java
├── mapper/
│   ├── ProfilePersistenceMapper.java
│   ├── SessionPersistenceMapper.java
│   ├── MessagePersistenceMapper.java
│   └── DraftPersistenceMapper.java
└── converter/
    └── JsonbConverter.java
```

Responsibilities:
- JPA entities
- Spring Data repositories
- persistence mapping
- JSONB conversion where appropriate

### 6.6 `config`
Recommended content:

```text
config/
├── JacksonConfig.java
├── OpenApiConfig.java
├── RestClientConfig.java
├── DatabaseConfig.java
├── WebMvcConfig.java
├── CorsConfig.java
├── LlmConfig.java
└── FeatureFlagConfig.java
```

### 6.7 `common`
Reusable technical utilities.

Recommended content:

```text
common/
├── dto/
├── enums/
├── exception/
├── util/
└── validation/
```

Use sparingly. Avoid dumping business logic here.

---

## 7. Backend Class Ownership Guidelines

### Good placement examples

| Class | Suggested package |
|---|---|
| `ChatOrchestrator` | `application.orchestrator` |
| `TransferWorkflowEngine` | `application.workflow` |
| `TransactionDraft` | `domain.transfer` |
| `WorkflowState` | `domain.transfer` |
| `AccountClient` | `integration.account` |
| `ChatSessionEntity` | `persistence.entity` |
| `ChatSessionRepository` | `persistence.repository` |
| `ResponseRenderer` | `application.renderer` |

### Avoid these anti-patterns

- controller calling downstream API clients directly
- repository returning external DTOs
- LLM prompt strings scattered through random services
- generated models being manually edited
- domain objects depending on JPA annotations when avoidable for shared logic

---

## 8. Frontend Project Structure

Suggested path:

```text
apps/chat2pay-web/
├── package.json
├── README.md
├── index.html
├── src/
│   ├── main.tsx
│   ├── App.tsx
│   ├── app/
│   ├── pages/
│   ├── layouts/
│   ├── components/
│   ├── features/
│   ├── services/
│   ├── store/
│   ├── hooks/
│   ├── types/
│   ├── utils/
│   ├── styles/
│   └── generated/
│       └── api/
├── public/
└── dist/
```

### Frontend folder layout

```text
src/
├── app/
│   ├── router/
│   ├── providers/
│   └── config/
├── pages/
│   ├── ProfileSelectionPage/
│   ├── ChatWorkspacePage/
│   └── SessionHistoryPage/
├── layouts/
│   ├── AppLayout/
│   └── ChatWorkspaceLayout/
├── components/
│   ├── common/
│   ├── sidebar/
│   ├── message/
│   ├── cards/
│   ├── forms/
│   └── feedback/
├── features/
│   ├── profile/
│   ├── chat-session/
│   ├── chat-message/
│   ├── transfer-draft/
│   └── ui-action/
├── services/
│   ├── api/
│   ├── mapper/
│   └── adapter/
├── store/
│   ├── profileStore.ts
│   ├── chatStore.ts
│   └── uiStore.ts
├── hooks/
├── types/
├── utils/
├── styles/
│   ├── index.css
│   └── tailwind.css
└── generated/api/
```

---

## 9. Frontend Module Responsibilities

### 9.1 `pages`
Route-level views.

Recommended pages:

```text
pages/
├── ProfileSelectionPage/
│   ├── index.tsx
│   └── ProfileSelectionPage.tsx
├── ChatWorkspacePage/
│   ├── index.tsx
│   └── ChatWorkspacePage.tsx
└── SessionHistoryPage/
```

### 9.2 `layouts`
Page shells and layout composition.

Recommended layouts:

```text
layouts/
├── AppLayout/
│   ├── AppLayout.tsx
│   └── index.ts
└── ChatWorkspaceLayout/
    ├── ChatWorkspaceLayout.tsx
    └── index.ts
```

### 9.3 `components`
Reusable UI pieces.

Recommended structure:

```text
components/
├── common/
│   ├── PageContainer.tsx
│   ├── EmptyState.tsx
│   ├── LoadingOverlay.tsx
│   └── ConfirmDialog.tsx
├── sidebar/
│   ├── Sidebar.tsx
│   ├── SidebarHeader.tsx
│   ├── SidebarMenu.tsx
│   ├── SessionHistoryList.tsx
│   ├── UserProfileFooter.tsx
│   └── UserSettingsPopover.tsx
├── message/
│   ├── MessageList.tsx
│   ├── MessageBubble.tsx
│   ├── MessageRenderer.tsx
│   └── MessageTimestamp.tsx
├── cards/
│   ├── SummaryCard.tsx
│   ├── InfoCard.tsx
│   ├── ErrorCard.tsx
│   └── SelectionCard.tsx
├── forms/
│   ├── InlineSimpleForm.tsx
│   ├── SelectionList.tsx
│   └── ChatInputBar.tsx
└── feedback/
    ├── ToastHost.tsx
    └── InlineErrorBanner.tsx
```

### 9.4 `features`
Feature-centric logic and containers.

Recommended structure:

```text
features/
├── profile/
│   ├── ProfileCard.tsx
│   ├── useProfiles.ts
│   └── profile.mapper.ts
├── chat-session/
│   ├── useChatSessions.ts
│   ├── session.mapper.ts
│   └── session.actions.ts
├── chat-message/
│   ├── useMessages.ts
│   ├── message.mapper.ts
│   └── sendMessage.ts
├── transfer-draft/
│   ├── draft.mapper.ts
│   └── draftSummary.ts
└── ui-action/
    ├── submitSelection.ts
    ├── submitForm.ts
    └── uiAction.mapper.ts
```

### 9.5 `services`
External communication and adapters.

Recommended structure:

```text
services/
├── api/
│   ├── client.ts
│   ├── authHeaders.ts
│   └── interceptors.ts
├── mapper/
└── adapter/
```

### 9.6 `generated/api`
Generated OpenAPI assets.

Rules:
- generated only
- do not hand edit
- regenerate via script when the contract changes

---

## 10. OpenAPI and Code Generation Structure

Recommended structure:

```text
openapi/
└── chat2pay-api.yaml

scripts/
└── codegen/
    ├── generate-backend.sh
    ├── generate-frontend.sh
    └── verify-openapi.sh
```

### Frontend generation target

```text
apps/chat2pay-web/src/generated/api/
```

Suggested generated content:
- TypeScript models
- API request/response types
- API client layer

### Backend generation target

Two valid options are acceptable.

#### Option A - generate interfaces only
Preferred for maintainability.

```text
apps/chat2pay-backend/src/main/java/com/company/chat2pay/api/generated/
```

Generate:
- request/response DTOs
- API interfaces

Then implement the interfaces in handwritten controllers.

#### Option B - generate controllers and models
Faster initially, but easier to make messy later.

For this POC, **Option A is recommended**.

---

## 11. Database and Migration Structure

Suggested structure:

```text
db/
├── ddl/
│   ├── V001__create_poc_profile.sql
│   ├── V002__create_chat_session.sql
│   ├── V003__create_chat_message.sql
│   ├── V004__create_transaction_draft.sql
│   └── V005__create_workflow_transition_log.sql
├── migration/
│   └── README.md
└── seed/
    ├── R__seed_profiles.sql
    └── R__seed_demo_sessions.sql
```

### Migration strategy

Recommended:
- use Flyway
- versioned migrations for schema changes
- repeatable scripts for seed/reference data if needed

### Seed data responsibility

Seed files should include:
- 4–5 POC profiles
- optional sample sessions for demo environments

---

## 12. Resource and Config Structure

Suggested backend resources layout:

```text
src/main/resources/
├── application.yml
├── application-local.yml
├── application-dev.yml
├── db/
│   └── migration/
├── prompts/
│   ├── intent-parser.txt
│   ├── clarification-generator.txt
│   └── assistant-reply.txt
└── logback-spring.xml
```

### Why `prompts/` should exist

Because prompt text will change frequently during the POC.
Storing prompts as external resource files is cleaner than embedding large prompt strings into Java classes.

---

## 13. Testing Structure

### Backend tests

```text
src/test/java/com/company/chat2pay/
├── api/
├── application/
├── domain/
├── integration/
└── persistence/
```

Recommended test types:
- controller tests
- application/orchestrator tests
- workflow transition tests
- repository tests
- downstream client tests with stubs/mocks
- OpenAPI contract compatibility tests

### Highest-value backend tests for the POC

1. transfer workflow state transition tests
2. chat orchestrator tests for main happy path
3. ambiguous payee/account resolution tests
4. summary card rendering tests
5. persistence load/recovery tests for historical sessions

### Frontend tests

Suggested structure:

```text
apps/chat2pay-web/src/
├── __tests__/
├── components/**/__tests__/
└── features/**/__tests__/
```

Recommended test types:
- message renderer tests
- sidebar/session list tests
- profile selection flow tests
- chat send / response rendering tests
- structured list and form submission tests

---

## 15. Suggested Naming Conventions

### Backend
- controllers end with `Controller`
- services end with `Service`
- orchestrators end with `Orchestrator`
- workflow classes end with `Workflow...`
- repositories end with `Repository`
- integration clients end with `Client`
- persistence entities end with `Entity`

### Frontend
- React components use PascalCase
- hooks use `useXxx`
- state stores end with `Store`
- feature actions use verb-based file names such as `sendMessage.ts`

### OpenAPI
- use stable resource names
- prefer explicit enums
- avoid overloading one endpoint with multiple unrelated behaviors

---

## 16. Suggested Generated vs Handwritten Boundaries

### Handwritten backend

```text
application/**
domain/**
integration/**
persistence/**
config/**
```

### Generated backend

```text
api/generated/**
```

### Handwritten frontend

```text
pages/**
layouts/**
components/**
features/**
store/**
services/**
```

### Generated frontend

```text
generated/api/**
```

### Rule
Generated folders must be treated as disposable outputs.
Never place handwritten business logic there.

---

## 17. Suggested POC Build Order

To reduce delivery risk, implement in this sequence:

1. repository skeleton
2. OpenAPI contract validation + generation scripts
3. backend basic boot app + profile endpoints
4. frontend profile selection page + pseudo-login
5. chat session list/create APIs
6. main chat layout + history sidebar
7. send message flow
8. lightweight workflow engine
9. downstream integration stubs
10. main transfer happy path
11. selectable list + simple form rendering
12. DB persistence hardening
13. LLM prompt tuning

---

## 18. Optional Future Evolution Structure

If the POC later grows, the repository can evolve in one of two directions.

### Option 1 - keep monorepo, split apps further

```text
apps/
├── chat2pay-backend/
├── chat2pay-web/
└── chat2pay-admin/
```

### Option 2 - split backend by bounded contexts later

For example:
- `chat2pay-chat-service`
- `chat2pay-transfer-orchestrator`
- `chat2pay-web`

For the current POC, this is unnecessary. Keep it simple.

---

## 19. Final Recommendation

For **chat2pay**, the recommended structure is:

- **one monorepo**
- **one Spring Boot backend app**
- **one React frontend app**
- **one canonical OpenAPI contract**
- **one DB migration area**
- **clear separation between generated and handwritten code**
- **orchestrator-centric backend package design**
- **feature-friendly frontend structure**

This structure is intentionally practical:
- simple enough for a POC
- clean enough to scale a bit further
- aligned with contract-first development
- aligned with the previously defined system design, API contract, DB design, and UI spec

---

## 20. Quick Reference Tree

```text
chat2pay/
├── docs/
│   ├── 01-system_design.md
│   ├── 03-db_design.md
│   ├── 04-UI_spec.md
│   └── 05-project_structure.md
├── openapi/
│   └── chat2pay-api.yaml
├── apps/
│   ├── chat2pay-backend/
│   │   ├── src/main/java/com/company/chat2pay/
│   │   │   ├── api/
│   │   │   ├── application/
│   │   │   ├── domain/
│   │   │   ├── integration/
│   │   │   ├── persistence/
│   │   │   ├── config/
│   │   │   └── common/
│   │   └── src/main/resources/
│   │       ├── application.yml
│   │       ├── db/migration/
│   │       └── prompts/
│   └── chat2pay-web/
│       ├── src/
│       │   ├── pages/
│       │   ├── layouts/
│       │   ├── components/
│       │   ├── features/
│       │   ├── services/
│       │   ├── store/
│       │   └── generated/api/
├── db/
│   ├── ddl/
│   ├── migration/
│   └── seed/
├── scripts/
│   ├── dev/
│   ├── codegen/
│   └── ci/
```
