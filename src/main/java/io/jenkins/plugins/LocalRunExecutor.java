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
            String environment)
            throws IOException, InterruptedException {

        validate(projectId, suiteId, testId, profileId);

        String apiKey = env.get(API_KEY_ENV);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("SEDSTART_API_KEY is not set. Use Jenkins credentials binding.");
        }
        List<String> cmd = new ArrayList<>();

        cmd.add("sedstart");
        cmd.add("run");

        cmd.add("--key");
        cmd.add(apiKey);

        cmd.add("--url");
        cmd.add(resolveApiUrl(environment));

        cmd.add("--project");
        cmd.add(projectId.toString());

        cmd.add("--data");
        cmd.add(profileId.toString());

        if (browser != null && !browser.isEmpty()) {
            cmd.add("--browser");
            cmd.add(browser);
        }

        if (testId != null) {
            cmd.add("--test");
            cmd.add(testId.toString());
        } else {
            cmd.add("--suite");
            cmd.add(suiteId.toString());
        }

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
