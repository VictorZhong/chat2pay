package com.chat2pay.app.application.chat;

import com.chat2pay.app.api.ApiModels;
import com.chat2pay.app.application.journey.DomesticExistingPayeeJourney;
import com.chat2pay.app.application.profile.ProfileService;
import com.chat2pay.app.common.ApiException;
import com.chat2pay.app.domain.ConversationMessage;
import com.chat2pay.app.domain.ConversationSession;
import com.chat2pay.app.domain.Profile;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ChatApplicationService {

    private final ProfileService profileService;
    private final ConversationSessionRepository conversationSessionRepository;
    private final ApiMapper apiMapper;
    private final BlockFactory blockFactory;
    private final ConversationMessageFactory conversationMessageFactory;
    private final DomesticExistingPayeeJourney domesticExistingPayeeJourney;
    private final com.chat2pay.app.common.UlidFactory ulidFactory;

    public ChatApplicationService(
            ProfileService profileService,
            ConversationSessionRepository conversationSessionRepository,
            ApiMapper apiMapper,
            BlockFactory blockFactory,
            ConversationMessageFactory conversationMessageFactory,
            DomesticExistingPayeeJourney domesticExistingPayeeJourney,
            com.chat2pay.app.common.UlidFactory ulidFactory) {
        this.profileService = profileService;
        this.conversationSessionRepository = conversationSessionRepository;
        this.apiMapper = apiMapper;
        this.blockFactory = blockFactory;
        this.conversationMessageFactory = conversationMessageFactory;
        this.domesticExistingPayeeJourney = domesticExistingPayeeJourney;
        this.ulidFactory = ulidFactory;
    }

    public synchronized ApiModels.ChatSessionSummaryPageResponse listSessions(String profileId) {
        profileService.requireProfile(profileId);
        return apiMapper.toSessionSummaryPage(conversationSessionRepository.findAllByProfileId(profileId));
    }

    public synchronized ApiModels.ChatSessionCreateResponse createSession(
            String profileId,
            ApiModels.CreateChatSessionRequest request) {
        Profile profile = profileService.requireProfile(profileId);
        ConversationSession session = new ConversationSession(
                ulidFactory.nextUlid(),
                profileId,
                request != null && request.title() != null && !request.title().isBlank()
                        ? request.title().trim()
                        : "Domestic payment",
                java.time.Instant.now());

        ConversationMessage welcomeMessage = conversationMessageFactory.assistantMessage(
                session.getId(),
                session.nextSequenceNo(),
                null,
                List.of(
                        blockFactory.text("Hi " + firstName(profile.displayName())
                                + ", I can help with registered payee lookups and domestic payments to existing payees."),
                        blockFactory.info(
                                "Try this",
                                "Try \"Show my payees\" or \"Pay BOB 500 HKD\".")));
        session.addMessage(welcomeMessage);
        conversationSessionRepository.save(session);

        return new ApiModels.ChatSessionCreateResponse(
                apiMapper.toSessionDetail(session),
                List.of(apiMapper.toMessage(welcomeMessage)));
    }

    public synchronized ApiModels.ChatSessionDetailResponse getSession(String profileId, String sessionId) {
        return apiMapper.toSessionDetail(requireSession(profileId, sessionId));
    }

    public synchronized ApiModels.ChatMessagePageResponse listMessages(String profileId, String sessionId) {
        return apiMapper.toMessagePage(requireSession(profileId, sessionId).getMessages());
    }

    public synchronized ApiModels.ChatTurnResponse sendMessage(
            String profileId,
            String sessionId,
            ApiModels.SendMessageRequest request) {
        Profile profile = profileService.requireProfile(profileId);
        ConversationSession session = requireWritableSession(profileId, sessionId);
        String messageText = request.messageText().trim();

        ConversationMessage userMessage = conversationMessageFactory.userTextMessage(
                session.getId(),
                session.nextSequenceNo(),
                messageText);
        session.addMessage(userMessage);

        TurnOutcome outcome = domesticExistingPayeeJourney.handleText(profile, session, messageText);
        List<ConversationMessage> assistantMessages = appendAssistantMessages(session, outcome);
        conversationSessionRepository.save(session);
        return apiMapper.toTurnResponse(session, userMessage, assistantMessages, outcome.suggestedActions());
    }

    public synchronized ApiModels.ChatTurnResponse submitEvent(
            String profileId,
            String sessionId,
            ApiModels.UiEventRequest request) {
        Profile profile = profileService.requireProfile(profileId);
        ConversationSession session = requireWritableSession(profileId, sessionId);

        ConversationMessage userMessage = conversationMessageFactory.userEventMessage(
                session.getId(),
                session.nextSequenceNo(),
                deriveEventText(session, request));
        session.addMessage(userMessage);

        TurnOutcome outcome = domesticExistingPayeeJourney.handleUiEvent(profile, session, request);
        List<ConversationMessage> assistantMessages = appendAssistantMessages(session, outcome);
        conversationSessionRepository.save(session);
        return apiMapper.toTurnResponse(session, userMessage, assistantMessages, outcome.suggestedActions());
    }

    private ConversationSession requireSession(String profileId, String sessionId) {
        profileService.requireProfile(profileId);
        return conversationSessionRepository.findByProfileIdAndId(profileId, sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CHAT2PAY-404", "Chat session not found."));
    }

    private ConversationSession requireWritableSession(String profileId, String sessionId) {
        ConversationSession session = requireSession(profileId, sessionId);
        if (session.isTerminal()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CHAT2PAY-400", "This chat session is read-only.");
        }
        return session;
    }

    private List<ConversationMessage> appendAssistantMessages(ConversationSession session, TurnOutcome outcome) {
        if (outcome.assistantBlocks() == null || outcome.assistantBlocks().isEmpty()) {
            return List.of();
        }

        ConversationMessage assistantMessage = conversationMessageFactory.assistantMessage(
                session.getId(),
                session.nextSequenceNo(),
                outcome.assistantText(),
                outcome.assistantBlocks());
        session.addMessage(assistantMessage);
        return List.of(assistantMessage);
    }

    private String deriveEventText(ConversationSession session, ApiModels.UiEventRequest request) {
        return switch (request.eventType()) {
            case SUBMIT_FORM -> "Submitted payment details";
            case CLICK_ACTION -> request.selectedItemIds() != null && !request.selectedItemIds().isEmpty()
                    ? request.selectedItemIds().getFirst().replace('_', ' ')
                    : "Submitted action";
            case SELECT_ITEM -> {
                if (session.getActiveDraft() == null || request.selectedItemIds() == null || request.selectedItemIds().isEmpty()) {
                    yield "Selected payee";
                }
                var payee = session.getActiveDraft().getCandidatePayees().get(request.selectedItemIds().getFirst());
                yield payee != null ? "Selected " + payee.name() : "Selected payee";
            }
        };
    }

    private String firstName(String displayName) {
        return displayName == null || displayName.isBlank() ? "there" : displayName.split("\\s+")[0];
    }
}
