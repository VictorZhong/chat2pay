package com.chat2pay.app.integration.downstream.crossborder;

import java.util.Map;

/**
 * Reserved client boundary for V2 cross-border ORTT payment support.
 */
public interface CrossBorderPaymentClient {

    CrossBorderProposal propose(CrossBorderProposalRequest request);

    CrossBorderConfirmationResult confirm(CrossBorderConfirmRequest request);

    record CrossBorderProposalRequest(
            String profileId,
            String rail,
            Map<String, Object> payload
    ) {}

    record CrossBorderProposal(
            String proposalId,
            String rail,
            Map<String, Object> response
    ) {}

    record CrossBorderConfirmRequest(
            String profileId,
            String proposalId,
            String idempotencyKey
    ) {}

    record CrossBorderConfirmationResult(
            boolean ok,
            String reference,
            String status,
            Map<String, Object> response,
            String message
    ) {}
}
