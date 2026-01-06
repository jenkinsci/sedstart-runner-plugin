package io.jenkins.plugins;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.ProxyConfiguration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class CloudRunExecutor {

    private static final String API_KEY_ENV = "SEDSTART_API_KEY";

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
            String environment
    ) throws IOException, InterruptedException {

        validate(projectId, suiteId, testId, profileId);

        String apiKey = env.get(API_KEY_ENV);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException(
                    "SEDSTART_API_KEY is not set. Use Jenkins credentials binding."
            );
        }

        String baseUrl =
                "QA".equalsIgnoreCase(environment)
                        ? "https://sedstart.sedinqa.com"
                        : "https://app.sedstart.com";

        if (!baseUrl.startsWith("https://")) {
            throw new IOException("Only HTTPS endpoints are allowed");
        }

        URI uri = URI.create(baseUrl + "/api/project/" + projectId + "/runCI");
        listener.getLogger().println("[sedstart] Triggering Cloud run: " + uri);

        HttpClient client = createHttpClient();

        String body = buildJson(suiteId, testId, profileId, browser, headless);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))   // request timeout
                .header("Authorization", "APIKey " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<java.io.InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() >= 400) {
            throw new IOException("Cloud run failed with HTTP " + response.statusCode());
        }

        listener.getLogger().println("[sedstart] Streaming cloud execution logs…");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

            SedStartSseRenderer renderer = new SedStartSseRenderer(listener);

            String line;
            StringBuilder eventData = new StringBuilder();

            while ((line = reader.readLine()) != null) {

                // Empty line = end of one SSE event
                if (line.isEmpty()) {
                    if (eventData.length() > 0) {
                        renderer.onEvent(eventData.toString());
                        eventData.setLength(0);
                    }
                    continue;
                }

                // SSE payload lines
                if (line.startsWith("data:")) {
                    if (eventData.length() > 0) {
                        eventData.append(' ');
                    }
                    eventData.append(line.substring(5).trim());
                }
            }

            // Final flush (in case stream ends without blank line)
            if (eventData.length() > 0) {
                renderer.onEvent(eventData.toString());
            }

            renderer.onComplete();
        }
    }

    private static HttpClient createHttpClient() {
        HttpClient.Builder builder = ProxyConfiguration.newHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(30));

        return builder.build();
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
