# chat2pay - Project Structure

## 1. Purpose

This document describes the repository structure that should support the current
POC direction:

- existing frontend UI kept mostly intact
- new Spring Boot backend added under `chat2pay-app/`
- design and contract docs updated for backend-owned LLM and payment
  orchestration

## 2. Repository Strategy

The project remains a single repository with application folders directly under
the repo root:

- `chat2pay-web/` for the frontend
- `chat2pay-app/` for the Spring Boot backend
- `docs/` for design references
- `api-contract/` for the implementation-facing contract copy

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
│   ├── 06-ui_implementation.md
│   ├── 10-local_LLM.md
│   └── 11-backend-skill.md
├── api-contract/
│   └── chat2pay-api.yaml
├── chat2pay-web/
├── chat2pay-app/
│   ├── compose.yaml
│   └── src/main/resources/db/migration/postgresql/
└── chat2pay-app/src/test/resources/db/migration/h2/
```

## 4. Frontend Direction

The frontend remains the current React + TypeScript implementation in
`chat2pay-web/`.

Key rule:

- preserve the current page structure and visual direction
- add real backend APIs without removing the existing mock flow
- allow a lightweight switch between mock and backend mode for local demos
- keep orchestration out of the frontend

## 5. Frontend Structure

```text
chat2pay-web/
├── package.json
├── index.html
├── public/
├── src/
│   ├── app/
│   ├── features/
│   ├── pages/
│   ├── shared/
│   │   ├── api/
│   │   ├── config/
│   │   ├── lib/
│   │   ├── styles/
│   │   └── ui/
│   └── generated/
└── dist/
```

### Frontend responsibility summary

| Path | Responsibility |
|---|---|
| `pages/` | Route-level screens such as profile selection and chat workspace |
| `features/` | Chat input, sidebar, message rendering, structured UI events |
| `shared/api/` | Frontend API client and contract-aligned DTOs |
| `shared/ui/` | Reusable branded UI primitives |
| `shared/styles/` | Global styling, tokens, motion |
| `generated/` | Generated artifacts if OpenAPI codegen is introduced later |

## 6. Backend Direction

`chat2pay-app/` should become the backend implementation root.

Recommended direction:

```text
chat2pay-app/
├── compose.yaml
├── pom.xml
└── src/main/
    ├── java/com/company/chat2pay/
    │   ├── api/
    │   ├── application/
    │   │   ├── chat/
    │   │   ├── profile/
    │   │   └── journey/
    │   ├── domain/
    │   │   ├── conversation/
    │   │   └── payment/
    │   ├── integration/
    │   │   ├── llm/
    │   │   └── downstream/
    │   ├── persistence/
    │   ├── config/
    │   └── common/
    └── resources/
        ├── application.yml
        └── db/migration/postgresql/
```

### Backend responsibility summary

| Path | Responsibility |
|---|---|
| `api/` | REST controllers exposed to the frontend |
| `application/chat/` | Turn orchestration and response assembly |
| `application/journey/` | Journey handlers, backend planning agent loop, and tool registry/handlers that turn tool results into next-step decisions |
| `integration/llm/` | Local HTTP LLM adapter and future remote provider adapter |
| `integration/downstream/` | All downstream clients, auth helpers, and downstream request/response models; add new downstream APIs here without changing frontend shape |
| `persistence/` | Repositories and database mappings |
| `src/main/resources/db/migration/postgresql/` | PostgreSQL Flyway migrations using `ctp_` table prefixes |
| `src/test/resources/db/migration/h2/` | Test-only Flyway migrations mirroring the runtime schema |
| `compose.yaml` | Local PostgreSQL runtime for the backend POC |

## 7. Contract Placement

The canonical implementation-facing copy is:

```text
api-contract/chat2pay-api.yaml
```

The design copy remains in:

```text
docs/02-api_contract.yaml
```

These two files should stay identical.

## 8. Design References

Use these docs together:

- `docs/01-system_design.md`
- `docs/03-db_design.md`
- `docs/06-ui_implementation.md`
- `docs/10-local_LLM.md`
- `docs/11-backend-skill.md`

Recommended reading order for implementation:

1. `docs/01-system_design.md`
2. `docs/11-backend-skill.md`
3. `docs/10-local_LLM.md`
4. `docs/02-api_contract.yaml`
5. `docs/03-db_design.md`

## 9. Current Recommendation

For the next implementation step:

- keep the repository flat
- leave the current frontend UI structure in place
- build the Spring Boot backend in `chat2pay-app/`
- switch frontend API calls from mock flow to backend endpoints
- treat `docs/11-backend-skill.md` as the behavioral reference for the first
  payment journey

## 10. How To Add A New Journey

For a new journey such as `INTERNATIONAL_EXISTING_PAYEE`, keep the extension
pattern consistent:

1. Add a new journey handler in `application/journey/`.
2. Add one or more bounded tool handlers in `application/journey/` for the new
   business capabilities such as `limit_check`, `fraud_check`, or `get_fx_quote`.
3. Add the matching downstream client plus request/response models in
   `integration/downstream/`.
4. Reuse the existing `ChatApiController` and frontend endpoints. Do not add a
   dedicated frontend orchestration path.
5. Reuse existing content block types for the UI. Do not create a new page or a
   journey-specific wizard unless the conversation model truly breaks down.
6. Add a Flyway migration only if existing draft/session JSON storage is no
   longer sufficient.

Recommended naming pattern:

- journey handler: `InternationalExistingPayeeJourney`
- tool handlers: `LimitCheckToolHandler`, `FraudCheckToolHandler`,
  `GetFxQuoteToolHandler`
- downstream clients: `HttpLimitCheckClient`, `HttpFraudCheckClient`,
  `HttpFxQuoteClient`
- downstream DTOs: `LimitCheckRequest`, `LimitCheckResponse`, and so on

This keeps the system open for new journeys while keeping the top-level backend
shape stable.
