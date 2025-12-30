package io.jenkins.plugins;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;

import java.io.IOException;
import java.util.Arrays;
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

        List<String> cmd = (testId != null)
                ? Arrays.asList("sedstart", "run", "-t", testId.toString())
                : Arrays.asList("sedstart", "run", "-s", suiteId.toString());

        listener.getLogger().println("[sedstart] Running local CLI: " + String.join(" ", cmd));

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

    private static void validate(Integer projectId, Integer suiteId, Integer testId, Integer profileId)
            throws IOException {

        if (projectId == null) throw new IOException("projectId is required");
        if (profileId == null) throw new IOException("profileId is required");
        if ((suiteId == null && testId == null) || (suiteId != null && testId != null)) {
            throw new IOException("Provide exactly one of suiteId or testId");
        }
    }
}
