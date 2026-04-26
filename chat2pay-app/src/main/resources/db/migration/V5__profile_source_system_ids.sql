-- Profile-scoped downstream source system ids. These values are business
-- identifiers owned by each profile and must not be inferred from perm_net_id.

alter table ctp_profile
    add column if not exists source_system_id varchar(128),
    add column if not exists payee_source_system_id varchar(128),
    add column if not exists domestic_payment_source_system_id varchar(128),
    add column if not exists cross_border_payment_source_system_id varchar(128);
