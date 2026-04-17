# chat2pay-app

Spring Boot backend for the chat2pay POC.

## Current Scope

- profile list and shared-password login
- chat session APIs
- domestic payment to an existing payee
- backend-owned orchestration for local LLM and downstream clients
- PostgreSQL persistence for profiles, chat sessions, messages, and drafts
- Flyway-managed schema with `ctp_` table prefixes

## Run

```bash
docker compose up -d
mvn spring-boot:run
```

The app starts on `http://localhost:8080`.

Default local database settings:

- database: `chat2pay`
- username: `chat2pay`
- password: `chat2pay`
- JDBC URL: `jdbc:postgresql://localhost:5432/chat2pay`

You can override these with `CHAT2PAY_DB_URL`, `CHAT2PAY_DB_USERNAME`, and
`CHAT2PAY_DB_PASSWORD`.

## Test

```bash
mvn test
```

## Notes

- Local LLM integration is wired to `http://localhost:8000/api/chat` and falls
  back to deterministic parsing if the local service is unavailable.
- Downstream clients are implemented behind interfaces.
- `chat2pay.downstream.mock-enabled=true` by default so the backend can run
  end-to-end without external dependencies while the rest of the stack is being
  integrated.
- Tests run against H2 with Flyway migrations mirrored from the PostgreSQL
  schema.
