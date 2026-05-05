create table if not exists ctp_profile_debit_account (
    id varchar(64) primary key,
    profile_id varchar(64) not null,
    account_number text not null,
    product_category_code varchar(32),
    display_label text,
    currency varchar(3),
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ctp_fk_profile_debit_account_profile
        foreign key (profile_id) references ctp_profile(id)
);

create index if not exists ctp_idx_profile_debit_account_profile_sort
    on ctp_profile_debit_account(profile_id, sort_order, created_at);

alter table ctp_payment_draft
    add column if not exists selected_debit_account_id varchar(64),
    add column if not exists selected_debit_account_number text,
    add column if not exists selected_debit_product_category_code varchar(32),
    add column if not exists selected_debit_display_label text,
    add column if not exists selected_debit_currency varchar(3);

alter table ctp_chat_session
    drop constraint if exists ctp_ck_session_state;

alter table ctp_chat_session
    add constraint ctp_ck_session_state
        check (state in (
            'IDLE', 'COLLECTING_DETAILS', 'AWAITING_PAYEE_SELECTION',
            'AWAITING_DEBIT_ACCOUNT_SELECTION', 'AWAITING_CONFIRMATION',
            'EXECUTING', 'COMPLETED', 'FAILED', 'CANCELLED'
        ));
