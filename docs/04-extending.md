# chat2pay - Extending the POC

This document captures the common extension paths that should stay small and
reviewable as V1 grows.

## Add a New Payment Tool

1. Add the tool schema in `PaymentToolDefinitions.all()`.
2. Add a `PaymentTool` implementation and let `PaymentToolRegistry` pick it up
   as a Spring bean. Keep side effects behind backend validation; never trust
   tool arguments directly for payment execution.
3. Extend `IntentInterpreter.fromToolCall` if the tool can also be selected by
   the standalone intent interpreter.
4. Add or update unit tests for the interpreter and orchestrator behavior.
5. Update `docs/02-api_contract.yaml` only when the frontend contract changes.

Tool handlers should return structured blocks through `ChatBlockFactory` and
should leave the controller contract unchanged. V2 payment rails should arrive
as new tool definitions plus focused handlers, not as frontend workflow
branches.

## Add a New Test Profile

1. Insert a row into `ctp_profile` with status `ACTIVE`.
2. Configure profile capabilities in `supported_capabilities_json`, usually
   `["REGISTERED_PAYEE_LOOKUP","DOMESTIC_PAYMENT"]` for V1.
3. Set `payment_currency`, `debit_account_number`, and
   `debit_product_category_code` when real downstream mode is needed.
4. For mock mode, seed profile-scoped registered payees in
   `V2__seed_payees.sql` or add a follow-up migration.
5. For real downstream testing, set `PAYMENT_MOCK_ENABLED=false` and configure
   `PAYMENT_LOGIN_URL`, `PAYMENT_PAYEE_URL`, and `PAYMENT_CONFIRM_URL`.

The profile selector uses the fixed POC access password `tb123`. The
`ctp_profile.password` value is only the downstream test-data-service password
used by the backend when it acquires a SAML token.
