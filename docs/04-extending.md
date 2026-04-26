# chat2pay - Extending the POC

This document captures the common extension paths that should stay small and
reviewable as V1 grows.

## Add a New Payment Tool

1. Add or update the semantic metadata in `CapabilityRegistry`.
2. Add a `PaymentTool` implementation and let `PaymentToolRegistry` pick it up
   as a Spring bean. Keep side effects behind backend validation; never trust
   tool arguments directly for payment execution.
3. Add aliases only for backward compatibility with prior model-facing tool
   names; keep the canonical tool name aligned with the semantic capability.
4. Extend `IntentInterpreter.fromToolCall` if the tool can also be selected by
   the standalone intent interpreter.
5. Add or update unit tests for capability metadata, interpreter behavior, and
   the journey/capability service.
6. Update `docs/02-api_contract.yaml` only when the frontend contract changes.

Tool handlers should return structured blocks through `ChatBlockFactory` and
should leave the controller contract unchanged. V2 payment rails should arrive
as new tool definitions plus focused handlers, not as frontend workflow
branches.

Domestic and cross-border payment should stay separate at the capability level.
V1 domestic payment directly calls the domestic confirmation API after backend
validation and explicit user confirmation. V2 cross-border payment should be
modeled as ORTT-only for the POC, with a `proposeCrossBorderPayment` capability
that persists a backend-owned proposal id/execution token before
`confirmCrossBorderPayment` can run. Do not fold GD or other rails into the POC
journey.

Payee lookup should use the live downstream payee API in real mode. Local
`ctp_registered_payee` / `ctp_payee_alias` rows are mock fixtures only; payee
creation or update flows should call downstream APIs directly rather than
writing chat2pay tables.

## Add a New Test Profile

1. Insert a row into `ctp_profile` with status `ACTIVE`.
2. Configure profile capabilities in `supported_capabilities_json`, usually
   `["REGISTERED_PAYEE_LOOKUP","DOMESTIC_PAYMENT"]` for V1.
3. Set `payment_currency`, `debit_account_number`, and
   `debit_product_category_code` when real downstream mode is needed.
4. Set `source_system_id` or endpoint-specific overrides such as
   `payee_source_system_id` and `domestic_payment_source_system_id` per profile;
   this value is not derived from `perm_net_id`.
5. For mock mode only, seed profile-scoped registered payee fixtures in
   `V2__seed_payees.sql` or add a follow-up migration. Do not use these tables
   as production payee storage.
6. For real downstream testing, set `PAYMENT_MOCK_ENABLED=false` and configure
   `PAYMENT_LOGIN_URL`, `PAYMENT_PAYEE_URL`, and `PAYMENT_CONFIRM_URL`.

The profile selector uses the fixed POC access password `tb123`. The
`ctp_profile.password` value is only the downstream test-data-service password
used by the backend when it acquires a SAML token.
