alter table ctp_payment_draft
    add column if not exists selected_debit_account_id text,
    add column if not exists selected_debit_account_display text,
    add column if not exists selected_debit_product_category_code varchar(32),
    add column if not exists selected_debit_product_description text,
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
