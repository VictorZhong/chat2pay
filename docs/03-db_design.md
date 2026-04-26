# chat2pay - Database Design

## 1. Purpose

This document defines the PostgreSQL persistence design for the updated POC.

The database should support:

- profile selection
- chat session history
- assistant message rendering
- one active domestic payment draft per session
- later extension to cross-border ORTT payment and more tools

V1 uses PostgreSQL only. Redis is not part of the design.

## 2. Storage Principles

1. **PostgreSQL is the only shared state store.**
   Sessions, messages, and drafts are persisted in PostgreSQL.

2. **All physical tables use the `ctp_` prefix.**
   This includes Flyway's schema history table:
   `ctp_flyway_schema_history`.

3. **Keep the schema small in V1.**
   We only store what is needed for session recovery, rendering, and payment
   continuation.

4. **Use JSONB for flexible integration context.**
   Tool results, provider metadata, and future downstream references belong in
   JSONB fields instead of forcing a wide relational schema in V1.

5. **Do not introduce Redis-style coordination concepts.**
   If a short TTL cache is needed later, it can stay in-process and
   non-authoritative.

6. **No separate workflow log table in V1.**
   Keep diagnostics in `ctp_chat_message.metadata_json` and
   `ctp_payment_draft.context_json`. Add a dedicated audit table only when there is
   a real need.

7. **Do not make chat2pay the payee system of record.**
   Real payee lookup, creation, and update flows belong to downstream APIs.
   Local payee tables exist only as POC/mock fixtures while
   `PAYMENT_MOCK_ENABLED=true`.

## 3. Logical ER Diagram

`CTP_REGISTERED_PAYEE` and `CTP_PAYEE_ALIAS` are shown because the current
schema still carries mock fixture tables. They are not target production
business storage; real-mode payee data is fetched live from downstream APIs.

```mermaid
erDiagram
    CTP_PROFILE ||--o{ CTP_CHAT_SESSION : owns
    CTP_CHAT_SESSION ||--o{ CTP_CHAT_MESSAGE : contains
    CTP_CHAT_SESSION ||--o| CTP_PAYMENT_DRAFT : has_active_draft
    CTP_REGISTERED_PAYEE ||--o{ CTP_PAYEE_ALIAS : mock_has

    CTP_PROFILE {
        varchar(64) id PK
        varchar(64) guid
        varchar(128) perm_net_id
        varchar(64) profile_code
        varchar(128) username
        text password
        varchar(128) display_name
        varchar(256) avatar_url
        varchar(16) locale
        jsonb supported_capabilities_json
        varchar(16) status
        text debit_account_number
        varchar(16) debit_product_category_code
        varchar(3) payment_currency
        varchar(128) source_system_id
        varchar(128) payee_source_system_id
        varchar(128) domestic_payment_source_system_id
        varchar(128) cross_border_payment_source_system_id
        timestamptz created_at
        timestamptz updated_at
    }

    CTP_CHAT_SESSION {
        varchar(64) id PK
        varchar(64) profile_id FK
        varchar(160) title
        boolean title_locked
        varchar(24) status
        varchar(48) state
        varchar(32) llm_provider
        varchar(64) active_draft_id FK
        varchar(160) last_message_preview
        int message_count
        timestamptz created_at
        timestamptz updated_at
        timestamptz archived_at
    }

    CTP_CHAT_MESSAGE {
        varchar(64) id PK
        varchar(64) profile_id FK
        varchar(64) session_id FK
        int sequence_no
        varchar(16) role
        varchar(24) kind
        text content_text
        jsonb content_blocks_json
        jsonb metadata_json
        timestamptz created_at
    }

    CTP_PAYMENT_DRAFT {
        varchar(64) id PK
        varchar(64) profile_id FK
        varchar(64) session_id FK
        varchar(32) payment_type
        varchar(32) status
        text payee_query_text
        text selected_payee_id
        text selected_payee_name
        text selected_payee_type
        text selected_bank_code
        text selected_bank_name
        text selected_account_number
        text selected_display_label
        numeric(18,2) amount
        varchar(3) currency
        date payment_date
        timestamptz user_confirmed_at
        text downstream_reference
        varchar(64) last_error_code
        text last_error_message
        jsonb context_json
        timestamptz created_at
        timestamptz updated_at
        timestamptz completed_at
    }

    CTP_REGISTERED_PAYEE {
        varchar(64) id PK
        varchar(64) profile_id
        varchar(160) name
        varchar(32) payee_type
        varchar(32) bank_code
        varchar(160) bank_name
        varchar(64) account_number
        varchar(160) display_label
        timestamptz created_at
    }

    CTP_PAYEE_ALIAS {
        varchar(64) profile_id
        varchar(64) payee_id PK,FK
        varchar(160) alias PK
    }

    CTP_LLM_CREDENTIAL {
        varchar(32) provider PK
        text api_key
        text session_token
        timestamptz session_token_expires_at
        jsonb metadata_json
        timestamptz updated_at
    }
```

## 4. Table Design

## 4.1 `ctp_profile`

Stores the predefined demo profiles shown on the landing page.

### Important Columns

| Column | Type | Notes |
|---|---|---|
| `id` | `varchar(64)` | ULID primary key |
| `guid` | `varchar(64)` | POC profile GUID |
| `perm_net_id` | `varchar(128)` | Profile PermNet identity; not used as downstream source system id |
| `profile_code` | `varchar(64)` | Internal display code |
| `username` | `varchar(128)` | Test-data-service login username |
| `password` | `text` | Plaintext test-data-service password for SAML3 token generation; not the UI profile-selection password |
| `display_name` | `varchar(128)` | UI display name |
| `avatar_url` | `varchar(256)` | Optional avatar |
| `locale` | `varchar(16)` | Example `en-HK` |
| `supported_capabilities_json` | `jsonb` | Example `["REGISTERED_PAYEE_LOOKUP","DOMESTIC_PAYMENT"]` |
| `status` | `varchar(16)` | `ACTIVE` / `INACTIVE` |
| `debit_account_number` | `text` | Profile-specific debit account used for real payment confirmation |
| `debit_product_category_code` | `varchar(16)` | Profile-specific debit account product category, example `CUR` |
| `payment_currency` | `varchar(3)` | Profile-specific payment currency used by real downstream payment calls |
| `source_system_id` | `varchar(128)` | Default downstream source system id for this profile |
| `payee_source_system_id` | `varchar(128)` | Optional source system id override for payee lookup APIs |
| `domestic_payment_source_system_id` | `varchar(128)` | Optional source system id override for domestic payment confirm APIs |
| `cross_border_payment_source_system_id` | `varchar(128)` | Optional source system id override for future cross-border ORTT APIs |

## 4.2 `ctp_chat_session`

Represents one visible conversation thread in the sidebar.

### Important Columns

| Column | Type | Notes |
|---|---|---|
| `id` | `varchar(64)` | ULID primary key |
| `profile_id` | `varchar(64)` | FK to `ctp_profile.id` |
| `title` | `varchar(160)` | Sidebar title |
| `title_locked` | `boolean` | Set when the user manually renamed the session; AI title suggestions stop updating locked rows |
| `status` | `varchar(24)` | `ACTIVE`, `COMPLETED`, `FAILED`, `CANCELLED`, `ARCHIVED` |
| `state` | `varchar(48)` | Current conversation state |
| `llm_provider` | `varchar(32)` | Example `COPILOT_PERSONAL` |
| `active_draft_id` | `varchar(64)` | Optional FK to `ctp_payment_draft.id` |
| `last_message_preview` | `varchar(160)` | Fast sidebar rendering |
| `message_count` | `integer` | Denormalized for list rendering |
| `created_at` | `timestamptz` | Creation time |
| `updated_at` | `timestamptz` | Last activity time |
| `archived_at` | `timestamptz` | Set when archived |

### Persisted Session States

- `IDLE`
- `COLLECTING_DETAILS`
- `AWAITING_PAYEE_SELECTION`
- `AWAITING_CONFIRMATION`
- `EXECUTING`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

For a standalone payee lookup, the session can end the turn in `IDLE`.

## 4.3 `ctp_chat_message`

Stores the visible conversation plus important structured payloads.

### Important Columns

| Column | Type | Notes |
|---|---|---|
| `id` | `varchar(64)` | ULID primary key |
| `profile_id` | `varchar(64)` | Direct FK to `ctp_profile.id` for profile isolation |
| `session_id` | `varchar(64)` | FK to `ctp_chat_session.id` |
| `sequence_no` | `integer` | Ordered within session |
| `role` | `varchar(16)` | `USER`, `ASSISTANT`, `SYSTEM` |
| `kind` | `varchar(24)` | `TEXT`, `BLOCKS`, `UI_EVENT`, `SYSTEM` |
| `content_text` | `text` | Plain assistant or user text |
| `content_blocks_json` | `jsonb` | Renderable frontend blocks |
| `metadata_json` | `jsonb` | Stream metadata, processing timing, tool summaries, provider info |
| `created_at` | `timestamptz` | Creation time |

### Notes

- assistant deltas do not need separate rows
- persist the final assembled assistant message
- long conversation content is stored in `text`/`jsonb`; `ctp_chat_session.title`
  and `last_message_preview` stay bounded because they are derived display fields
- store assistant turn duration in `metadata_json.processingMs`
- if needed, store a short tool summary in `metadata_json`

## 4.4 `ctp_payment_draft`

Stores the current or completed domestic payment draft for a session.

### Important Columns

| Column | Type | Notes |
|---|---|---|
| `id` | `varchar(64)` | ULID primary key |
| `profile_id` | `varchar(64)` | Direct FK to `ctp_profile.id` for profile isolation |
| `session_id` | `varchar(64)` | Unique FK to `ctp_chat_session.id` |
| `payment_type` | `varchar(32)` | V1 value `DOMESTIC_PAYMENT` |
| `status` | `varchar(32)` | `DRAFT`, `AWAITING_CONFIRMATION`, `EXECUTING`, `CONFIRMED`, `FAILED`, `CANCELLED` |
| `payee_query_text` | `text` | Raw or normalized user payee text |
| `selected_payee_id` | `text` | Opaque downstream payee identifier; not truncated because it is used for confirmation |
| `selected_payee_name` | `text` | User-facing payee name |
| `selected_payee_type` | `text` | Downstream payee type |
| `selected_bank_code` | `text` | Payee bank code |
| `selected_bank_name` | `text` | Payee bank name |
| `selected_account_number` | `text` | Display-safe account identifier |
| `selected_display_label` | `text` | Product/account label shown in confirmation |
| `amount` | `numeric(18,2)` | Payment amount |
| `currency` | `varchar(3)` | Copied from the active profile payment currency |
| `payment_date` | `date` | Date only, no time-of-day in V1 |
| `user_confirmed_at` | `timestamptz` | Set when user explicitly confirms |
| `downstream_reference` | `text` | Confirm response reference if any |
| `last_error_code` | `varchar(64)` | Downstream or internal error code |
| `last_error_message` | `text` | Last failure message |
| `context_json` | `jsonb` | Extra tool context and future extension fields |
| `created_at` | `timestamptz` | Creation time |
| `updated_at` | `timestamptz` | Last update time |
| `completed_at` | `timestamptz` | Set on terminal success |

### Notes

- payee query alone does not require a draft row
- V2 cross-border ORTT can extend `context_json`, add targeted columns, or add
  a dedicated proposal table when propose/confirm state becomes concrete

## 4.5 `ctp_registered_payee` (mock fixture only)

Stores the local POC registered-payee fixture used when
`PAYMENT_MOCK_ENABLED=true`.

For real downstream mode (`PAYMENT_MOCK_ENABLED=false`), payees are fetched from
`PAYMENT_PAYEE_URL`; selected downstream `payeeIdIndex` values are persisted in
`ctp_payment_draft.selected_payee_id`.

This table should not be treated as a product payee directory. Future payee
create/update flows should call downstream payee APIs directly. If mock mode is
kept long term, this fixture can be isolated behind test-only migrations or a
separate mock-data module.

### Important Columns

| Column | Type | Notes |
|---|---|---|
| `id` | `varchar(64)` | Local mock payee id |
| `profile_id` | `varchar(64)` | POC profile that owns this seeded payee |
| `name` | `varchar(160)` | User-facing payee name |
| `payee_type` | `varchar(32)` | Domestic downstream payee type |
| `bank_code` | `varchar(32)` | Payee bank code |
| `bank_name` | `varchar(160)` | Payee bank name |
| `account_number` | `varchar(64)` | Display-safe account identifier |
| `display_label` | `varchar(160)` | Product/account label |

## 4.6 `ctp_payee_alias` (mock fixture only)

Stores local alias hints for regex fallback and DB-seeded POC payee lookup.
Aliases are not a production matching source; real payee matching should use
the live downstream payee payload returned for the active user/profile.

| Column | Type | Notes |
|---|---|---|
| `profile_id` | `varchar(64)` | Denormalized profile owner for alias lookup |
| `payee_id` | `varchar(64)` | FK to `ctp_registered_payee.id` |
| `alias` | `varchar(160)` | Lowercase alias used for fallback matching |

## 4.7 `ctp_llm_credential`

Stores LLM provider credentials and short-lived session tokens.

| Column | Type | Notes |
|---|---|---|
| `provider` | `varchar(32)` | `COPILOT_PERSONAL` / `REMOTE_API` |
| `api_key` | `text` | GitHub token/PAT used for Copilot session-token exchange |
| `session_token` | `text` | Cached Copilot bearer token |
| `session_token_expires_at` | `timestamptz` | Cached token expiry |
| `metadata_json` | `jsonb` | Future provider metadata |
| `updated_at` | `timestamptz` | Last credential/token update |

## 5. Indexing Strategy

### `ctp_profile`

- unique index on `profile_code`
- unique index on `username`
- index on `status`

### `ctp_chat_session`

- index on `profile_id, updated_at desc`
- index on `state`

### `ctp_chat_message`

- unique index on `session_id, sequence_no`
- index on `session_id, created_at`
- index on `profile_id, session_id, created_at`

### `ctp_payment_draft`

- unique index on `session_id`
- index on `status`
- index on `profile_id, status`

### `ctp_registered_payee` (mock fixture)

- index on `profile_id, name, account_number`

### `ctp_payee_alias` (mock fixture)

- primary key on `payee_id, alias`
- index on `alias`
- index on `profile_id, alias`

## 6. DDL

The executable schema is version-controlled in Flyway migrations under
`chat2pay-app/src/main/resources/db/migration/`.

- `V1__init_schema.sql` creates `ctp_profile`, `ctp_chat_session`,
  `ctp_payment_draft`, `ctp_chat_message`, mock-fixture `ctp_registered_payee`,
  `ctp_payee_alias`, and `ctp_llm_credential`.
- `V2__seed_payees.sql` seeds the POC registered payee fixture for local mock
  mode only.
- `V3__profile_scoped_runtime_config.sql` adds profile runtime credentials,
  profile-scoped payment config, and direct `profile_id` partition keys to
  messages, drafts, and seeded payees.
- `V4__session_title_locked.sql` adds a manual-title lock for AI title
  suggestions.
- `V5__profile_source_system_ids.sql` adds profile-scoped source system ids
  used by downstream auth headers.
- Flyway itself uses `ctp_flyway_schema_history`, configured through
  `spring.flyway.table`.

Do not maintain a second copy of executable DDL in this document. Schema
changes should be made through a new Flyway migration.

## 7. Seed Data Guidance

For V1:

- insert one or a few `ACTIVE` profiles manually
- keep `supported_capabilities_json` as `["REGISTERED_PAYEE_LOOKUP","DOMESTIC_PAYMENT"]`
- set `username` and plaintext downstream `password` on the profile; these are used to
  call the test data service for SAML3 token generation in real downstream mode
- set `debit_account_number`, `debit_product_category_code`, and
  `payment_currency` per profile; these values are not global yaml settings
- set `source_system_id` or endpoint-specific overrides such as
  `payee_source_system_id` and `domestic_payment_source_system_id` per profile;
  do not derive source system id from `perm_net_id`
- keep profile display data lightweight
- use `V2__seed_payees.sql` only for local POC/mock mode payee data; those seed
  rows are assigned to `profile_poc` by V3 unless the operator updates them
- do not seed real customer payees into chat2pay; real payee lists must come
  from the downstream payee API at request time

## 8. Operational Notes

- update `ctp_chat_session.updated_at` on every meaningful turn
- increment `message_count` transactionally with message inserts
- persist only the final assistant message, not every stream delta
- store downstream response summaries in `ctp_payment_draft.context_json`
- if provider fallback happens, update `ctp_chat_session.llm_provider`
