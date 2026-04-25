-- Profile-scoped credentials and payment runtime configuration.

alter table ctp_profile
    add column if not exists guid varchar(64),
    add column if not exists perm_net_id varchar(128),
    add column if not exists password text,
    add column if not exists debit_account_number text,
    add column if not exists debit_product_category_code varchar(16),
    add column if not exists payment_currency varchar(3);

create unique index if not exists ctp_uq_profile_guid
    on ctp_profile(guid)
    where guid is not null;

create unique index if not exists ctp_uq_profile_perm_net_id
    on ctp_profile(perm_net_id)
    where perm_net_id is not null;

-- Sessions already carry profile_id. Add it to messages and drafts as a direct
-- partition key so later queries do not need to infer profile ownership through
-- the session join.
alter table ctp_chat_message
    add column if not exists profile_id varchar(64);

update ctp_chat_message m
set profile_id = s.profile_id
from ctp_chat_session s
where m.session_id = s.id
  and m.profile_id is null;

alter table ctp_chat_message
    alter column profile_id set not null;

alter table ctp_chat_message
    add constraint ctp_fk_message_profile
    foreign key (profile_id) references ctp_profile(id);

create index if not exists ctp_idx_message_profile_session_created
    on ctp_chat_message(profile_id, session_id, created_at);

alter table ctp_payment_draft
    add column if not exists profile_id varchar(64);

update ctp_payment_draft d
set profile_id = s.profile_id
from ctp_chat_session s
where d.session_id = s.id
  and d.profile_id is null;

alter table ctp_payment_draft
    alter column profile_id set not null;

alter table ctp_payment_draft
    add constraint ctp_fk_draft_profile
    foreign key (profile_id) references ctp_profile(id);

create index if not exists ctp_idx_draft_profile_status
    on ctp_payment_draft(profile_id, status);

-- Seeded/mock payees are now profile-scoped. Existing V2 seed rows are assigned
-- to the documented POC profile id; operators can update this value or insert
-- per-profile payees as needed.
alter table ctp_registered_payee
    add column if not exists profile_id varchar(64) not null default 'profile_poc';

create index if not exists ctp_idx_payee_profile_name
    on ctp_registered_payee(profile_id, name, account_number);

alter table ctp_payee_alias
    add column if not exists profile_id varchar(64) not null default 'profile_poc';

create index if not exists ctp_idx_payee_alias_profile_alias
    on ctp_payee_alias(profile_id, alias);
