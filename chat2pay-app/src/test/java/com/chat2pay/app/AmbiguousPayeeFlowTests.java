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
        "chat2pay.downstream.mock-enabled=true",
        "chat2pay.downstream.payee.mock-payees[0].payee-id-index=demo-payee-001",
        "chat2pay.downstream.payee.mock-payees[0].payee-type=6",
        "chat2pay.downstream.payee.mock-payees[0].name=BOB",
        "chat2pay.downstream.payee.mock-payees[0].description=Registered local payee",
        "chat2pay.downstream.payee.mock-payees[1].payee-id-index=demo-payee-002",
        "chat2pay.downstream.payee.mock-payees[1].payee-type=6",
        "chat2pay.downstream.payee.mock-payees[1].name=BOBBY",
        "chat2pay.downstream.payee.mock-payees[1].description=Registered local payee 2"
})
@AutoConfigureMockMvc
class AmbiguousPayeeFlowTests {

    private static final String SHARED_PASSWORD = "tb123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void ambiguousPayeeSelectionAsksUserThenBuildsConfirmation() throws Exception {
        String profileId = fetchFirstProfileId();
        String sessionId = createSession(profileId);

        JsonNode ambiguousResponse = readJson(mockMvc.perform(post("/api/chat/sessions/{sessionId}/messages", sessionId)
                        .header("X-Profile-Id", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messageText":"Pay BO 500 HKD"}
                                """))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(ambiguousResponse.path("workflowState").asText()).isEqualTo("RESOLVING_PAYEE");
        JsonNode assistantBlocks = ambiguousResponse.path("assistantMessages").get(0).path("contentBlocks");
        assertThat(assistantBlocks.get(1).path("type").asText()).isEqualTo("SELECTABLE_LIST");
        assertThat(assistantBlocks.get(1).path("items")).hasSize(2);

        JsonNode selectionResponse = readJson(mockMvc.perform(post("/api/chat/sessions/{sessionId}/events", sessionId)
                        .header("X-Profile-Id", profileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"SELECT_ITEM",
                                  "sourceMessageId":"msg-ambiguous",
                                  "sourceBlockId":"blk-ambiguous",
                                  "selectedItemIds":["demo-payee-002"]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(selectionResponse.path("workflowState").asText()).isEqualTo("AWAITING_USER_CONFIRMATION");
        assertThat(selectionResponse.path("draftSummary").path("payeeIdIndex").asText()).isEqualTo("demo-payee-002");
        assertThat(selectionResponse.path("draftSummary").path("payeeDisplay").asText()).isEqualTo("BOBBY");
        assertThat(selectionResponse.path("assistantMessages").get(0).path("contentBlocks").get(1).path("type").asText())
                .isEqualTo("SUMMARY_CARD");
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
