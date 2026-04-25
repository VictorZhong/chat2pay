# chat2pay - Database Design

## 1. Purpose

This document defines the PostgreSQL persistence design for the updated POC.

The database should support:

- profile selection
- chat session history
- assistant message rendering
- one active domestic payment draft per session
- later extension to international payment and more tools

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

## 3. Logical ER Diagram

```mermaid
erDiagram
    CTP_PROFILE ||--o{ CTP_CHAT_SESSION : owns
    CTP_CHAT_SESSION ||--o{ CTP_CHAT_MESSAGE : contains
    CTP_CHAT_SESSION ||--o| CTP_PAYMENT_DRAFT : has_active_draft

    CTP_PROFILE {
        varchar(64) id PK
        varchar(64) profile_code
        varchar(128) username
        varchar(128) display_name
        varchar(256) avatar_url
        varchar(16) locale
        jsonb supported_capabilities_json
        varchar(16) status
        timestamptz created_at
        timestamptz updated_at
    }

    CTP_CHAT_SESSION {
        varchar(64) id PK
        varchar(64) profile_id FK
        varchar(160) title
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
        varchar(64) session_id FK
        varchar(32) payment_type
        varchar(32) status
        varchar(160) payee_query_text
        varchar(128) selected_payee_id
        varchar(160) selected_payee_name
        varchar(32) selected_payee_type
        varchar(32) selected_bank_code
        varchar(160) selected_bank_name
        varchar(64) selected_account_number
        numeric(18,2) amount
        varchar(3) currency
        date payment_date
        timestamptz user_confirmed_at
        varchar(128) downstream_reference
        varchar(64) last_error_code
        text last_error_message
        jsonb context_json
        timestamptz created_at
        timestamptz updated_at
        timestamptz completed_at
    }
```

## 4. Table Design

## 4.1 `ctp_profile`

Stores the predefined demo profiles shown on the landing page.

### Important Columns

| Column | Type | Notes |
|---|---|---|
| `id` | `varchar(64)` | ULID primary key |
| `profile_code` | `varchar(64)` | Internal display code |
| `username` | `varchar(128)` | Downstream identity input |
| `display_name` | `varchar(128)` | UI display name |
| `avatar_url` | `varchar(256)` | Optional avatar |
| `locale` | `varchar(16)` | Example `en-HK` |
| `supported_capabilities_json` | `jsonb` | Example `["DOMESTIC_PAYMENT"]` |
| `status` | `varchar(16)` | `ACTIVE` / `INACTIVE` |

## 4.2 `ctp_chat_session`

Represents one visible conversation thread in the sidebar.

### Important Columns

| Column | Type | Notes |
|---|---|---|
| `id` | `varchar(64)` | ULID primary key |
| `profile_id` | `varchar(64)` | FK to `ctp_profile.id` |
| `title` | `varchar(160)` | Sidebar title |
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
| `session_id` | `varchar(64)` | FK to `ctp_chat_session.id` |
| `sequence_no` | `integer` | Ordered within session |
| `role` | `varchar(16)` | `USER`, `ASSISTANT`, `SYSTEM` |
| `kind` | `varchar(24)` | `TEXT`, `BLOCKS`, `UI_EVENT`, `SYSTEM` |
| `content_text` | `text` | Plain assistant or user text |
| `content_blocks_json` | `jsonb` | Renderable frontend blocks |
| `metadata_json` | `jsonb` | Stream metadata, tool summaries, provider info |
| `created_at` | `timestamptz` | Creation time |

### Notes

- assistant deltas do not need separate rows
- persist the final assembled assistant message
- if needed, store a short tool summary in `metadata_json`

## 4.4 `ctp_payment_draft`

Stores the current or completed domestic payment draft for a session.

### Important Columns

| Column | Type | Notes |
|---|---|---|
| `id` | `varchar(64)` | ULID primary key |
| `session_id` | `varchar(64)` | Unique FK to `ctp_chat_session.id` |
| `payment_type` | `varchar(32)` | V1 value `DOMESTIC_PAYMENT` |
| `status` | `varchar(32)` | `DRAFT`, `AWAITING_CONFIRMATION`, `EXECUTING`, `CONFIRMED`, `FAILED`, `CANCELLED` |
| `payee_query_text` | `varchar(160)` | Raw or normalized user payee text |
| `selected_payee_id` | `varchar(128)` | Opaque downstream payee identifier |
| `selected_payee_name` | `varchar(160)` | User-facing payee name |
| `selected_payee_type` | `varchar(32)` | Downstream payee type |
| `selected_bank_code` | `varchar(32)` | Payee bank code |
| `selected_bank_name` | `varchar(160)` | Payee bank name |
| `selected_account_number` | `varchar(64)` | Display-safe account identifier |
| `amount` | `numeric(18,2)` | Payment amount |
| `currency` | `varchar(3)` | V1 normally `HKD` |
| `payment_date` | `date` | Date only, no time-of-day in V1 |
| `user_confirmed_at` | `timestamptz` | Set when user explicitly confirms |
| `downstream_reference` | `varchar(128)` | Confirm response reference if any |
| `last_error_code` | `varchar(64)` | Downstream or internal error code |
| `last_error_message` | `text` | Last failure message |
| `context_json` | `jsonb` | Extra tool context and future extension fields |
| `created_at` | `timestamptz` | Creation time |
| `updated_at` | `timestamptz` | Last update time |
| `completed_at` | `timestamptz` | Set on terminal success |

### Notes

- payee query alone does not require a draft row
- V2 can extend `context_json` or add targeted columns when international
  payment becomes concrete

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

### `ctp_payment_draft`

- unique index on `session_id`
- index on `status`

### `ctp_payee_alias`

- primary key on `payee_id, alias`
- index on `alias`

## 6. DDL

The executable schema is version-controlled in Flyway migrations under
`chat2pay-app/src/main/resources/db/migration/`.

- `V1__init_schema.sql` creates `ctp_profile`, `ctp_chat_session`,
  `ctp_payment_draft`, `ctp_chat_message`, `ctp_registered_payee`,
  `ctp_payee_alias`, and `ctp_llm_credential`.
- `V2__seed_payees.sql` seeds the POC registered payee directory.
- Flyway itself uses `ctp_flyway_schema_history`, configured through
  `spring.flyway.table`.

Do not maintain a second copy of executable DDL in this document. Schema
changes should be made through a new Flyway migration.

## 7. Seed Data Guidance

For V1:

- seed one or a few `ACTIVE` profiles
- keep `supported_capabilities_json` as `["DOMESTIC_PAYMENT"]`
- set `username` to the downstream identity needed by `LOGIN_URL`
- keep profile display data lightweight

## 8. Operational Notes

- update `ctp_chat_session.updated_at` on every meaningful turn
- increment `message_count` transactionally with message inserts
- persist only the final assistant message, not every stream delta
- store downstream response summaries in `ctp_payment_draft.context_json`
- if provider fallback happens, update `ctp_chat_session.llm_provider`
