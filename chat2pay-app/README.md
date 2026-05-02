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

- LLM integration runs through a per-use-case router (`LlmRouter`) with two
  pluggable providers, both selectable from `application.yml`:
  - `COPILOT` (default): the existing local agent at
    `http://localhost:8000/api/chat`.
  - `REMOTE`: an internal-network OpenAI-style chat completion endpoint reached
    after fetching a JWT from the IB2B token translator and attaching
    `X-zzzz-E2E-Trust-Token` + `X-zzzz-Request-Correlation-Id` headers. The
    target URL is resolved per model; declare each model under
    `chat2pay.llm.remote.models`.
- If the LLM is unreachable or the response can't be parsed, the planner falls
  back to deterministic heuristics (`fallback-to-heuristics: true`). Each use
  case can also declare a cross-provider `fallback-provider` (e.g. try `REMOTE`
  first, then `COPILOT`) before the heuristic fallback kicks in.
- Remote credentials default from env vars `CHAT2PAY_REMOTE_LLM_USERNAME` and
  `CHAT2PAY_REMOTE_LLM_PASSWORD`; tokens are cached for
  `chat2pay.llm.remote.auth.token-ttl-seconds` (default 30 min).
- To route the journey planner to a remote model, set:

  ```yaml
  chat2pay:
    llm:
      remote:
        enabled: true
        models:
          - name: Qwen3-32B-AWQ
            url: https://internal-host/v1/chat/completions
      use-cases:
        journey-planner:
          provider: REMOTE
          model: Qwen3-32B-AWQ
          fallback-provider: COPILOT
  ```

  Adding a new use case (e.g. chat-title generation) is just another entry
  under `chat2pay.llm.use-cases.*`; the same router resolves provider + model.
- Downstream clients are implemented behind interfaces.
- `chat2pay.downstream.mock-enabled=true` by default so the backend can run
  end-to-end without external dependencies while the rest of the stack is being
  integrated.
- Tests run against H2 with Flyway migrations mirrored from the PostgreSQL
  schema.
