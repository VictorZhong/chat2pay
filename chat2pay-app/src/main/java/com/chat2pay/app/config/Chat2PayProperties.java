package com.chat2pay.app.config;

import com.chat2pay.app.domain.JourneyType;
import com.chat2pay.app.domain.ProfileStatus;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "chat2pay")
public class Chat2PayProperties {

    private final Security security = new Security();
    private final Llm llm = new Llm();
    private final Demo demo = new Demo();
    private final Downstream downstream = new Downstream();

    public Security getSecurity() {
        return security;
    }

    public Llm getLlm() {
        return llm;
    }

    public Demo getDemo() {
        return demo;
    }

    public Downstream getDownstream() {
        return downstream;
    }

    public static class Security {

        @NotBlank
        private String sharedPassword;

        public String getSharedPassword() {
            return sharedPassword;
        }

        public void setSharedPassword(String sharedPassword) {
            this.sharedPassword = sharedPassword;
        }
    }

    public static class Llm {

        private boolean enabled = true;
        private boolean fallbackToHeuristics = true;

        @NotBlank
        private String baseUrl;

        @NotBlank
        private String chatPath;

        private final Remote remote = new Remote();

        private Map<String, UseCaseConfig> useCases = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isFallbackToHeuristics() {
            return fallbackToHeuristics;
        }

        public void setFallbackToHeuristics(boolean fallbackToHeuristics) {
            this.fallbackToHeuristics = fallbackToHeuristics;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getChatPath() {
            return chatPath;
        }

        public void setChatPath(String chatPath) {
            this.chatPath = chatPath;
        }

        public Remote getRemote() {
            return remote;
        }

        public Map<String, UseCaseConfig> getUseCases() {
            return useCases;
        }

        public void setUseCases(Map<String, UseCaseConfig> useCases) {
            this.useCases = useCases == null ? new LinkedHashMap<>() : useCases;
        }
    }

    public enum LlmProviderType {
        COPILOT,
        REMOTE
    }

    public static class Remote {

        private boolean enabled = false;
        private final RemoteAuth auth = new RemoteAuth();
        private String defaultUser;
        private List<RemoteModel> models = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public RemoteAuth getAuth() {
            return auth;
        }

        public String getDefaultUser() {
            return defaultUser;
        }

        public void setDefaultUser(String defaultUser) {
            this.defaultUser = defaultUser;
        }

        public List<RemoteModel> getModels() {
            return models;
        }

        public void setModels(List<RemoteModel> models) {
            this.models = models == null ? new ArrayList<>() : models;
        }
    }

    public static class RemoteAuth {

        private String tokenUrl;
        private String username;
        private String password;
        private long tokenTtlSeconds = 1800;

        public String getTokenUrl() {
            return tokenUrl;
        }

        public void setTokenUrl(String tokenUrl) {
            this.tokenUrl = tokenUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public long getTokenTtlSeconds() {
            return tokenTtlSeconds;
        }

        public void setTokenTtlSeconds(long tokenTtlSeconds) {
            this.tokenTtlSeconds = tokenTtlSeconds;
        }
    }

    public static class RemoteModel {

        @NotBlank
        private String name;

        @NotBlank
        private String url;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    public static class UseCaseConfig {

        private LlmProviderType provider = LlmProviderType.COPILOT;
        private String model;
        private Integer maxTokens;
        private LlmProviderType fallbackProvider;

        public LlmProviderType getProvider() {
            return provider;
        }

        public void setProvider(LlmProviderType provider) {
            this.provider = provider == null ? LlmProviderType.COPILOT : provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public LlmProviderType getFallbackProvider() {
            return fallbackProvider;
        }

        public void setFallbackProvider(LlmProviderType fallbackProvider) {
            this.fallbackProvider = fallbackProvider;
        }
    }

    public static class Demo {

        @NotBlank
        private String defaultSourceAccountId;

        @NotBlank
        private String defaultSourceAccountDisplay;

        private List<ProfileConfig> profiles = new ArrayList<>();

        public String getDefaultSourceAccountId() {
            return defaultSourceAccountId;
        }

        public void setDefaultSourceAccountId(String defaultSourceAccountId) {
            this.defaultSourceAccountId = defaultSourceAccountId;
        }

        public String getDefaultSourceAccountDisplay() {
            return defaultSourceAccountDisplay;
        }

        public void setDefaultSourceAccountDisplay(String defaultSourceAccountDisplay) {
            this.defaultSourceAccountDisplay = defaultSourceAccountDisplay;
        }

        public List<ProfileConfig> getProfiles() {
            return profiles;
        }

        public void setProfiles(List<ProfileConfig> profiles) {
            this.profiles = profiles;
        }
    }

    public static class ProfileConfig {

        @NotBlank
        private String id;

        @NotBlank
        private String code;

        @NotBlank
        private String username;

        @NotBlank
        private String displayName;

        private String avatarUrl;

        @NotBlank
        private String mockCustomerId;

        @NotBlank
        private String locale;

        private ProfileStatus status = ProfileStatus.ACTIVE;

        private List<JourneyType> supportedJourneyTypes = new ArrayList<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getAvatarUrl() {
            return avatarUrl;
        }

        public void setAvatarUrl(String avatarUrl) {
            this.avatarUrl = avatarUrl;
        }

        public String getMockCustomerId() {
            return mockCustomerId;
        }

        public void setMockCustomerId(String mockCustomerId) {
            this.mockCustomerId = mockCustomerId;
        }

        public String getLocale() {
            return locale;
        }

        public void setLocale(String locale) {
            this.locale = locale;
        }

        public ProfileStatus getStatus() {
            return status;
        }

        public void setStatus(ProfileStatus status) {
            this.status = status;
        }

        public List<JourneyType> getSupportedJourneyTypes() {
            return supportedJourneyTypes;
        }

        public void setSupportedJourneyTypes(List<JourneyType> supportedJourneyTypes) {
            this.supportedJourneyTypes = supportedJourneyTypes;
        }
    }

    public static class Downstream {

        private boolean mockEnabled = true;
        private final Login login = new Login();
        private final Payee payee = new Payee();
        private final Payment payment = new Payment();

        public boolean isMockEnabled() {
            return mockEnabled;
        }

        public void setMockEnabled(boolean mockEnabled) {
            this.mockEnabled = mockEnabled;
        }

        public Login getLogin() {
            return login;
        }

        public Payee getPayee() {
            return payee;
        }

        public Payment getPayment() {
            return payment;
        }
    }

    public static class Login {

        @NotBlank
        private String urlTemplate;

        @NotBlank
        private String code;

        public String getUrlTemplate() {
            return urlTemplate;
        }

        public void setUrlTemplate(String urlTemplate) {
            this.urlTemplate = urlTemplate;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }
    }

    public static class Payee {

        @NotBlank
        private String url;

        @NotBlank
        private String payeeCategory;

        @NotBlank
        private String payeeType;

        private List<PayeeConfig> mockPayees = new ArrayList<>();

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getPayeeCategory() {
            return payeeCategory;
        }

        public void setPayeeCategory(String payeeCategory) {
            this.payeeCategory = payeeCategory;
        }

        public String getPayeeType() {
            return payeeType;
        }

        public void setPayeeType(String payeeType) {
            this.payeeType = payeeType;
        }

        public List<PayeeConfig> getMockPayees() {
            return mockPayees;
        }

        public void setMockPayees(List<PayeeConfig> mockPayees) {
            this.mockPayees = mockPayees;
        }
    }

    public static class PayeeConfig {

        @NotBlank
        private String payeeIdIndex;

        @NotBlank
        private String payeeType;

        @NotBlank
        private String name;

        private String description;

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

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public static class Payment {

        @NotBlank
        private String confirmUrl;

        @NotBlank
        private String defaultCurrency;

        @NotBlank
        private String debitAccountAcn;

        @NotBlank
        private String productCategoryCode;

        public String getConfirmUrl() {
            return confirmUrl;
        }

        public void setConfirmUrl(String confirmUrl) {
            this.confirmUrl = confirmUrl;
        }

        public String getDefaultCurrency() {
            return defaultCurrency;
        }

        public void setDefaultCurrency(String defaultCurrency) {
            this.defaultCurrency = defaultCurrency;
        }

        public String getDebitAccountAcn() {
            return debitAccountAcn;
        }

        public void setDebitAccountAcn(String debitAccountAcn) {
            this.debitAccountAcn = debitAccountAcn;
        }

        public String getProductCategoryCode() {
            return productCategoryCode;
        }

        public void setProductCategoryCode(String productCategoryCode) {
            this.productCategoryCode = productCategoryCode;
        }
    }
}
