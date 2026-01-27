package io.jenkins.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.TaskListener;

final class SedStartSseRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TaskListener listener;

    private String finalStatus;

    SedStartSseRenderer(TaskListener listener) {
        this.listener = listener;
    }

    void onEvent(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode result = root.path("result");
            if (result.isMissingNode()) return;

            String type = result.path("type").asText();

            if ("Test".equalsIgnoreCase(type)) {
                renderTestSnapshot(result);
            } else if ("Run".equalsIgnoreCase(type)) {
                renderRun(result);
            }

        } catch (Exception e) {
            listener.getLogger().println("[sedstart] " + json);
        }
    }

    private void renderTestSnapshot(JsonNode test) {
        String name = test.path("name").asText("<unnamed>");
        String status = test.path("status").asText("UNKNOWN");

        listener.getLogger().println("🧪 Test: " + name + " — " + status);

        JsonNode items = test.path("items");
        if (items.isArray()) {
            for (JsonNode step : items) {
                renderTestStep(step);
            }
        }
    }

    private void renderTestStep(JsonNode step) {
        String name = step.path("name").asText("TestStep");
        String status = step.path("status").asText("UNKNOWN");

        listener.getLogger().println("  • " + name + " — " + status);

        JsonNode stepItems = step.path("items");
        if (stepItems.isArray()) {
            for (JsonNode stepItem : stepItems) {
                renderStepItem(stepItem);
            }
        }
    }

    private void renderStepItem(JsonNode item) {
        String name = item.path("name").asText("StepItem");
        String status = item.path("status").asText("UNKNOWN");

        listener.getLogger().println("    - " + name + " — " + status);

        JsonNode actions = item.path("items");
        if (actions.isArray()) {
            for (JsonNode action : actions) {
                renderAction(action);
            }
        }
    }

    private void renderAction(JsonNode action) {
        String name = action.path("name").asText("Action");
        String status = action.path("status").asText("UNKNOWN");

        listener.getLogger().println("      → " + name + " — " + status);
    }

    private void renderRun(JsonNode run) {
        String status = run.path("status").asText("");

        if (!status.isEmpty()) {
            listener.getLogger().println(icon(status) + " " + status);
            if ("PASS".equalsIgnoreCase(status) || "FAIL".equalsIgnoreCase(status)) {
                finalStatus = status;
            }
        }

        JsonNode video = run.path("video");
        if (video.isArray()) {
            for (JsonNode v : video) {
                String url = v.asText();
                if (!url.isEmpty()) {
                    listener.getLogger().println("🎥 " + url);
                }
            }
        }
    }

    boolean isFailed() {
        return "FAIL".equalsIgnoreCase(finalStatus);
    }

    void onComplete() {
        if ("PASS".equalsIgnoreCase(finalStatus)) {
            listener.getLogger().println("✅ PASS");
        } else if ("FAIL".equalsIgnoreCase(finalStatus)) {
            listener.getLogger().println("❌ FAIL");
        }
    }

    private static String icon(String status) {
        switch (status.toUpperCase()) {
            case "PASS":
                return "✅";
            case "FAIL":
                return "❌";
            case "IN_PROGRESS":
                return "⏳";
            default:
                return "ℹ️";
        }
    }
}
