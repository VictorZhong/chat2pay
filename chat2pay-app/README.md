# chat2pay-app

Spring Boot backend for the chat2pay POC.

## Current Scope

- profile list and shared-password login
- chat session APIs
- domestic payment to an existing payee
- backend-owned orchestration for local LLM and downstream clients
- in-memory conversation storage for the first backend slice

## Run

```bash
mvn spring-boot:run
```

The app starts on `http://localhost:8080`.

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
