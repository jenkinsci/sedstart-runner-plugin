package io.jenkins.plugins;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class LocalRunExecutor {

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
    ) throws IOException, InterruptedException {

        validate(projectId, suiteId, testId, profileId);

        String apiKey = env.get(apiKeyEnvName);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException(apiKeyEnvName + " environment variable is required");
        }

        List<String> cmd = new ArrayList<>();

        cmd.add("sedstart");
        cmd.add("run");

        // API key
        cmd.add("--key");
        cmd.add(apiKey);

        // API URL (based on environment)
        cmd.add("--url");
        cmd.add(resolveApiUrl(environment));

        // Project + profile
        cmd.add("--project");
        cmd.add(projectId.toString());

        cmd.add("--data"); // profile id flag
        cmd.add(profileId.toString());

        // Browser
        if (browser != null && !browser.isEmpty()) {
            cmd.add("--browser");
            cmd.add(browser);
        }

        // Headless
//        if (headless) {
//            cmd.add("--headless");
//        }

        // Test or suite
        if (testId != null) {
            cmd.add("--test");
            cmd.add(testId.toString());
        } else {
            cmd.add("--suite");
            cmd.add(suiteId.toString());
        }

//        listener.getLogger().println(
//                "[sedstart] Running local CLI: " + String.join(" ", maskApiKey(cmd))
//        );

        Launcher.ProcStarter ps = launcher.launch();
        ps.cmds(cmd);
        ps.envs(env);
        ps.stdout(listener.getLogger());
        ps.stderr(listener.getLogger());

        int exit = ps.join();
        if (exit != 0) {
            throw new IOException("sedstart CLI exited with code " + exit);
        }
    }

    private static String resolveApiUrl(String environment) {
        if ("QA".equalsIgnoreCase(environment)) {
            return "https://sedstart.sedinqa.com/api";
        }
        return "https://app.sedstart.com/api";
    }

    private static List<String> maskApiKey(List<String> cmd) {
        List<String> masked = new ArrayList<>(cmd.size());
        for (int i = 0; i < cmd.size(); i++) {
            if ("--key".equals(cmd.get(i)) && i + 1 < cmd.size()) {
                masked.add("--key");
                masked.add("********");
                i++;
            } else {
                masked.add(cmd.get(i));
            }
        }
        return masked;
    }

    private static void validate(Integer projectId, Integer suiteId, Integer testId, Integer profileId)
            throws IOException {

        if (projectId == null) {
            throw new IOException("projectId is required");
        }
        if (profileId == null) {
            throw new IOException("profileId is required");
        }
        if ((suiteId == null && testId == null) || (suiteId != null && testId != null)) {
            throw new IOException("Provide exactly one of suiteId or testId");
        }
    }
}
