# chat2pay

Chat2Pay is a Spring Boot + React POC for registered-payee lookup and domestic-payment orchestration.

The backend owns payment guardrails, PostgreSQL persistence, Flyway migrations, downstream payment calls, and the LLM tool loop. The frontend renders chat history and structured interaction blocks.

## Prerequisites

- JDK 21+
- Maven 3.9+
- Node.js 20+
- PostgreSQL 16

## PostgreSQL and Flyway

Run PostgreSQL locally. If you are using the PG 16 image already provided in this POC:

```bash
docker run --name chat2pay-pg16 \
  -e POSTGRES_DB=chat2pay \
  -e POSTGRES_USER=chat2pay \
  -e POSTGRES_PASSWORD=chat2pay \
  -p 5432:5432 \
  -d sha256:fc6b782c1e7274e95ea96e2c9958b2e38135ae9e22c623c02dca38e5d84197f8
```

Backend DB settings:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/chat2pay
export DB_USER=chat2pay
export DB_PASSWORD=chat2pay
```

Flyway runs automatically on backend startup. Application tables and Flyway history are all prefixed with `ctp_`; the history table is `ctp_flyway_schema_history`.

## Demo Profile Setup

Profiles are manual in the POC. Insert at least one active profile before using the real backend:

```sql
insert into ctp_profile (
  id,
  guid,
  perm_net_id,
  profile_code,
  username,
  password,
  display_name,
  locale,
  supported_capabilities_json,
  status,
  debit_account_number,
  debit_product_category_code,
  payment_currency
) values (
  'profile_poc',
  'profile-poc-guid',
  '11114418_O88',
  'poc',
  'poc.user',
  '<testdataservice-profile-password>',
  'POC User',
  'en-HK',
  '["REGISTERED_PAYEE_LOOKUP","DOMESTIC_PAYMENT"]'::jsonb,
  'ACTIVE',
  '<profile-debit-account-number>',
  'CUR',
  'HKD'
) on conflict (id) do update set
  guid = excluded.guid,
  perm_net_id = excluded.perm_net_id,
  username = excluded.username,
  password = excluded.password,
  display_name = excluded.display_name,
  supported_capabilities_json = excluded.supported_capabilities_json,
  status = excluded.status,
  debit_account_number = excluded.debit_account_number,
  debit_product_category_code = excluded.debit_product_category_code,
  payment_currency = excluded.payment_currency,
  updated_at = now();
```

The profile selector is intentionally lightweight for the POC: the frontend submits the fixed access password `tb123`, and the backend only uses that value to gate profile selection. The `ctp_profile.password` column is not the UI login password; it is the plaintext test-data-service password paired with `ctp_profile.username` for SAML3 token generation when real downstream mode is enabled. Runtime profile credentials/config are cached by the backend for one day.

## Personal Copilot Token

V1 reads personal Copilot credentials from `ctp_llm_credential`, so token rotation does not require redeploying or restarting.

Insert or rotate the GitHub token used for the Copilot session-token exchange:

```sql
insert into ctp_llm_credential (
  provider,
  api_key,
  session_token,
  session_token_expires_at,
  metadata_json,
  updated_at
) values (
  'COPILOT_PERSONAL',
  '<github-token>',
  null,
  null,
  '{}'::jsonb,
  now()
) on conflict (provider) do update set
  api_key = excluded.api_key,
  session_token = null,
  session_token_expires_at = null,
  updated_at = now();
```

The backend exchanges that key at `https://api.github.com/copilot_internal/v2/token`, caches the short-lived session token in the same row, and refreshes it when it expires.

Optional bootstrap env vars exist only for first startup when the DB row is missing:

```bash
export LLM_API_KEY=<github-token>
export COPILOT_SESSION_TOKEN=<short-lived-token-if-you-already-have-one>
```

Corporate proxy for Copilot/GitHub traffic. Prefer the single URL form, which matches the refresh-token script in `docs/99-ref.md`:

```bash
export LLM_PROXY_URL='http://<url-encoded-user>:<url-encoded-password>@<proxy-host>:<proxy-port>'
```

The split settings are also supported:

```bash
export LLM_PROXY_HOST=<proxy-host>
export LLM_PROXY_PORT=<proxy-port>
export LLM_PROXY_USERNAME=<proxy-user>
export LLM_PROXY_PASSWORD_B64=$(node -e "console.log(Buffer.from(process.argv[1]).toString('base64'))" '<plain-password>')
```

If token refresh fails with `407 Proxy Authentication Required`, check that `LLM_PROXY_URL` contains URL-encoded credentials or that `LLM_PROXY_PASSWORD_B64` is the base64 of the plaintext password. The app enables JDK Basic proxy authentication and sends proxy auth preemptively for the Copilot token and completion calls.

## LLM Provider Selection

Default V1 provider:

```bash
export CHAT2PAY_PRIMARY_PROVIDER=COPILOT_PERSONAL
export CHAT2PAY_FALLBACK_PROVIDER=REMOTE_API
```

To disable LLM intent/tool handling and force local fallback parsing:

```bash
export CHAT2PAY_INTENT_USE_LLM=false
```

For a future OpenAI-compatible remote provider, configure:

```bash
export CHAT2PAY_PRIMARY_PROVIDER=REMOTE_API
export REMOTE_LLM_BASE_URL=https://<provider-host>/v1
export REMOTE_LLM_API_KEY=<api-key>
export REMOTE_LLM_MODEL=<model-name>
```

Both `COPILOT_PERSONAL` and `REMOTE_API` use the same backend LLM tool loop. Payment execution is still guarded by backend state, registered-payee validation, and explicit user confirmation.

## Backend Mock vs Real Downstream

The backend always uses PostgreSQL for sessions, drafts, messages, profiles, credentials, and seeded mock payees.

Local/mock downstream mode is the default:

```bash
export PAYMENT_MOCK_ENABLED=true
```

In this mode, payees come from `ctp_registered_payee` seeded by Flyway and domestic payment confirmation returns a mock reference.

Real downstream mode:

```bash
export PAYMENT_MOCK_ENABLED=false
export PAYMENT_LOGIN_URL='https://.../{username}/SAML3/30'
export PAYMENT_PAYEE_URL='https://.../payees'
export PAYMENT_CONFIRM_URL='https://.../confirm-domestic-payments'
```

The login username/password, debit account number, product category, and payment currency are read from `ctp_profile` for the active profile. Optional downstream headers and defaults are configured through `PAYMENT_CHANNEL_ID`, `PAYMENT_COUNTRY_CODE`, `PAYMENT_GROUP_MEMBER`, `PAYMENT_LOCALE`, `PAYMENT_SOURCE_SYSTEM_ID`, `PAYMENT_DEVICE_ID`, and `PAYMENT_USER_AGENT`; `perm_net_id` is used as the source system id when present.

Backend logging defaults to DEBUG for the POC, including SQL binding and downstream/Copilot call summaries. CORS is open for `/api/**` so the same build can run behind changing k8s ingress domains.

## Run the Backend

```bash
cd chat2pay-app
mvn spring-boot:run
```

Useful backend checks:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/profiles
```

## Frontend Mock vs Real Backend

The frontend defaults to its in-browser mock server:

```bash
cd chat2pay-web
npm install
npm run dev
```

To use the Spring Boot backend through the Vite proxy:

```bash
cd chat2pay-web
VITE_USE_MOCK_API=false VITE_API_BASE_URL=/api npm run dev
```

If the frontend is served separately from Vite, point it directly at the backend:

```bash
VITE_USE_MOCK_API=false VITE_API_BASE_URL=http://localhost:8080/api npm run dev
```

## Verification

Backend:

```bash
cd chat2pay-app
mvn test
```

Frontend:

```bash
cd chat2pay-web
npm test
npm run build
```
