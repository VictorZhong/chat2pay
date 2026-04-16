package com.chat2pay.app.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ConversationSession {

    private final String id;
    private final String profileId;
    private final Instant createdAt;
    private final List<ConversationMessage> messages = new ArrayList<>();
    private String title;
    private ChatSessionStatus status;
    private WorkflowState workflowState;
    private JourneyType journeyType;
    private PaymentDraft activeDraft;
    private Instant updatedAt;

    public ConversationSession(String id, String profileId, String title, Instant createdAt) {
        this.id = id;
        this.profileId = profileId;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = ChatSessionStatus.ACTIVE;
        this.workflowState = WorkflowState.IDLE;
    }

    public String getId() {
        return id;
    }

    public String getProfileId() {
        return profileId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public ChatSessionStatus getStatus() {
        return status;
    }

    public void setStatus(ChatSessionStatus status) {
        this.status = status;
    }

    public WorkflowState getWorkflowState() {
        return workflowState;
    }

    public void setWorkflowState(WorkflowState workflowState) {
        this.workflowState = workflowState;
    }

    public JourneyType getJourneyType() {
        return journeyType;
    }

    public void setJourneyType(JourneyType journeyType) {
        this.journeyType = journeyType;
    }

    public PaymentDraft getActiveDraft() {
        return activeDraft;
    }

    public void setActiveDraft(PaymentDraft activeDraft) {
        this.activeDraft = activeDraft;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<ConversationMessage> getMessages() {
        return List.copyOf(messages);
    }

    public void addMessage(ConversationMessage message) {
        messages.add(message);
        touch(message.createdAt());
    }

    public int nextSequenceNo() {
        return messages.size() + 1;
    }

    public void touch(Instant instant) {
        this.updatedAt = instant;
        if (activeDraft != null) {
            activeDraft.touch(instant);
        }
    }

    public String lastAssistantText() {
        return messages.stream()
                .filter(message -> message.role() == MessageRole.ASSISTANT)
                .max(Comparator.comparing(ConversationMessage::createdAt))
                .map(message -> {
                    if (message.text() != null && !message.text().isBlank()) {
                        return message.text();
                    }
                    if (message.contentBlocks() == null || message.contentBlocks().isEmpty()) {
                        return null;
                    }
                    return message.contentBlocks().getFirst().displayText();
                })
                .orElse(null);
    }

    public boolean isTerminal() {
        return status == ChatSessionStatus.COMPLETED || status == ChatSessionStatus.CANCELLED;
    }
}
