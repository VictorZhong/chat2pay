package com.chat2pay.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "chat2pay.llm.enabled=false",
        "chat2pay.downstream.mock-enabled=true"
})
@AutoConfigureMockMvc
class Chat2PayApplicationTests {

    private static final String SHARED_PASSWORD = "tb123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void domesticExistingPayeeFlowConfirmsSuccessfully() throws Exception {
        String profileId = fetchFirstProfileId();
        login(profileId);

        JsonNode createSession = readJson(mockMvc.perform(post("/api/chat/sessions")
                        .header("X-Profile-Id", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn());

        String sessionId = createSession.path("session").path("sessionId").asText();
        assertThat(sessionId).isNotBlank();

        JsonNode paymentSummary = readJson(mockMvc.perform(post("/api/chat/sessions/{sessionId}/messages", sessionId)
                        .header("X-Profile-Id", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messageText":"Pay BOB 500 HKD"}
                                """))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(paymentSummary.path("workflowState").asText()).isEqualTo("AWAITING_USER_CONFIRMATION");
        assertThat(paymentSummary.path("draftSummary").path("payeeDisplay").asText()).isEqualTo("BOB");
        assertThat(paymentSummary.path("draftSummary").path("payeeIdIndex").asText()).isEqualTo("demo-payee-001");
        assertThat(paymentSummary.path("draftSummary").path("status").asText()).isEqualTo("READY_FOR_CONFIRMATION");

        JsonNode confirmed = readJson(mockMvc.perform(post("/api/chat/sessions/{sessionId}/events", sessionId)
                        .header("X-Profile-Id", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"CLICK_ACTION",
                                  "sourceMessageId":"msg-confirm",
                                  "sourceBlockId":"blk-confirm",
                                  "selectedItemIds":["CONFIRM_TRANSFER"]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(confirmed.path("session").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(confirmed.path("workflowState").asText()).isEqualTo("COMPLETED");
        assertThat(confirmed.path("draftSummary").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("draftSummary").path("transferReference").asText()).startsWith("MOCK-");
    }

    @Test
    void unsupportedInternationalRequestReturnsInfoCard() throws Exception {
        String profileId = fetchFirstProfileId();
        String sessionId = createSession(profileId);

        JsonNode response = readJson(mockMvc.perform(post("/api/chat/sessions/{sessionId}/messages", sessionId)
                        .header("X-Profile-Id", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messageText":"Make an international transfer to Alice"}
                                """))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(response.path("workflowState").asText()).isEqualTo("IDLE");
        assertThat(response.path("assistantMessages").get(0).path("contentBlocks").get(0).path("type").asText())
                .isEqualTo("INFO_CARD");
        assertThat(response.path("assistantMessages").get(0).path("contentBlocks").get(0).path("text").asText())
                .contains("existing payees only");
    }

    @Test
    void browseRegisteredPayeesReturnsDirectoryListWithDetailMetadata() throws Exception {
        String profileId = fetchFirstProfileId();
        String sessionId = createSession(profileId);

        JsonNode response = readJson(mockMvc.perform(post("/api/chat/sessions/{sessionId}/messages", sessionId)
                        .header("X-Profile-Id", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messageText":"Show my payees"}
                                """))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(response.path("session").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(response.path("workflowState").asText()).isEqualTo("IDLE");
        assertThat(response.path("draftSummary").isMissingNode() || response.path("draftSummary").isNull()).isTrue();

        JsonNode listBlock = response.path("assistantMessages").get(0).path("contentBlocks").get(1);
        assertThat(listBlock.path("type").asText()).isEqualTo("SELECTABLE_LIST");
        assertThat(listBlock.path("metadata").path("interactionMode").asText()).isEqualTo("OPEN_DETAIL_MODAL");
        assertThat(listBlock.path("items").get(0).path("metadata").path("detailFields").isArray()).isTrue();
    }

    @Test
    void changePayeeDuringReviewClearsResolvedPayeeAndAsksForReplacement() throws Exception {
        String profileId = fetchFirstProfileId();
        String sessionId = createSession(profileId);

        readJson(mockMvc.perform(post("/api/chat/sessions/{sessionId}/messages", sessionId)
                        .header("X-Profile-Id", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messageText":"Pay BOB 500 HKD"}
                                """))
                .andExpect(status().isOk())
                .andReturn());

        JsonNode changePayee = readJson(mockMvc.perform(post("/api/chat/sessions/{sessionId}/events", sessionId)
                        .header("X-Profile-Id", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"CLICK_ACTION",
                                  "sourceMessageId":"msg-review",
                                  "sourceBlockId":"blk-review",
                                  "selectedItemIds":["CHANGE_PAYEE"]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(changePayee.path("session").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(changePayee.path("workflowState").asText()).isEqualTo("COLLECTING_PAYMENT_DETAILS");
        assertThat(changePayee.path("draftSummary").path("payeeIdIndex").isNull()).isTrue();
        assertThat(changePayee.path("draftSummary").path("payeeNameInput").isNull()).isTrue();
        assertThat(changePayee.path("draftSummary").path("amount").decimalValue()).isEqualByComparingTo("500");

        JsonNode formFields = changePayee.path("assistantMessages").get(0).path("contentBlocks").get(1).path("fields");
        assertThat(formFields).hasSize(2);
        assertThat(formFields.get(0).path("fieldId").asText()).isEqualTo("payee");
    }

    @Test
    void cancelledSessionBecomesReadOnly() throws Exception {
        String profileId = fetchFirstProfileId();
        String sessionId = createSession(profileId);

        JsonNode cancelled = readJson(mockMvc.perform(post("/api/chat/sessions/{sessionId}/messages", sessionId)
                        .header("X-Profile-Id", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messageText":"Cancel"}
                                """))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(cancelled.path("session").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("workflowState").asText()).isEqualTo("CANCELLED");

        mockMvc.perform(post("/api/chat/sessions/{sessionId}/messages", sessionId)
                        .header("X-Profile-Id", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messageText":"Pay BOB 100 HKD"}
                                """))
                .andExpect(status().isBadRequest());
    }

    private String createSession(String profileId) throws Exception {
        login(profileId);
        JsonNode createSession = readJson(mockMvc.perform(post("/api/chat/sessions")
                        .header("X-Profile-Id", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn());
        return createSession.path("session").path("sessionId").asText();
    }

    private String fetchFirstProfileId() throws Exception {
        JsonNode profiles = readJson(mockMvc.perform(get("/api/profiles"))
                .andExpect(status().isOk())
                .andReturn());
        String profileId = profiles.get(0).path("id").asText();
        assertThat(profileId).isNotBlank();
        return profileId;
    }

    private void login(String profileId) throws Exception {
        mockMvc.perform(post("/api/auth/profile-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "profileId":"%s",
                                  "password":"%s"
                                }
                                """.formatted(profileId, SHARED_PASSWORD)))
                .andExpect(status().isOk());
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
