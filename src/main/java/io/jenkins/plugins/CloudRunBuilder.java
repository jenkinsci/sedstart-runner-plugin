package io.jenkins.plugins;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jakarta.servlet.ServletException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.util.logging.Logger;


/**
 * CloudRunBuilder
 * - UI fields exposed via config.jelly
 * - API key is read from the environment variable SEDSTART_API_KEY
 * - Pretty-printing of SSE JSON events
 */
public class CloudRunBuilder extends Builder implements SimpleBuildStep {
    private static final Logger LOGGER = Logger.getLogger(CloudRunBuilder.class.getName());
    private final String name;

    // Parameters for the API request
    private Integer projectId;   // required
    private Integer suiteId;     // exclusive with testId
    private Integer testId;      // exclusive with suiteId
    private Integer profileId;   // required
    private String browser = "chrome";
    private boolean headless = true;
    private String environment = "QA";
    private String apiKeyEnvName = "SEDSTART_API_KEY";


    @DataBoundConstructor
    public CloudRunBuilder(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Integer getProjectId() {
        return projectId;
    }

    @DataBoundSetter
    public void setProjectId(Integer projectId) {
        this.projectId = projectId;
    }

    public Integer getSuiteId() {
        return suiteId;
    }

    @DataBoundSetter
    public void setSuiteId(Integer suiteId) {
        this.suiteId = suiteId;
    }

    public Integer getTestId() {
        return testId;
    }

    @DataBoundSetter
    public void setTestId(Integer testId) {
        this.testId = testId;
    }

    public Integer getProfileId() {
        return profileId;
    }

    @DataBoundSetter
    public void setProfileId(Integer profileId) {
        this.profileId = profileId;
    }

    public String getBrowser() {
        return browser;
    }

    @DataBoundSetter
    public void setBrowser(String browser) {
        this.browser = browser;
    }

    public boolean isHeadless() {
        return headless;
    }

    @DataBoundSetter
    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public String getApiKeyEnvName() { return apiKeyEnvName; }

    @DataBoundSetter public void setApiKeyEnvName(String apiKeyEnvName) { this.apiKeyEnvName = apiKeyEnvName; }


    public String getEnvironment() {
        return environment;
    }

    @DataBoundSetter
    public void setEnvironment(String environment) {
        if (environment != null && !environment.trim().isEmpty()) {
            this.environment = environment.trim();
        }
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {

        // Basic validation
        if (projectId == null) {
            listener.getLogger().println("[sedstart] ERROR: projectId is required.");
            throw new IOException("projectId is required");
        }
        if ((suiteId == null && testId == null) || (suiteId != null && testId != null)) {
            listener.getLogger().println("[sedstart] ERROR: exactly one of suiteId or testId must be provided.");
            throw new IOException("Exactly one of suiteId or testId must be provided");
        }
        if (profileId == null) {
            listener.getLogger().println("[sedstart] ERROR: profileId is required.");
            throw new IOException("profileId is required");
        }

        // Read API key from environment variable only
        String effectiveApiKey = null;

        if (env != null) {
            effectiveApiKey = env.get(apiKeyEnvName);
        }
        if (effectiveApiKey == null || effectiveApiKey.isEmpty()) {
            listener.getLogger().println("[sedstart] ERROR: environment variable "+apiKeyEnvName+" is not set. Aborting request.");
            throw new IOException(apiKeyEnvName+" environment variable is required");
        }

        // choose base URL based on environment
        String baseUrl;
        String envVal = (environment == null) ? "" : environment.trim();
        if ("QA".equalsIgnoreCase(envVal)) {
            baseUrl = "https://sedstart.sedinqa.com";
        } else if ("PROD".equalsIgnoreCase(envVal)) {
            baseUrl = "https://app.sedstart.com";
        } else if (envVal.startsWith("http://") || envVal.startsWith("https://")) {
            baseUrl = envVal;
        } else if (!envVal.isEmpty()) {
            baseUrl = "https://" + envVal;
        } else {
            // fallback default
            baseUrl = "https://sedstart.sedinqa.com";
        }

        String endpoint = String.format("%s/api/project/%d/runCI", baseUrl, projectId);
        listener.getLogger().println("[sedstart] Triggering runCI for project: " + projectId + " -> " + endpoint);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setRequestProperty("Accept", "text/event-stream, */*");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "APIKey " + effectiveApiKey);

            // Build JSON body
            StringBuilder json = new StringBuilder();
            json.append('{');
            if (suiteId != null) {
                json.append("\"suite_id\":").append(suiteId).append(',');
            } else {
                json.append("\"test_id\":").append(testId).append(',');
            }
            json.append("\"profile_id\":").append(profileId).append(',');
            json.append("\"browser\":\"").append(escapeJson(browser)).append("\",");
            json.append("\"headless\":").append(headless);
            json.append('}');

            byte[] out = json.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
                os.flush();
            }

            int status = conn.getResponseCode();
            InputStream rawStream = (status >= 200 && status < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (rawStream == null) {
                listener.getLogger().println("[sedstart] No response stream received (HTTP " + status + ").");
                return;
            }

            // Read Server-Sent Events (SSE) from the response stream and print to build log
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(rawStream, StandardCharsets.UTF_8))) {
                String line;
                StringBuilder eventData = new StringBuilder();
                String eventType = null;

                listener.getLogger().println("[sedstart] Streaming response (HTTP " + status + "):");

                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (eventData.length() > 0) {
                            String data = eventData.toString();
                            prettyLogEvent(data, listener);
                            eventData.setLength(0);
                            eventType = null;
                        }
                        continue;
                    }

                    if (line.startsWith("data:")) {
                        String payload = line.substring(5).trim();
                        if (eventData.length() > 0) eventData.append(' ');
                        eventData.append(payload);
                    } else if (line.startsWith("event:")) {
                        eventType = line.substring(6).trim();
                    } else if (line.startsWith("id:")) {
                    } else {
                        // fallback: print raw line
                        listener.getLogger().println("[sedstart] >> " + line);
                    }
                }

                // flush any leftover data
                if (eventData.length() > 0) {
                    String data = eventData.toString();
                    prettyLogEvent(data, listener);
                }
            }

        } catch (IOException e) {
            listener.getLogger().println("[sedstart] ERROR while calling runCI: " + e.getMessage());
            throw e;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------- Pretty-printing helpers----------
    private static void prettyLogEvent(String jsonText, TaskListener listener) {
        LOGGER.info("[sedstart-dev] raw SSE: " + jsonText);

        if (jsonText == null || jsonText.isEmpty()) {
            return;
        }

        // check only trim, original jsonText not mutated
        String trimmed = jsonText.trim();

        //If not JSON object print raw
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}"))) {
            listener.getLogger().println("[sedstart] " + jsonText);
            return;
        }

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        try {
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(jsonText);
            com.fasterxml.jackson.databind.JsonNode result = root.path("result");

            if (result.isMissingNode() || result.isNull()) {
                listener.getLogger().println("[sedstart] " + jsonText);
                return;
            }

            String type = result.path("type").asText("");

            java.util.function.Function<String, String> emoji = st -> {
                if (st == null) return "ℹ️";
                switch (st.toUpperCase()) {
                    case "PASS": return "✅";
                    case "FAIL": return "❌";
                    case "IN_PROGRESS":
                    case "IN-PROGRESS":
                    case "INPROGRESS":
                        return "⏳";
                    default: return "ℹ️";
                }
            };

            if ("Run".equalsIgnoreCase(type)) {
                String status = result.path("status").asText("");
                String prefix = emoji.apply(status);

                if (status == null || status.isEmpty()) {
                    listener.getLogger().println(prefix + " RUN");
                } else {
                    listener.getLogger().println(prefix + " " + status);
                }

                if ("FAIL".equalsIgnoreCase(status)) {
                    String err = result.path("error").asText("");
                    if (err != null && !err.isEmpty()) {
                        listener.getLogger().println("❌ Error: " + err);
                    }
                }

                com.fasterxml.jackson.databind.JsonNode videoNode = result.path("video");
                if (videoNode != null && !videoNode.isMissingNode() && !videoNode.isNull()) {
                    if (videoNode.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode v : videoNode) {
                            String url = v.asText("");
                            if (!url.isEmpty()) {
                                listener.getLogger().println("🎥 " + url);
                            }
                        }
                    } else if (videoNode.isTextual()) {
                        String url = videoNode.asText("");
                        if (!url.isEmpty()) {
                            listener.getLogger().println("🎥 " + url);
                        }
                    }
                }

            } else if ("Test".equalsIgnoreCase(type)) {
                String testName = result.path("name").asText("");
                String testStatus = result.path("status").asText("");
                String testLine = "🧪 Test: " + (testName.isEmpty() ? "<unnamed>" : testName)
                        + " — " + (testStatus.isEmpty() ? "UNKNOWN" : testStatus);
                listener.getLogger().println(testLine);

                com.fasterxml.jackson.databind.JsonNode items = result.path("items");
                if (items.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode testStepNode : items) {
                        String itemType = testStepNode.path("type").asText("");
                        if (!"TestStep".equalsIgnoreCase(itemType)) {
                            continue;
                        }

                        String stepName = testStepNode.path("name").asText("");
                        String stepStatus = testStepNode.path("status").asText("");
                        listener.getLogger().println("  • " + (stepName.isEmpty() ? "TestStep" : stepName)
                                + " — " + (stepStatus.isEmpty() ? "UNKNOWN" : stepStatus));

                        com.fasterxml.jackson.databind.JsonNode stepItems = testStepNode.path("items");
                        if (stepItems.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode stepItemNode : stepItems) {
                                String stepItemType = stepItemNode.path("type").asText("");
                                if (!"StepItem".equalsIgnoreCase(stepItemType)) {
                                    continue;
                                }

                                String stepItemName = stepItemNode.path("name").asText("");
                                String stepItemStatus = stepItemNode.path("status").asText("");
                                listener.getLogger().println("    - " + (stepItemName.isEmpty() ? "StepItem" : stepItemName)
                                        + " — " + (stepItemStatus.isEmpty() ? "UNKNOWN" : stepItemStatus));

                                com.fasterxml.jackson.databind.JsonNode resourceActions = stepItemNode.path("items");
                                if (resourceActions.isArray()) {
                                    for (com.fasterxml.jackson.databind.JsonNode actionNode : resourceActions) {
                                        String actionType = actionNode.path("type").asText("");
                                        if (!"ResourceElementAction".equalsIgnoreCase(actionType)) {
                                            continue;
                                        }
                                        String actionName = actionNode.path("name").asText("");
                                        String actionStatus = actionNode.path("status").asText("");
                                        listener.getLogger().println("      → " + (actionName.isEmpty() ? "ResourceElementAction" : actionName)
                                                + " — " + (actionStatus.isEmpty() ? "UNKNOWN" : actionStatus));
                                    }
                                }
                            }
                        }
                    }
                }

            } else {
                listener.getLogger().println("[sedstart] " + jsonText);
            }

        } catch (Exception e) {
            listener.getLogger().println("[sedstart] " + jsonText);
        }
    }

    private static String extractStringField(String json, String keyPath) {
        String[] parts = keyPath.split("\\.");
        String current = json;
        for (int i = 0; i < parts.length - 1; i++) {
            String parent = parts[i];
            current = extractObject(current, parent);
            if (current == null) return "";
        }
        String finalKey = parts[parts.length - 1];
        Pattern p = Pattern.compile("\"" + Pattern.quote(finalKey) + "\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
        Matcher m = p.matcher(current);
        if (m.find()) return m.group(1);
        return "";
    }

    private static String extractArrayFirst(String json, String keyPath) {
        String[] parts = keyPath.split("\\.");
        String current = json;
        for (int i = 0; i < parts.length - 1; i++) {
            String parent = parts[i];
            current = extractObject(current, parent);
            if (current == null) return "";
        }
        String finalKey = parts[parts.length - 1];
        Pattern p = Pattern.compile("\"" + Pattern.quote(finalKey) + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
        Matcher m = p.matcher(current);
        if (m.find()) {
            String inside = m.group(1).trim();
            Pattern q = Pattern.compile("\"(.*?)\"");
            Matcher mq = q.matcher(inside);
            if (mq.find()) return mq.group(1);
        }
        return "";
    }

    private static String extractObject(String json, String key) {
        // Find the object value for the given key: "key": { ... }
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{", Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (!m.find()) return null;
        int start = m.end() - 1; // position of '{'
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            if (depth == 0) {
                return json.substring(start, i + 1);
            }
        }
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s
                .replace("\\", "\\\\")   // escape backslashes
                .replace("\"", "\\\"")   // escape quotes
                .replace("\n", "\\n");   // escape newlines
    }

    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public FormValidation doCheckName(@QueryParameter String value)
                throws IOException, ServletException {
            if (value == null || value.length() == 0)
                return FormValidation.error(Messages.SedStartBuilder_DescriptorImpl_errors_missingName());
            if (value.length() < 4)
                return FormValidation.warning(Messages.SedStartBuilder_DescriptorImpl_warnings_tooShort());
            return FormValidation.ok();
        }

        public FormValidation doCheckProjectId(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) return FormValidation.error("projectId is required");
            try {
                Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return FormValidation.error("projectId must be numeric");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckProfileId(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) return FormValidation.error("profileId is required");
            try {
                Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return FormValidation.error("profileId must be numeric");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckSuiteId(@QueryParameter String suiteValue, @QueryParameter String testValue) {
            boolean suiteEmpty = suiteValue == null || suiteValue.trim().isEmpty();
            boolean testEmpty = testValue == null || testValue.trim().isEmpty();
            if (suiteEmpty && testEmpty) {
                return FormValidation.error("Either suiteId or testId must be provided");
            }
            if (!suiteEmpty && !testEmpty) {
                return FormValidation.error("Provide only one of suiteId or testId, not both");
            }
            if (!suiteEmpty) {
                try { Integer.parseInt(suiteValue); } catch (NumberFormatException e) { return FormValidation.error("suiteId must be numeric"); }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckTestId(@QueryParameter String testValue, @QueryParameter String suiteValue) {
            // reuse logic above
            boolean suiteEmpty = suiteValue == null || suiteValue.trim().isEmpty();
            boolean testEmpty = testValue == null || testValue.trim().isEmpty();
            if (suiteEmpty && testEmpty) {
                return FormValidation.error("Either suiteId or testId must be provided");
            }
            if (!suiteEmpty && !testEmpty) {
                return FormValidation.error("Provide only one of suiteId or testId, not both");
            }
            if (!testEmpty) {
                try { Integer.parseInt(testValue); } catch (NumberFormatException e) { return FormValidation.error("testId must be numeric"); }
            }
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

//        @Override
        // public String getDisplayName() {
        //     return Messages.SedStartBuilder_DescriptorImpl_DisplayName();
        // }
    }
}