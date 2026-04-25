# chat2pay-app

Spring Boot backend for Chat2Pay.

Implemented backend responsibilities:

- PostgreSQL persistence through JPA repositories
- Flyway schema migrations with `ctp_` table prefix
- profile, session, message, draft, payee, and LLM credential storage
- provider-agnostic LLM tool loop
- personal Copilot provider with DB-backed token/session refresh
- optional OpenAI-compatible `REMOTE_API` provider
- downstream registered-payee lookup and domestic-payment confirmation

See the repository [README.md](../README.md) for DB setup, profile SQL, Copilot token rotation, proxy settings, and mock/real environment switching.


## 密码替换
@ -> %40
: -> %3A
/ -> %2F
? -> %3F
# -> %23
% -> %25
空格 -> %20
+ -> %2B
