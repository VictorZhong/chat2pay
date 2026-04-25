# chat2pay - Project Structure

## 1. Purpose

This document defines the repository structure that matches the updated design:

- React frontend kept as the presentation layer
- Spring Boot backend as the orchestration layer
- one API contract file for human alignment only
- no contract-copy directory
- no codegen-driven project layout

## 2. Repository Direction

The repository stays flat at the top level:

- `chat2pay-web/` for the frontend
- `chat2pay-app/` for the Spring Boot backend
- `docs/` for design documents and references
- `db/` for future migrations or seed scripts if needed

`docs/02-api_contract.yaml` is the only API contract document we keep.

We do **not** keep a second contract mirror under `api-contract/`.

## 3. Target Top-Level Structure

```text
chat2pay/
├── README.md
├── .gitignore
├── docs/
│   ├── 01-system_design.md
│   ├── 02-api_contract.yaml
│   ├── 03-db_design.md
│   ├── 05-project_structure.md
│   ├── 06-ui_implementation.md
│   └── 99-ref.md
├── chat2pay-web/
├── chat2pay-app/
└── db/
```

## 4. Frontend Structure

The frontend remains the current React + TypeScript app in `chat2pay-web/`.

Recommended structure:

```text
chat2pay-web/
├── package.json
├── index.html
├── public/
├── src/
│   ├── app/
│   ├── features/
│   ├── pages/
│   └── shared/
│       ├── api/
│       ├── config/
│       ├── lib/
│       ├── styles/
│       └── ui/
└── dist/
```

### Frontend Responsibility Summary

| Path | Responsibility |
|---|---|
| `pages/` | Profile selector and chat workspace |
| `features/` | Sidebar, chat input, message rendering, user menu |
| `shared/api/` | Handwritten request/response types and backend client calls |
| `shared/ui/` | Reusable UI primitives and light conversation status views |
| `shared/styles/` | Global design tokens and styles |

### Frontend Notes

- keep the current visual shell
- do not expand `generated/` or add codegen as a dependency path
- keep API types handwritten and intentionally small

## 5. Backend Structure

`chat2pay-app/` is the backend implementation root.

Recommended direction:

```text
chat2pay-app/
├── pom.xml
└── src/main/
    ├── java/com/chat2pay/app/
    │   ├── api/
    │   ├── application/
    │   │   ├── conversation/
    │   │   ├── profile/
    │   │   └── rendering/
    │   ├── domain/
    │   │   ├── conversation/
    │   │   ├── payment/
    │   │   └── tool/
    │   ├── integration/
    │   │   ├── llm/
    │   │   │   ├── copilot/
    │   │   │   └── remote/
    │   │   └── downstream/
    │   │       ├── auth/
    │   │       ├── payee/
    │   │       ├── domestic/
    │   │       └── international/
    │   ├── persistence/
    │   ├── config/
    │   └── common/
    └── resources/
        ├── application.yml
        └── db/
```

### Backend Responsibility Summary

| Path | Responsibility |
|---|---|
| `api/` | REST endpoints and streaming endpoints exposed to the frontend |
| `application/conversation/` | Turn orchestration, provider routing, guard checks |
| `application/profile/` | Profile listing and shared-password login |
| `application/rendering/` | Convert domain outcomes into frontend blocks |
| `domain/conversation/` | Session, message, state, and streaming event models |
| `domain/payment/` | Draft, payee, amount, confirmation, and result models |
| `domain/tool/` | Tool definitions and execution contracts |
| `integration/llm/copilot/` | Personal-subscription GitHub Copilot adapter |
| `integration/llm/remote/` | Future real API provider adapter |
| `integration/downstream/auth/` | SAML acquisition and auth reuse rules |
| `integration/downstream/payee/` | Registered payee client |
| `integration/downstream/domestic/` | Domestic payment confirm client |
| `integration/downstream/international/` | Reserved for V2 |
| `persistence/` | JPA entities, Spring Data repositories, repository-backed stores |

## 6. Design File Roles

| File | Role |
|---|---|
| `docs/01-system_design.md` | Overall architecture and orchestration model |
| `docs/02-api_contract.yaml` | Human-readable FE/BE API contract |
| `docs/03-db_design.md` | PostgreSQL schema design |
| `docs/05-project_structure.md` | Repo and package layout |
| `docs/06-ui_implementation.md` | Frontend interaction constraints |
| `docs/99-ref.md` | Behavioral reference from the working Python demo |

`99-ref.md` is a reference source, not the target code structure.

## 7. Contract Handling

The API contract is kept for manual alignment and review only.

Rules:

- keep a single contract file in `docs/02-api_contract.yaml`
- do not maintain a duplicated contract copy elsewhere
- do not generate frontend or backend models from the contract in V1
- keep the contract small enough that handwritten DTOs stay reasonable

## 8. Current Implementation Guidance

For the next implementation step:

- keep the existing frontend shell
- continue backend orchestration in `chat2pay-app/`
- keep provider routing, LLM intent/tool-decision parsing, and backend-owned
  payment execution together in the application layer
- keep V1 limited to registered payee lookup and domestic payment
- wire the frontend to backend APIs without introducing codegen or extra
  contract mirrors

## 9. Recommended Reading Order

1. `docs/01-system_design.md`
2. `docs/06-ui_implementation.md`
3. `docs/02-api_contract.yaml`
4. `docs/03-db_design.md`
5. `docs/99-ref.md`
