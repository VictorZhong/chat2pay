-- Seed registered payees for the POC. Profiles are inserted manually by the
-- operator (per docs/03-db_design.md §7).

insert into ctp_registered_payee (id, name, payee_type, bank_code, bank_name, account_number, display_label) values
    ('payee_bob_current',     'Bob Chan',     'DOMESTIC_REGISTERED', '004', 'HSBC Hong Kong',           '123-456-789', 'CURRENT • 123-456-789'),
    ('payee_bob_savings',     'Bob Chan',     'DOMESTIC_REGISTERED', '004', 'HSBC Hong Kong',           '987-654-321', 'SAVINGS • 987-654-321'),
    ('payee_sarah_salary',    'Sarah Wong',   'DOMESTIC_REGISTERED', '012', 'Bank of China (Hong Kong)','800-221-456', 'PAYROLL • 800-221-456'),
    ('payee_alex_ops',        'Alex Tan',     'DOMESTIC_REGISTERED', '024', 'Hang Seng Bank',           '556-000-912', 'OPERATIONS • 556-000-912'),
    ('payee_michelle_vendor', 'Michelle Ng',  'DOMESTIC_REGISTERED', '005', 'Citibank Hong Kong',       '445-221-007', 'VENDOR • 445-221-007')
on conflict (id) do nothing;

insert into ctp_payee_alias (payee_id, alias) values
    ('payee_bob_current',     'bob chan'),
    ('payee_bob_current',     'bob'),
    ('payee_bob_savings',     'bob chan'),
    ('payee_bob_savings',     'bob'),
    ('payee_sarah_salary',    'sarah wong'),
    ('payee_sarah_salary',    'sarah'),
    ('payee_alex_ops',        'alex tan'),
    ('payee_alex_ops',        'alex'),
    ('payee_michelle_vendor', 'michelle ng'),
    ('payee_michelle_vendor', 'michelle')
on conflict do nothing;
