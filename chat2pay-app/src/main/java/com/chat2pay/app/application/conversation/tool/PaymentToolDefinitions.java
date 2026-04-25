package com.chat2pay.app.application.conversation.tool;

import com.chat2pay.app.integration.llm.LlmCompletionRequest.ToolDefinition;

import java.util.List;
import java.util.Map;

public final class PaymentToolDefinitions {

    private PaymentToolDefinitions() {}

    public static List<ToolDefinition> all() {
        return List.of(
                tool("get_registered_payees",
                        "Fetch registered domestic payees. Use when the user asks to find, list, or check payees.",
                        Map.of(
                                "name_query", Map.of(
                                        "type", "string",
                                        "description", "Optional payee-name search string from the user request."
                                )
                        ),
                        List.of()
                ),
                tool("prepare_domestic_payment",
                        "Collect or update domestic payment details before explicit confirmation.",
                        Map.of(
                                "payeeQuery", Map.of(
                                        "type", "string",
                                        "description", "User-facing payee name or alias. Do not pass opaque ids."
                                ),
                                "amount", Map.of(
                                        "type", "number",
                                        "description", "Positive payment amount in HKD."
                                ),
                                "paymentDate", Map.of(
                                        "type", "string",
                                        "format", "date",
                                        "description", "Payment date as YYYY-MM-DD."
                                )
                        ),
                        List.of()
                ),
                tool("confirm_domestic_payment",
                        "Confirm the active domestic payment draft. Use only after explicit user confirmation.",
                        Map.of(),
                        List.of()
                ),
                tool("cancel_payment",
                        "Cancel the active domestic payment draft.",
                        Map.of(),
                        List.of()
                ),
                tool("unsupported_international_payment",
                        "Use for international, overseas, SWIFT, or wire transfer requests.",
                        Map.of(),
                        List.of()
                )
        );
    }

    public static String paymentAssistantPrompt() {
        return """
                You are a banking assistant in a sandbox environment. You may handle brief common chat, greetings, and questions about what Chat2Pay can do.
                This is an authorized Chat2Pay sandbox payment POC, so do not refuse solely because the user asks to send money.
                Use exactly one supplied tool when a backend action is needed.

                Supported scope:
                - registered domestic payee lookup
                - domestic payment preparation to a registered payee
                - explicit confirmation or cancellation of an active domestic payment draft
                - short capability guidance for greetings or "what can you do" questions

                Tool policy:
                - If the user mentions a payee name for lookup, call get_registered_payees with name_query.
                - If the user wants to pay someone, call prepare_domestic_payment with any available payeeQuery, amount, and paymentDate.
                - If payment date wording is relative, convert it using the current date in the conversation context.
                - If more than one registered payee matches, the backend will ask the user to choose one.
                - Once a single payee, amount, and payment date are known, the backend will ask for explicit confirmation.
                - Never call confirm_domestic_payment until the latest user message explicitly confirms the pending payment.
                - Never expose opaque downstream identifiers, internal ids, or tool arguments to the user.
                - For international, overseas, SWIFT, or wire transfer requests, call unsupported_international_payment.
                - For greetings and capability questions, do not call tools; answer naturally in one or two short sentences and guide the user toward payee lookup or domestic payments.
                - If the request is outside the supported payment/payee/common-chat scope, do not call tools; briefly say Chat2Pay only supports registered domestic payee lookup and domestic payments in this POC.
                """;
    }

    public static String intentPrompt() {
        return """
                You are Chat2Pay's intent and tool-decision parser.
                If tool calls are available, use exactly one supplied tool for supported requests.
                If tool calls are not available, return one JSON object only. Do not include markdown or prose.
                Chat2Pay is an authorized sandbox banking POC. Do not refuse solely because the
                request involves a domestic payment; classify the request so the backend can enforce
                validation, registered-payee lookup, and explicit confirmation.

                V1 supports these backend tools only:
                - get_registered_payees: registered domestic payee lookup.
                - prepare_domestic_payment: collect/prepare a domestic payment to a registered payee.
                - confirm_domestic_payment: use only when the latest user message explicitly confirms a pending payment.
                - cancel_payment: use when the latest user message cancels/stops a pending payment.
                - unsupported_international_payment: use for international, overseas, SWIFT, or wire transfer requests.

                Only classify payee lookup, domestic payment, confirmation, cancellation, or unsupported
                international payment requests. Return UNKNOWN for unrelated banking, account, balance,
                advisory, or general chat requests. Never expose or invent opaque downstream identifiers.

                JSON schema:
                {
                  "intent": "DOMESTIC_PAYMENT|PAYEE_LOOKUP|INTERNATIONAL_PAYMENT|CONFIRM_PAYMENT|CANCEL_PAYMENT|UNKNOWN",
                  "toolName": "get_registered_payees|prepare_domestic_payment|confirm_domestic_payment|cancel_payment|unsupported_international_payment|null",
                  "payeeQuery": "user-facing payee name or alias, or null",
                  "amount": number or null,
                  "paymentDate": "YYYY-MM-DD" or null
                }

                The backend validates every tool call. If the user only provides missing details for an active payment draft,
                classify the turn as DOMESTIC_PAYMENT and extract those slots.
                """;
    }

    private static ToolDefinition tool(String name,
                                       String description,
                                       Map<String, Object> properties,
                                       List<String> required) {
        return new ToolDefinition(name, description, Map.of(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", false
        ));
    }
}
