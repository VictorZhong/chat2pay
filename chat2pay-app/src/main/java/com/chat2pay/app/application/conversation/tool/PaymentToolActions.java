package com.chat2pay.app.application.conversation.tool;

public interface PaymentToolActions {

    PaymentToolExecution executeListDebitAccountsTool(PaymentToolContext context);

    PaymentToolExecution executeRegisteredPayeesTool(PaymentToolContext context);

    PaymentToolExecution executePrepareDomesticPaymentTool(PaymentToolContext context);

    PaymentToolExecution executeConfirmDomesticPaymentTool(PaymentToolContext context);

    PaymentToolExecution executeCancelPaymentTool(PaymentToolContext context);

    PaymentToolExecution executeUnsupportedCrossBorderPaymentTool(PaymentToolContext context);

    PaymentToolExecution executeUnknownPaymentTool(PaymentToolContext context);
}
