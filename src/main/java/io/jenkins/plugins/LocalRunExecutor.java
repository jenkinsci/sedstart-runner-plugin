package io.jenkins.plugins;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class LocalRunExecutor {

    private static final String API_KEY_ENV = "SEDSTART_API_KEY";
    private static final String DIR_NAME = ".sedstart";
    private static final String BIN_NAME = "sedstart";

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
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("SEDSTART_API_KEY is not set. Use Jenkins credentials binding.");
        }

        // -----------------------------
        // Prepare .sedstart directory
        // -----------------------------
        FilePath sedstartDir = workspace.child(DIR_NAME);
        sedstartDir.mkdirs();

        FilePath binary = sedstartDir.child(detectOs(launcher).equals("windows") ? "sedstart.exe" : BIN_NAME);
        FilePath envFile = sedstartDir.child("default.env");

        if (!envFile.exists()) {
            envFile.touch(0);
        }

        if (!binary.exists()) {
            downloadBinary(binary, launcher, environment, listener);
            chmodIfNeeded(binary, launcher);
        }

        // -----------------------------
        // Build command
        // -----------------------------
        List<String> cmd = new ArrayList<>();

        cmd.add(binary.getRemote());
        cmd.add("run");

        cmd.add("-k");
        cmd.add(apiKey);

        cmd.add("-u");
        cmd.add(resolveApiUrl(environment));

        cmd.add("-p");
        cmd.add(projectId.toString());

        cmd.add("-d");
        cmd.add(profileId.toString());

        cmd.add("-b");
        cmd.add(browser);

        if (headless) {
            cmd.add("-q");
        }

        if (testId != null) {
            cmd.add("-t");
            cmd.add(testId.toString());
        } else {
            cmd.add("-s");
            cmd.add(suiteId.toString());
        }

        cmd.add("-e");
        cmd.add("default.env");

        // -----------------------------
        // Execute on agent
        // -----------------------------
        Launcher.ProcStarter ps = launcher.launch();
        ps.cmds(cmd);
        ps.envs(env);
        ps.pwd(sedstartDir);
        ps.stdout(listener.getLogger());
        ps.stderr(listener.getLogger());

        int exit = ps.join();
        if (exit != 0) {
            throw new IOException("sedstart CLI exited with code " + exit);
        }
    }

    // ------------------------------------------------------

    private static void downloadBinary(
            FilePath target,
            Launcher launcher,
            String environment,
            TaskListener listener
    ) throws IOException, InterruptedException {

        String os = detectOs(launcher);
        String arch = detectArch(launcher);

        String platform = resolvePlatform(os, arch);
        String baseUrl = resolveCliBaseUrl(environment);

        String url = baseUrl + "/" + platform + "/sedstart";

        listener.getLogger().println("Downloading sedstart CLI from: " + url);

        List<String> cmd = new ArrayList<>();
        if (os.contains("win")) {
            cmd.add("powershell");
            cmd.add("-Command");
            cmd.add("Invoke-WebRequest -Uri '" + url + "' -OutFile '" + target.getRemote() + "'");
        } else {
            cmd.add("curl");
            cmd.add("-fL");
            cmd.add(url);
            cmd.add("-o");
            cmd.add(target.getRemote());
        }

        int exit = launcher.launch().cmds(cmd).join();
        if (exit != 0) {
            throw new IOException("Failed to download sedstart CLI");
        }
    }

    private static void chmodIfNeeded(FilePath binary, Launcher launcher)
            throws IOException, InterruptedException {

        if (detectOs(launcher).equals("windows")) {
            return;
        }

        launcher.launch()
                .cmds("chmod", "+x", binary.getRemote())
                .join();
    }


    private static String detectOs(Launcher launcher) throws IOException, InterruptedException {
        if (!launcher.isUnix()) {
            return "windows";
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        launcher.launch()
                .cmds("uname", "-s")
                .stdout(out)
                .join();

        String os = out.toString().trim().toLowerCase();
        if (os.contains("linux")) return "linux";
        if (os.contains("darwin")) return "darwin";

        throw new IOException("Unsupported OS: " + os);
    }

    private static String detectArch(Launcher launcher) throws IOException, InterruptedException {
        if (!launcher.isUnix()) {
            return "amd64"; // windows default, refine if needed
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        launcher.launch()
                .cmds("uname", "-m")
                .stdout(out)
                .join();

        String arch = out.toString().trim().toLowerCase();
        if (arch.contains("arm") || arch.contains("aarch64")) return "arm64";
        if (arch.contains("64")) return "amd64";
        if (arch.contains("86")) return "386";

        throw new IOException("Unsupported ARCH: " + arch);
    }

    // ------------------------------------------------------

    private static String resolveCliBaseUrl(String env) {
        return "QA".equalsIgnoreCase(env)
                ? "http://cli.sedinqa.com/latest"
                : "http://cli.sedstart.com/latest";
    }

    private static String resolveApiUrl(String env) {
        return "QA".equalsIgnoreCase(env)
                ? "https://sedstart.sedinqa.com/api"
                : "https://app.sedstart.com/api";
    }

    private static String resolvePlatform(String os, String arch) throws IOException {

        if (os.contains("mac")) {
            return arch.contains("aarch64") || arch.contains("arm")
                    ? "cli_darwin_arm64_v8.0"
                    : "cli_darwin_amd64_v1";
        }

        if (os.contains("linux")) {
            return arch.contains("aarch64") || arch.contains("arm")
                    ? "cli_linux_arm64_v8.0"
                    : "cli_linux_amd64_v1";
        }

        if (os.contains("win")) {
            return arch.contains("arm")
                    ? "cli_windows_arm64_v8.0"
                    : "cli_windows_amd64_v1";
        }

        throw new IOException("Unsupported OS/arch: " + os + " / " + arch);
    }

    private static void validate(
            Integer projectId,
            Integer suiteId,
            Integer testId,
            Integer profileId
    ) throws IOException {

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
