insert into ctp_profile (
    id,
    profile_code,
    username,
    display_name,
    avatar_url,
    mock_customer_id,
    locale,
    supported_journey_types_json,
    status,
    created_at,
    updated_at
) values (
    '01J0CHAT2PAYPROFILE000000001',
    'HK_STAFF_001',
    'payment10',
    'Payment 10',
    null,
    'CUST0001',
    'en-HK',
    '["DOMESTIC_EXISTING_PAYEE"]',
    'ACTIVE',
    current_timestamp,
    current_timestamp
);
