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

        log(listener, "Starting local execution");

        log(listener, "Validating inputs");
        validate(projectId, suiteId, testId, profileId);

        String apiKey = env.get(API_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("SEDSTART_API_KEY is not set. Use Jenkins credentials binding.");
        }

        // -----------------------------
        // Prepare .sedstart directory
        // -----------------------------
        log(listener, "Preparing .sedstart directory");
        FilePath sedstartDir = workspace.child(DIR_NAME);
        sedstartDir.mkdirs();

        String os = detectOs(launcher, listener);
        String arch = detectArch(launcher, listener);

        log(listener, "Platform resolved: " + os + " / " + arch);

        FilePath binary = sedstartDir.child(os.equals("windows") ? "sedstart.exe" : BIN_NAME);
        FilePath envFile = sedstartDir.child("default.env");

        if (!envFile.exists()) {
            log(listener, "Creating default.env");
            envFile.touch(0);
        }

        if (!binary.exists()) {
            log(listener, "sedstart binary not found, downloading");
            downloadBinary(binary, launcher, environment, os, arch, listener);
            chmodIfNeeded(binary, launcher, os, listener);
        } else {
            log(listener, "sedstart binary already present, reusing");
        }

        // -----------------------------
        // Build command
        // -----------------------------
        log(listener, "Building sedstart command");

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
        log(listener, "Executing sedstart CLI");

        Launcher.ProcStarter ps = launcher.launch();
        ps.cmds(cmd);
        ps.envs(env);
        ps.pwd(sedstartDir);
        ps.stdout(listener.getLogger());
        ps.stderr(listener.getLogger());

        int exit = ps.join();
        log(listener, "sedstart finished with exit code " + exit);

        if (exit != 0) {
            throw new IOException("sedstart CLI exited with code " + exit);
        }
    }

    // ------------------------------------------------------

    private static void downloadBinary(
            FilePath target,
            Launcher launcher,
            String environment,
            String os,
            String arch,
            TaskListener listener
    ) throws IOException, InterruptedException {

        String platform = resolvePlatform(os, arch);
        String baseUrl = resolveCliBaseUrl(environment);
        String url = baseUrl + "/" + platform + "/sedstart";

        log(listener, "Downloading sedstart from: " + url);

        List<String> cmd = new ArrayList<>();
        if (os.equals("windows")) {
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

        log(listener, "Download completed");
    }

    private static void chmodIfNeeded(
            FilePath binary,
            Launcher launcher,
            String os,
            TaskListener listener
    ) throws IOException, InterruptedException {

        if (os.equals("windows")) {
            return;
        }

        log(listener, "Making sedstart executable");
        launcher.launch()
                .cmds("chmod", "+x", binary.getRemote())
                .join();
    }

    private static String detectOs(Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException {

        log(listener, "Detecting OS");
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

    private static String detectArch(Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException {

        log(listener, "Detecting architecture");
        if (!launcher.isUnix()) {
            return "amd64";
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
            return arch.contains("arm")
                    ? "cli_darwin_arm64_v8.0"
                    : "cli_darwin_amd64_v1";
        }

        if (os.contains("linux")) {
            return arch.contains("arm")
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

    private static void log(TaskListener listener, String msg) {
        listener.getLogger().println("[sedstart] " + msg);
    }
}
