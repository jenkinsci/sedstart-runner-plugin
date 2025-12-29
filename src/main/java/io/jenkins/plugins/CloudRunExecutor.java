package io.jenkins.plugins;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class CloudRunExecutor {

    void execute(
            Run<?, ?> run,
            FilePath workspace,
            EnvVars env,
            Launcher launcher,
            TaskListener listener,
            Integer projectId,
            Integer suiteId,
            Integer testId,
            Integer profileId,
            String browser,
            boolean headless,
            String environment,
            String apiKeyEnvName
    ) throws IOException {

        validate(projectId, suiteId, testId, profileId);

        String apiKey = env.get(apiKeyEnvName);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException(apiKeyEnvName + " environment variable is required");
        }

        String baseUrl =
                "QA".equalsIgnoreCase(environment)
                        ? "https://sedstart.sedinqa.com"
                        : "https://app.sedstart.com";

        String endpoint = baseUrl + "/api/project/" + projectId + "/runCI";
        listener.getLogger().println("[sedstart] Triggering Cloud run: " + endpoint);

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "APIKey " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");

        String body = buildJson(suiteId, testId, profileId, browser, headless);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status >= 400) {
            throw new IOException("Cloud run failed with HTTP " + status);
        }

        listener.getLogger().println("[sedstart] Cloud run triggered successfully");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    listener.getLogger().println("[sedstart] " + line.substring(5).trim());
                }
            }
        }
    }

    private static void validate(Integer projectId, Integer suiteId, Integer testId, Integer profileId)
            throws IOException {

        if (projectId == null) throw new IOException("projectId is required");
        if (profileId == null) throw new IOException("profileId is required");
        if ((suiteId == null && testId == null) || (suiteId != null && testId != null)) {
            throw new IOException("Provide exactly one of suiteId or testId");
        }
    }

    private static String buildJson(
            Integer suiteId,
            Integer testId,
            Integer profileId,
            String browser,
            boolean headless
    ) {
        return "{"
                + (suiteId != null
                ? "\"suite_id\":" + suiteId + ","
                : "\"test_id\":" + testId + ",")
                + "\"profile_id\":" + profileId + ","
                + "\"browser\":\"" + browser + "\","
                + "\"headless\":" + headless
                + "}";
    }
}
