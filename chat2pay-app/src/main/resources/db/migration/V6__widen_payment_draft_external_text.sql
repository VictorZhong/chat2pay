-- Downstream payee identifiers and confirmation references are opaque strings.
-- They must not be truncated because selected_payee_id is sent back to the
-- payment confirmation API.
alter table ctp_payment_draft
    alter column payee_query_text type text,
    alter column selected_payee_id type text,
    alter column selected_payee_name type text,
    alter column selected_payee_type type text,
    alter column selected_bank_code type text,
    alter column selected_bank_name type text,
    alter column selected_account_number type text,
    alter column selected_display_label type text,
    alter column downstream_reference type text;
