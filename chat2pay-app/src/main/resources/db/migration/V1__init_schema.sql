-- chat2pay V1 schema. Every table is prefixed with ctp_ (including Flyway's
-- own history table, configured via spring.flyway.table). Profile rows are
-- inserted manually for the POC; this migration only creates structure.

create table if not exists ctp_profile (
    id varchar(64) primary key,
    profile_code varchar(64) not null unique,
    username varchar(128) not null unique,
    display_name varchar(128) not null,
    avatar_url varchar(256),
    locale varchar(16) not null default 'en-HK',
    supported_capabilities_json jsonb not null default '[]'::jsonb,
    status varchar(16) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ctp_ck_profile_status
        check (status in ('ACTIVE', 'INACTIVE'))
);

create index if not exists ctp_idx_profile_status on ctp_profile(status);

create table if not exists ctp_chat_session (
    id varchar(64) primary key,
    profile_id varchar(64) not null,
    title varchar(160) not null,
    status varchar(24) not null,
    state varchar(48) not null,
    llm_provider varchar(32),
    active_draft_id varchar(64),
    last_message_preview varchar(160),
    message_count integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    archived_at timestamptz,
    constraint ctp_fk_session_profile foreign key (profile_id) references ctp_profile(id),
    constraint ctp_ck_session_status
        check (status in ('ACTIVE', 'COMPLETED', 'FAILED', 'CANCELLED', 'ARCHIVED')),
    constraint ctp_ck_session_state
        check (state in (
            'IDLE', 'COLLECTING_DETAILS', 'AWAITING_PAYEE_SELECTION',
            'AWAITING_CONFIRMATION', 'EXECUTING', 'COMPLETED', 'FAILED', 'CANCELLED'
        ))
);

create index if not exists ctp_idx_session_profile_updated on ctp_chat_session(profile_id, updated_at desc);
create index if not exists ctp_idx_session_state on ctp_chat_session(state);

create table if not exists ctp_payment_draft (
    id varchar(64) primary key,
    session_id varchar(64) not null unique,
    payment_type varchar(32) not null,
    status varchar(32) not null,
    payee_query_text varchar(160),
    selected_payee_id varchar(128),
    selected_payee_name varchar(160),
    selected_payee_type varchar(32),
    selected_bank_code varchar(32),
    selected_bank_name varchar(160),
    selected_account_number varchar(64),
    selected_display_label varchar(160),
    amount numeric(18,2),
    currency varchar(3),
    payment_date date,
    user_confirmed_at timestamptz,
    downstream_reference varchar(128),
    last_error_code varchar(64),
    last_error_message text,
    context_json jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint ctp_fk_draft_session foreign key (session_id) references ctp_chat_session(id),
    constraint ctp_ck_draft_payment_type
        check (payment_type in ('DOMESTIC_PAYMENT', 'INTERNATIONAL_PAYMENT')),
    constraint ctp_ck_draft_status
        check (status in ('DRAFT', 'AWAITING_CONFIRMATION', 'EXECUTING', 'CONFIRMED', 'FAILED', 'CANCELLED'))
);

alter table ctp_chat_session
    add constraint ctp_fk_session_active_draft
    foreign key (active_draft_id) references ctp_payment_draft(id)
    deferrable initially deferred;

create index if not exists ctp_idx_draft_status on ctp_payment_draft(status);

create table if not exists ctp_chat_message (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    sequence_no integer not null,
    role varchar(16) not null,
    kind varchar(24) not null,
    content_text text,
    content_blocks_json jsonb,
    metadata_json jsonb,
    created_at timestamptz not null default now(),
    constraint ctp_fk_message_session foreign key (session_id) references ctp_chat_session(id),
    constraint ctp_uq_message_session_seq unique (session_id, sequence_no),
    constraint ctp_ck_message_role check (role in ('USER', 'ASSISTANT', 'SYSTEM')),
    constraint ctp_ck_message_kind check (kind in ('TEXT', 'BLOCKS', 'UI_EVENT', 'SYSTEM'))
);

create index if not exists ctp_idx_message_session_created on ctp_chat_message(session_id, created_at);

-- Registered payee directory + alias lookup table for backend validation/fallback.
create table if not exists ctp_registered_payee (
    id varchar(64) primary key,
    name varchar(160) not null,
    payee_type varchar(32) not null,
    bank_code varchar(32) not null,
    bank_name varchar(160) not null,
    account_number varchar(64) not null,
    display_label varchar(160) not null,
    created_at timestamptz not null default now()
);

create table if not exists ctp_payee_alias (
    payee_id varchar(64) not null,
    alias varchar(160) not null,
    primary key (payee_id, alias),
    constraint ctp_fk_alias_payee foreign key (payee_id) references ctp_registered_payee(id) on delete cascade
);

create index if not exists ctp_idx_payee_alias_alias on ctp_payee_alias(alias);

-- LLM credentials. The api_key column holds the GitHub OAuth/PAT used for the
-- Copilot session-token exchange; session_token caches the short-lived bearer
-- (~30 min). Operators rotate api_key via UPDATE — the provider re-reads from
-- this table on every token refresh, so no redeploy is needed.
create table if not exists ctp_llm_credential (
    provider varchar(32) primary key,
    api_key text,
    session_token text,
    session_token_expires_at timestamptz,
    metadata_json jsonb,
    updated_at timestamptz not null default now(),
    constraint ctp_ck_credential_provider check (provider in ('COPILOT_PERSONAL', 'REMOTE_API'))
);
