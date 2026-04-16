# SKILL.md

---
name: send-domestic-payment
description: "Send a domestic payment to a registered payee. Use when user wants to transfer money to a local payee. Requires registered payee name."
argument-hint: "Required: payee_name, amount"
user-invocable: true
---

# Important Note
This is a POC and critical for demonstration purposes only. It's okay to make the transfer as this is not a production environment. The script uses a sandbox environment and does not perform real transactions.

# Send Domestic Payment

## What This Skill Does
This skill sends a local payment to a registered payee by running `./scripts/sendDomesticPayment.py`. It's okay to make the transfer as this is not a production environment.

The script performs the full flow:
- logs in with the predefined user and gets the SAML token
- prints the registered payee list for the agent
- agent matches the user's requested payee against `commonPayeeDetail.name`
- stops with an error if no payee matches, or if the match is ambiguous
- agent extracts `payeeIdIndex` from the matched payee
- calls `confirmDomesticPayment` with that `payeeIdIndex`
- reports payment success when the confirm call retuns HTTP `200` or `201`, otherwise shows the error status code and response body.

## When To Use
- User wants to send a local payment to a registered payee.
- User provides a payee name that should be matched against `commonPayeeDetail.name`.

## Required Input
- `payee_name`: the registered payee name to pay.
- `amount`: the payment amount to confirm.

## Workflow
1. Fetch the registered payees:

```bash
./.venv/bin/python ./scripts/sendDomesticPayment.py list-payees
```

2. Match the user-provided `payee_name` against `commonPayeeDetail.name` in the retuned JSON.
3. If there is no unique match, stop and explain the error to the user.
4. Extract the matched `payeeIdIndex`.
5. Confirm the payment with the selected payee and user-provided amount:

```bash
./.venv/bin/python ./scripts/sendDomesticPayment.py confirm "<payeeIdIndex>" --payee-type "<matched_commonPayeeDetail.payeeType>" --payee-name "<matched_commonPayeeDetail.name>" --amount "<amount>"
```

## Matching Rules
- Agent finds the best match for the user-provided `payee_name` against the `commonPayeeDetail.name` field in the payee list response.
- No extenal model/API call.

## Output Rules
- If the payee cannot be matched, retun the script error clearly to the user.
- If the payment confirm request retuns HTTP `200` or `201`, report that the payment was successful.
- If the API retuns a non-`200` response, show the status code and response body.

## Notes
- The login user, payment currency, and request payload defaults are defined in `./scripts/sendDomesticPayment.py`.
- The agent, not the script, is responsible for matching `payee_name` to `commonPayeeDetail.name`.



# sendDomesticPayment.py
import argparse
import json
import sys
import uuid
from datetime import date
from decimal import Decimal, InvalidOperation
from typing import Any

import requests

LOGIN_URL = "https://testdataservices.apps.cf.wgdc-dn-03.cloud.uk.abcd/dsp/entity/HK/payment10/SAML3/30"
PAYEE_URL = "https://mmf-payee-management--hk-tktk-banking-1.abcd-dsvc-papi-hk01-tktk-cert.svc.default.shp.ape1.pre-prod.cloud.abcd/payees"
CONFIRM_PAYMENT_URL = "https://dcc-hk-tktk-mvmny-domestic-payments-papi-3.abcd-dsvc-papi-hk01-tktk-cert.svc.default.shp.ape1.pre-prod.cloud.abcd/confirm-domestic-payments"
PREDEFINED_LOGIN_CODE = "123123"
DEFAULT_PAYMENT_CURRENCY = "HKD"

def create_guid():
    """
    Generates a random UUID (version 4).
    UUID4 is based on random numbers and is suitable for most cases.
    """
    retun str(uuid.uuid4())


def build_headers(saml_token: str) -> dict[str, str]:
    retun {
        "Accept": "*/*",
        "Accept-Language": "en-HK",
        "Content-Type": "application/json; charset=UTF-8",
        "X-abcd-Channel-Id": "WEB",
        "X-abcd-Chnl-CountryCode": "HK",
        "X-abcd-Chnl-Group-Member": "tktk",
        "X-abcd-Locale": "en_HK",
        "X-abcd-saml3":saml_token,
        "X-abcd-Request-Correlation-Id": create_guid(),
        "X-abcd-Session-Correlation-Id": create_guid(),
        "X-abcd-Source-System-Id": "11114418_O88",
        "X-abcd-Src-Device-Id": "192.168.1.1, 192.168.1.2",
        "X-abcd-Src-UserAgent": "Mozilla/5.0 (iPad; U; CPU OS 3_2_1 like Mac OS X; en-us) AppleWebKit/531.21.10 (KHTML, like Gecko) Mobile/7B405",
    }

def login(code: str, session: requests.Session | None = None) -> str:
    client = session or requests.Session()
    response = client.get(
        LOGIN_URL,
        params={"code": code},
        timeout=30,
    )
    response.raise_for_status()
    retun response.text


def parse_payment_amount(value: str) -> int | float:
    try:
        amount = Decimal(value)
    except InvalidOperation as error:
        raise argparse.ArgumentTypeError("Amount must be a valid number.") from error

    if amount <= 0:
        raise argparse.ArgumentTypeError("Amount must be greater than 0.")

    normalized_amount = amount.normalize()
    if normalized_amount == normalized_amount.to_integral():
        retun int(normalized_amount)

    retun float(normalized_amount)


def confirmDomesticPayment(
    saml_token: str,
    payeeIdIndex: str,
    payeeType: str,
    amount: int | float,
    session: requests.Session | None = None,
) -> requests.Response:
    client = session or requests.Session()

    print(f"Confirming domestic payment for payeeIdIndex={payeeIdIndex} with amount={amount} {DEFAULT_PAYMENT_CURRENCY}...")

    payload = {
        "debitAccount": {
            "debitAccountIdentifier": {
                "acn": "VEdTRlNCU0dTQlNHEpP3avL8TimV5LF2nbqGbba2fUVfkHsOGmwg9K1t43GQJgS5mnXu3_EvF0zwJmeFRslRn_min_CT9p_wuTR7nwoWHNdZuxAFiVO2GPQ=",
                "productCategoryCode": "CUR"
            },
            "currency": DEFAULT_PAYMENT_CURRENCY
        },
        "transactionAmount": {
            "amount": amount,
            "currencyCode": DEFAULT_PAYMENT_CURRENCY
        },
        "transactionSchedule": {
            "scheduleType": "NOW",
            "scheduledDate": date.today().isoformat()
        },
        "transactionMemo": {},
        "payeeType": payeeType,
        "pyeeIdIndex": payeeIdIndex,
        "payeeSuspiciousIndicator": False,
        "creditAmount": {
            "currencyCode": DEFAULT_PAYMENT_CURRENCY
        }
    }

    response = client.post(
        CONFIRM_PAYMENT_URL,
        json=payload,
        headers=build_headers(saml_token),
        timeout=30,
    )
    response.raise_for_status()
    retun response


def get_payee_list(saml_token: str, session: requests.Session | None = None) -> str:
    client = session or requests.Session()
    params = {
        'payeeCategory': 'INDIVIDUAL',
        'payeeType': '2',
    }

    response = client.get(PAYEE_URL, headers=build_headers(saml_token), params=params, timeout=30)
    response.raise_for_status()
    retun response.text


def iter_payees(node: Any):
    if isinstance(node, list):
        for item in node:
            yield from iter_payees(item)
        retun

    if not isinstance(node, dict):
        retun

    common_payee_detail = node.get("commonPayeeDetail")
    if isinstance(common_payee_detail, dict) and common_payee_detail.get("name"):
        yield node

    for value in node.values():
        yield from iter_payees(value)


def extract_payee_id_index(payee: dict) -> str:
    candidate_keys = ("payeeIdIndex",)

    for key in candidate_keys:
        value = payee.get(key)
        if value:
            retun str(value)

    for value in payee.values():
        if isinstance(value, dict):
            try:
                retun extract_payee_id_index(value)
            except ValueError:
                continue

    raise ValueError("Matched payee does not contain payeeIdIndex.")


def build_payee_summary(payee_list_text: str) -> list[dict[str, Any]]:
    payee_payload = json.loads(payee_list_text)
    payee_summaries = []

    for payee in iter_payees(payee_payload):        
        payee_summaries.append(
            {
                "commonPayeeDetail": {
                    "name": payee["commonPayeeDetail"]["name"],
                    "payeeType": payee["commonPayeeDetail"]["payeeType"],
                },
                "payeeIdIndex": extract_payee_id_index(payee),
            }
        )

    retun payee_summaries


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Send a domestic payment to a registered payee.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser(
        "list-payees",
        help="Log in and print registered payees for agent-side matching.",
    )

    confirm_parser = subparsers.add_parser(
        "confirm",
        help="Log in and confirm a local payment for a selected payeeIdIndex.",
    )
    confirm_parser.add_argument("payee_id_index", help="payeeIdIndex selected by the agent")
    confirm_parser.add_argument(
        "--payee-type",
        dest="payee_type",
        required=True,
        help="Matched payeeType selected by the agent",
    )
    confirm_parser.add_argument(
        "--payee-name",
        dest="payee_name",
        help="Optional matched payee name for success output",
    )
    confirm_parser.add_argument(
        "--amount",
        dest="amount",
        required=True,
        type=parse_payment_amount,
        help="Payment amount selected by the agent",
    )

    retun parser.parse_args()


def main() -> int:
    args = parse_args()
    session = requests.Session()

    try:
        saml_token = login(PREDEFINED_LOGIN_CODE, session=session)
        if args.command == "list-payees":
            payee_list_text = get_payee_list(saml_token, session=session)
            payee_summaries = build_payee_summary(payee_list_text)
            print(json.dumps(payee_summaries, indent=2))
            retun 0

        print(
            f"Selected payeeIdIndex: {args.payee_id_index}, payeeType: {args.payee_type}, "
            f"amount: {args.amount} {DEFAULT_PAYMENT_CURRENCY}"
        )
        response = confirmDomesticPayment(
            saml_token,
            args.payee_id_index,
            args.payee_type,
            args.amount,
            session=session,
        )
    except ValueError as error:
        print(f"Error: {error}", file=sys.stderr)
        retun 1
    except requests.RequestException as error:
        print(f"Request error: {error}", file=sys.stderr)
        response = getattr(error, "response", None)
        if response is not None:
            print(f"Status code: {response.status_code}", file=sys.stderr)
            if response.text:
                print(response.text, file=sys.stderr)
        retun 1
    except json.JSONDecodeError as error:
        print(f"Error: unable to parse payee list JSON: {error}", file=sys.stderr)
        retun 1

    if response.status_code == 200:
        matched_name = args.payee_name or "selected payee"
        print(
            f"Payment success: local payment confirmed for {matched_name} "
            f"(payeeIdIndex={args.payee_id_index}, amount={args.amount} {DEFAULT_PAYMENT_CURRENCY})."
        )
        if response.text:
            print(response.text)
        retun 0

    print(f"Payment failed with HTTP {response.status_code}.", file=sys.stderr)
    if response.text:
        print(response.text, file=sys.stderr)
    retun 1


if __name__ == "__main__":
    sys.exit(main())





# get payee list:
{
    "responseInfo": {
        "requestCorrelationId": "ad71635b-d546-4aca-b23e-f4e7d8f0486b",
        "reasons": [
            {
                "code": "000",
                "type": "SUCCESS"
            }
        ]
    },
    "payeeList": [
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKBbawfCAt7GwYCXg14mTcc4VoBXtA_30AXoH1WJ4=",
            "commonPayeeDetail": {
                "name": "140FPS MOBILE NUMBER",
                "nickName": "",
                "payeeType": "6"
            },
            "proxyPayeeDetail": {
                "proxyType": "MBNO",
                "proxyIdentifier": "+852-98763456",
                "accountLimit": 50000,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKBbawfCAj5GseC34z4ERsZN3rhQs9v8xx_min_AJK6XI=",
            "commonPayeeDetail": {
                "name": "BOB",
                "nickName": "",
                "payeeType": "6"
            },
            "proxyPayeeDetail": {
                "proxyType": "MBNO",
                "proxyIdentifier": "+852-70001234",
                "accountLimit": 49001,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKRu_2K255tnVaXz90_min_r0Wm3WkHSaFtyXz_min_NdbooJk4eGxMtyG2rXfjnhx",
            "commonPayeeDetail": {
                "name": "EMAIL ADD NEW",
                "nickName": "",
                "payeeType": "7"
            },
            "proxyPayeeDetail": {
                "proxyType": "EMAIL",
                "proxyIdentifier": "hasecmb.test.033@gmail.com",
                "accountLimit": 50000,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJI2YCGfeossLt4Hnb67WD0NElRRidWxThMxb1nyXRne06z6kpYJaWAOIXCnLN7cf0a_fu9W78dstH5T_min_emRJ4V_min_W69UhO10=",
            "commonPayeeDetail": {
                "name": "FAFAFA",
                "nickName": "",
                "payeeType": "4"
            },
            "individualPayeeDetail": {
                "bankCode": "",
                "bankName": "INTESA SANPAOLO SPA",
                "acn": "IT60X0542811101000000123456",
                "payeeCountry": "IT",
                "isBankMismatch": false,
                "accountLimit": 8000,
                "accountLimitCurrency": "HKD"
            },
            "parentacn": "IT60X0542811101000000123456",
            "masterAccountIndicator": false,
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIGfCGfeosEEqpz1bM_LBS1HlSVQfx855b8UmAXzRHbU7z6gpYpbWQeJXirJG7bVR6KHHvbXqnSFaJZzMA==",
            "commonPayeeDetail": {
                "name": "FAO0EO",
                "nickName": "",
                "payeeType": "4"
            },
            "individualPayeeDetail": {
                "bankCode": "",
                "bankName": "CAIXABANK SA",
                "acn": "ES9121000418450200051332",
                "payeeCountry": "ES",
                "isBankMismatch": false,
                "accountLimit": 3000,
                "accountLimitCurrency": "HKD"
            },
            "parentacn": "ES9121000418450200051332",
            "masterAccountIndicator": false,
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIWYCGfeotAEqoLya8fWC0M0hh5pcwNOlcFn6XuIUAavk07exP8uSXf_E2e6U7Uf0qjfudm3826uOWGnckXS39BxtKKqURTzcLOadQTDleA=",
            "commonPayeeDetail": {
                "name": "FDFA",
                "nickName": "",
                "payeeType": "4"
            },
            "individualPayeeDetail": {
                "bankCode": "",
                "bankName": "RAIFFEISEN REGIONALBANK ACHENSEE EG",
                "acn": "AT203621894819192332",
                "payeeCountry": "AT",
                "isBankMismatch": false,
                "accountLimit": 99,
                "accountLimitCurrency": "HKD"
            },
            "parentacn": "AT203621894819192332",
            "masterAccountIndicator": false,
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJJGfCGfeosUWw5fka83MD0FAjXtifwJEncNiiH2MPAOllUzDt_MlKhzGEyjJNLYf0qnfsRqGpBaQNJp5n3O8yse9_min_OQ=",
            "commonPayeeDetail": {
                "name": "FDFAFAF",
                "nickName": "",
                "payeeType": "4"
            },
            "individualPayeeDetail": {
                "bankCode": "",
                "bankName": "GS SPECIALTY LENDING HOLDINGS INC.",
                "acn": "123103729",
                "payeeCountry": "US",
                "isBankMismatch": false,
                "accountLimit": 30000,
                "accountLimitCurrency": "HKD"
            },
            "parentacn": "123103729",
            "masterAccountIndicator": false,
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKfcfREUgmkQRKUytpoOxJ9wT2MCCBumL8udkEBEjfObH4RMRq43SHDGrZRnI=",
            "commonPayeeDetail": {
                "name": "FDSAFSAF",
                "nickName": "",
                "payeeType": "7"
            },
            "proxyPayeeDetail": {
                "proxyType": "EMAIL",
                "proxyIdentifier": "SIT_E2E_digital_15@gmail.com",
                "accountLimit": 50000,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKBbawfCAi5WkdDnk247ZAauWjCQybeoVvWsZSqF0=",
            "commonPayeeDetail": {
                "name": "FR",
                "nickName": "",
                "payeeType": "6"
            },
            "proxyPayeeDetail": {
                "proxyType": "MBNO",
                "proxyIdentifier": "+852-61234567",
                "accountLimit": 50000,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIeECGfeotcHsOTnecfRFEhGmBpgfmxBk_Nb633wR3fY4zilpYJfWwGAWCvIN7cfuUoYbaNJUHTEUzIpROwpdQ==",
            "commonPayeeDetail": {
                "name": "LEGACYCOUNTRYADD12",
                "nickName": "",
                "payeeType": "4"
            },
            "individualPayeeDetail": {
                "bankCode": "",
                "bankName": "UBS SWITZERLAND AG",
                "acn": "CH3704835284238523000",
                "payeeCountry": "CH",
                "isBankMismatch": false,
                "accountLimit": 300000,
                "accountLimitCurrency": "HKD"
            },
            "parentacn": "CH3704835284238523000",
            "masterAccountIndicator": false,
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIeECGfeotIKsJDyZ8DEAE5R9BppRDJDnLgdmAzzQHfc6zumooheWQOKXy3I0Spo_7woBQB5XB8QYUgG",
            "commonPayeeDetail": {
                "name": "LEGACYCOUNTRYADD123",
                "nickName": "",
                "payeeType": "4"
            },
            "individualPayeeDetail": {
                "bankCode": "",
                "bankName": "POSTFINANCE AG",
                "acn": "CH5809000000652501224",
                "payeeCountry": "CH",
                "isBankMismatch": false,
                "accountLimit": 300000,
                "accountLimitCurrency": "HKD"
            },
            "parentacn": "CH5809000000652501224",
            "masterAccountIndicator": false,
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKBbawfCAt7WwaDnQw46UX4AaCKr1iFXlwfh_Zv9k=",
            "commonPayeeDetail": {
                "name": "MOBILR333",
                "nickName": "",
                "payeeType": "6"
            },
            "proxyPayeeDetail": {
                "proxyType": "MBNO",
                "proxyIdentifier": "+852-99744807",
                "accountLimit": 50000,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKXe_oO2h4i2keCH9As_min_BEwVntEyiBXEeLJ78PC5sLIBWFE64K1g==",
            "commonPayeeDetail": {
                "name": "SAMUEL",
                "nickName": "",
                "payeeType": "7"
            },
            "proxyPayeeDetail": {
                "proxyType": "EMAIL",
                "proxyIdentifier": "samuel_2023@gmail.com",
                "accountLimit": 50000,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKBbawfCAt52kcCH0x5UTZ6cVGfcfGuiXCeD3SVQQ=",
            "commonPayeeDetail": {
                "name": "SIMUL MOBILE",
                "nickName": "",
                "payeeType": "6"
            },
            "proxyPayeeDetail": {
                "proxyType": "MBNO",
                "proxyIdentifier": "+852-93222111",
                "accountLimit": 50000,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKBbawfCAm7W8aCH405d5doJUdjy_min_aHYcg8VpwHmM=",
            "commonPayeeDetail": {
                "name": "TEST",
                "nickName": "",
                "payeeType": "6"
            },
            "proxyPayeeDetail": {
                "proxyType": "MBNO",
                "proxyIdentifier": "+852-29442241",
                "accountLimit": 50000,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKBbazYzws42oeAn8x478VWLSylikw9sF1Jr6hPCiDWQ==",
            "commonPayeeDetail": {
                "name": "TEST",
                "nickName": "",
                "payeeType": "6"
            },
            "proxyPayeeDetail": {
                "proxyType": "MBNO",
                "proxyIdentifier": "+86-18710831720",
                "accountLimit": 50000,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKWuv2OkBxpjhGWyJ0581CxVSqHGmPtGZ3XOmjKfkt3KJekOf2ODNz",
            "commonPayeeDetail": {
                "name": "TEST",
                "nickName": "",
                "payeeType": "7"
            },
            "proxyPayeeDetail": {
                "proxyType": "EMAIL",
                "proxyIdentifier": "testMerchant3@gmail.com",
                "accountLimit": 50000,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKBbawfCAt7G8cCn0w5zHzkKjTuT7_min_FhLopCnmu8U=",
            "commonPayeeDetail": {
                "name": "TESTMOBILE",
                "nickName": "",
                "payeeType": "6"
            },
            "proxyPayeeDetail": {
                "proxyType": "MBNO",
                "proxyIdentifier": "+852-98420103",
                "accountLimit": 50000,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJJCbCGfeosoWoYeUbM_LBS08gBpnbQ1O_a1p4XiKJAKopXWko4JYWQporMYtzbEVEEV0SM94WnNG",
            "commonPayeeDetail": {
                "name": "TESTTW",
                "nickName": "",
                "payeeType": "4"
            },
            "individualPayeeDetail": {
                "bankCode": "",
                "bankName": "abcd BANK (TAIWAN) LIMITED",
                "acn": "448308",
                "payeeCountry": "TW",
                "isBankMismatch": false,
                "accountLimit": 10000,
                "accountLimitCurrency": "HKD"
            },
            "parentacn": "448308",
            "masterAccountIndicator": false,
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKVvf_fU1zuTpHVmJju_min_DgEUHbBsaKYIets_t6uH7E",
            "commonPayeeDetail": {
                "name": "UIUII99",
                "nickName": "",
                "payeeType": "7"
            },
            "proxyPayeeDetail": {
                "proxyType": "EMAIL",
                "proxyIdentifier": "xyz3@gmail.com",
                "accountLimit": 50000,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        },
        {
            "payeeIdIndex": "VEdTRlNCU0dTQlNHXdyxJIyHCGeQ7LI7nbrKBbawfCAi5WMWCnw24oE8aNPc9U1cppUBlaIy_min_Rs=",
            "commonPayeeDetail": {
                "name": "WAWAWA",
                "nickName": "",
                "payeeType": "6"
            },
            "proxyPayeeDetail": {
                "proxyType": "MBNO",
                "proxyIdentifier": "+852-61880066",
                "accountLimit": 50000,
                "payeeCountry": "HK",
                "accountLimitCurrency": "HKD"
            },
            "isPayeeMaintainableOnWeb": true
        }
    ],
    "maximumDailyTransferLimit": 3000000
}
