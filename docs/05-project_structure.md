# chat2pay - Project Structure

## 1. Purpose

This document describes the **current repository structure** used by the POC.

It replaces the earlier `apps/`-based recommendation.

## 2. Repository Strategy

The project remains a single repository, but the top-level application folders
now live directly under the repo root:

- `chat2pay-web/` for the frontend
- `chat2pay-app/` reserved for the backend

This keeps the repo simple while still separating frontend, backend, contract,
and design assets.

## 3. Current Top-Level Structure

```text
chat2pay/
├── README.md
├── .gitignore
├── docs/
│   ├── 01-system_design.md
│   ├── 02-api_contract.yaml
│   ├── 03-db_design.md
│   ├── 05-project_structure.md
│   └── 06-ui_implementation.md
├── api-contract/
│   └── chat2pay-api.yaml
├── chat2pay-web/
├── chat2pay-app/
└── db/   (reserved for future migrations / seed data)
```

## 4. Frontend Technology Direction

The current frontend implementation uses:

- React + TypeScript
- Vite
- React Router
- TanStack Query
- Zustand
- Tailwind CSS
- custom UI primitives and icons

The earlier Ant Design-based suggestion is no longer the active direction.

## 5. Frontend Structure

Current implemented path:

```text
chat2pay-web/
├── package.json
├── index.html
├── public/
├── src/
│   ├── app/
│   │   ├── providers/
│   │   └── router/
│   ├── entities/
│   ├── features/
│   │   ├── auth/
│   │   ├── chat-input/
│   │   ├── message-renderer/
│   │   ├── session-history/
│   │   ├── sidebar/
│   │   ├── ui-events/
│   │   └── user-menu/
│   ├── generated/
│   │   └── openapi/
│   ├── pages/
│   │   ├── chat-workspace/
│   │   └── profile-selector/
│   ├── shared/
│   │   ├── api/
│   │   ├── config/
│   │   ├── lib/
│   │   ├── styles/
│   │   └── ui/
│   └── test/
└── dist/
```

### Frontend responsibility summary

| Path | Responsibility |
|---|---|
| `app/` | app-level providers and router |
| `pages/` | route-level screens |
| `features/` | feature-specific UI and behavior |
| `shared/api/` | contract-aligned mock and client code |
| `shared/ui/` | reusable branded UI primitives |
| `shared/styles/` | global styling, tokens, motion |
| `generated/openapi/` | generated contract artifacts |

## 6. Backend Reservation

`chat2pay-app/` is reserved for the future Spring Boot backend.

Recommended direction:

```text
chat2pay-app/
├── pom.xml or build.gradle
├── src/main/java/com/company/chat2pay/
│   ├── api/
│   ├── application/
│   ├── domain/
│   ├── integration/
│   ├── persistence/
│   ├── config/
│   └── common/
└── src/main/resources/
```

## 7. API Contract Placement

The canonical contract copy for implementation work is:

```text
api-contract/chat2pay-api.yaml
```

The design copy remains under:

```text
docs/02-api_contract.yaml
```

## 8. UI Documentation Status

Use:

- `docs/06-ui_implementation.md`
- `chat2pay-web/`

as the practical UI reference.

## 9. Current Recommendation

For the current POC:

- keep the repository flat and simple
- keep `chat2pay-web/` as the implemented frontend
- use `chat2pay-app/` for upcoming backend work
- treat `api-contract/` as the implementation-facing contract source
- keep `docs/` aligned with the actual codebase rather than earlier sketches
