package com.chat2pay.app.domain;

import com.chat2pay.app.integration.downstream.RegisteredPayee;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class PaymentDraft {

    private final String id;
    private final String sessionId;
    private final JourneyType journeyType;
    private final Map<String, RegisteredPayee> candidatePayees = new LinkedHashMap<>();
    private TransferStatus status;
    private WorkflowState workflowState;
    private String sourceAccountId;
    private String sourceAccountDisplay;
    private String payeeNameInput;
    private String payeeIdIndex;
    private String payeeType;
    private String payeeDisplay;
    private BigDecimal amount;
    private String currency;
    private String note;
    private Map<String, Object> reviewSummary = new LinkedHashMap<>();
    private Map<String, Object> downstreamReferences = new LinkedHashMap<>();
    private String transferReference;
    private Map<String, Object> additionalContext = new LinkedHashMap<>();
    private Instant lastUpdatedAt;

    public PaymentDraft(
            String id,
            String sessionId,
            JourneyType journeyType,
            String sourceAccountId,
            String sourceAccountDisplay,
            Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.journeyType = journeyType;
        this.sourceAccountId = sourceAccountId;
        this.sourceAccountDisplay = sourceAccountDisplay;
        this.status = TransferStatus.DRAFT;
        this.workflowState = WorkflowState.COLLECTING_PAYMENT_DETAILS;
        this.lastUpdatedAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public JourneyType getJourneyType() {
        return journeyType;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public void setStatus(TransferStatus status) {
        this.status = status;
    }

    public WorkflowState getWorkflowState() {
        return workflowState;
    }

    public void setWorkflowState(WorkflowState workflowState) {
        this.workflowState = workflowState;
    }

    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public String getSourceAccountDisplay() {
        return sourceAccountDisplay;
    }

    public String getPayeeNameInput() {
        return payeeNameInput;
    }

    public void setPayeeNameInput(String payeeNameInput) {
        this.payeeNameInput = payeeNameInput;
    }

    public String getPayeeIdIndex() {
        return payeeIdIndex;
    }

    public void setPayeeIdIndex(String payeeIdIndex) {
        this.payeeIdIndex = payeeIdIndex;
    }

    public String getPayeeType() {
        return payeeType;
    }

    public void setPayeeType(String payeeType) {
        this.payeeType = payeeType;
    }

    public String getPayeeDisplay() {
        return payeeDisplay;
    }

    public void setPayeeDisplay(String payeeDisplay) {
        this.payeeDisplay = payeeDisplay;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Map<String, Object> getReviewSummary() {
        return reviewSummary;
    }

    public void setReviewSummary(Map<String, Object> reviewSummary) {
        this.reviewSummary = reviewSummary;
    }

    public Map<String, Object> getDownstreamReferences() {
        return downstreamReferences;
    }

    public void setDownstreamReferences(Map<String, Object> downstreamReferences) {
        this.downstreamReferences = downstreamReferences;
    }

    public String getTransferReference() {
        return transferReference;
    }

    public void setTransferReference(String transferReference) {
        this.transferReference = transferReference;
    }

    public Map<String, Object> getAdditionalContext() {
        return additionalContext;
    }

    public void setAdditionalContext(Map<String, Object> additionalContext) {
        this.additionalContext = additionalContext;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public Map<String, RegisteredPayee> getCandidatePayees() {
        return candidatePayees;
    }

    public void replaceCandidatePayees(Iterable<RegisteredPayee> payees) {
        candidatePayees.clear();
        for (RegisteredPayee payee : payees) {
            candidatePayees.put(payee.payeeIdIndex(), payee);
        }
    }

    public void clearCandidatePayees() {
        candidatePayees.clear();
    }

    public void touch(Instant instant) {
        this.lastUpdatedAt = instant;
    }
}
