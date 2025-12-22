// src/main/java/com/sedstart/LocalRunBuilder.java
package io.jenkins.plugins;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LocalRunBuilder (download + run CLI variant)
 *
 */
public class LocalRunBuilder extends Builder implements SimpleBuildStep {
    private static final Logger LOGGER = Logger.getLogger(LocalRunBuilder.class.getName());
    private final String name;

    // CLI parameters
    private Integer projectId;
    private Integer suiteId;
    private Integer testId;
    private Integer profileId;
    private String browser = "chrome";
    private boolean headless = false;
    private String environment = "PROD";
    // lgtm[jenkins/plaintext-storage]
    private String apiKeyEnvName = "SEDSTART_API_KEY";

    @DataBoundConstructor
    public LocalRunBuilder(String name) {
        this.name = name;
    }

    public String getName() { return name; }

    public String getBrowser() { return browser; }
    @DataBoundSetter
    public void setBrowser(String browser) { this.browser = browser; }

    public boolean isHeadless() { return headless; }
    @DataBoundSetter
    public void setHeadless(boolean headless) { this.headless = headless; }

    public String getEnvironment() { return environment; }
    @DataBoundSetter
    public void setEnvironment(String environment) { this.environment = environment; }

    public Integer getProjectId() { return projectId; }
    @DataBoundSetter
    public void setProjectId(Integer projectId) { this.projectId = projectId; }

    public String getApiKeyEnvName() { return apiKeyEnvName; }
    @DataBoundSetter public void setApiKeyEnvName(String apiKeyEnvName) { this.apiKeyEnvName = apiKeyEnvName; }

    public Integer getSuiteId() { return suiteId; }
    @DataBoundSetter
    public void setSuiteId(Integer suiteId) { this.suiteId = suiteId; }

    public Integer getTestId() { return testId; }
    @DataBoundSetter
    public void setTestId(Integer testId) { this.testId = testId; }

    public Integer getProfileId() { return profileId; }
    @DataBoundSetter
    public void setProfileId(Integer profileId) { this.profileId = profileId; }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {

        // Basic validation
        if (projectId == null) throw new IOException("projectId is required");
        if ((suiteId == null && testId == null) || (suiteId != null && testId != null)) {
            throw new IOException("Exactly one of suiteId or testId must be provided");
        }
        if (profileId == null) throw new IOException("profileId is required");

        // Read API key from environment variable only
        String effectiveApiKey = (env != null) ? env.get(apiKeyEnvName) : null;
        if (effectiveApiKey == null || effectiveApiKey.isEmpty()) {
            listener.getLogger().println("[sedstart] ERROR: environment variable "+apiKeyEnvName+" is not set. Aborting.");
            throw new IOException(apiKeyEnvName+" environment variable is required");
        }

        // decide environment
        String effectiveEnv = (environment != null && !environment.trim().isEmpty())
                ? environment.trim()
                : (env != null ? env.get("SEDSTART_ENV", "PROD") : "PROD");
        effectiveEnv = effectiveEnv.toUpperCase();

        String downloadHost;
        String sedstartUrl;
        if ("QA".equals(effectiveEnv)) {
            downloadHost = "cli.sedinqa.com";
            sedstartUrl = "https://sedstart.sedinqa.com/api";
        } else {
            downloadHost = "cli.sedstart.com";
            sedstartUrl = "https://app.sedstart.com/api";
        }

        listener.getLogger().println("[sedstart] Environment: " + effectiveEnv);
        listener.getLogger().println("[sedstart] Download host: " + downloadHost);
        listener.getLogger().println("[sedstart] SedStart API URL: " + sedstartUrl);

        // Detect platform & arch
        String osName = System.getProperty("os.name", "unknown").toLowerCase();
        String archName = System.getProperty("os.arch", "unknown").toLowerCase();
        String platformOs;
        if (osName.contains("linux")) platformOs = "linux";
        else if (osName.contains("mac") || osName.contains("darwin") || osName.contains("os x")) platformOs = "darwin";
        else if (osName.contains("windows") || osName.contains("win")) platformOs = "windows";
        else throw new IOException("Unsupported OS: " + osName);

        String platformArch;
        if (archName.contains("x86_64") || archName.contains("amd64")) platformArch = "amd64";
        else if (archName.contains("aarch64") || archName.contains("arm64")) platformArch = "arm64";
        else if (archName.equals("x86") || archName.equals("i386") || archName.equals("i686")) platformArch = "386";
        else throw new IOException("Unsupported ARCH: " + archName);

        listener.getLogger().println("[sedstart] Detected platform: " + platformOs + "/" + platformArch);

        String variant = mapVariant(platformOs, platformArch, listener);
        if (variant == null) throw new IOException("No variant mapping for " + platformOs + "/" + platformArch);

        String cliDir = String.format("cli_%s_%s_%s", platformOs, platformArch, variant);
        String binaryName = platformOs.equals("windows") ? "sedstart.exe" : "sedstart";
        String downloadUrl = String.format("https://%s/latest/%s/%s", downloadHost, cliDir, binaryName);

        listener.getLogger().println("[sedstart] CLI dir: " + cliDir);
        listener.getLogger().println("[sedstart] Binary name: " + binaryName);
        listener.getLogger().println("[sedstart] Download URL: " + downloadUrl);

        // .sedstart directory under user's home
        FilePath homeDir = new FilePath(new File(System.getProperty("user.home")));
        FilePath sedstartHome = homeDir.child(".sedstart");
        sedstartHome.mkdirs();

        FilePath binaryPath = sedstartHome.child(binaryName);

        // Download binary if missing
        try {
            downloadFile(downloadUrl, binaryPath, listener);
        } catch (IOException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while checking/downloading binary", ie);
        }
        // Make executable if not windows
        if (!platformOs.equals("windows")) {
            try {
                binaryPath.chmod(0755);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while chmod-ing binary", ie);
            }
        }

        // Create default.env in the same directory as the binary and populate values
        FilePath envFile = sedstartHome.child("default.env");
        StringBuilder envContent = new StringBuilder();
        envContent.append("browser=").append(browser).append('\n');
        envContent.append("url=").append(sedstartUrl).append('\n');
        envContent.append("key=").append(effectiveApiKey).append('\n');
        envContent.append("project=").append(projectId).append('\n');
        envContent.append("profile=").append(profileId).append('\n');

        try {
            envFile.write(envContent.toString(), "UTF-8");
            // restrict permissions when possible
            if (!platformOs.equals("windows")) {
                try { envFile.chmod(0600); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException("Interrupted while chmod-ing env file", ie); }
            }
            listener.getLogger().println("[sedstart] Written env file: " + envFile.getRemote());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while writing default.env", ie);
        }

        // Build CLI args
        List<String> cmd;
        if (testId != null && testId != 0) {
            cmd = Arrays.asList(
                    binaryPath.getRemote(),
                    "run",
                    "-b", browser,
                    "-t", testId.toString(),
                    "-e", "default.env"
            );
        } else {
            cmd = Arrays.asList(
                    binaryPath.getRemote(),
                    "run",
                    "-b", browser,
                    "-s", suiteId.toString(),
                    "-e", "default.env"
            );
        }

        // Launch the process
        Launcher.ProcStarter ps = launcher.launch();
        ps.cmds(cmd);
        ps.stdout(listener.getLogger());
        ps.stderr(listener.getLogger());

        Map<String, String> procEnv = new HashMap<>();
        if (env != null) procEnv.putAll(env);
        procEnv.put(apiKeyEnvName, effectiveApiKey);
        try {
            ps.envs(procEnv);
        } catch (Exception ex) {
            LOGGER.log(Level.FINE, "ps.envs() failed, continuing without explicit env map: " + ex.getMessage(), ex);
        }

        // Important: set working directory to the .sedstart directory so `-e default.env` (relative) is found by the binary.
        try {
            ps.pwd(sedstartHome);
        } catch (Exception ex) {
            LOGGER.log(Level.FINE, "ps.pwd() failed, continuing without changing CWD: " + ex.getMessage(), ex);
        }

        int exitCode;
        try {
            exitCode = ps.join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running sedstart CLI", ie);
        }

        if (exitCode != 0) {
            listener.getLogger().println("[sedstart] CLI exited with code " + exitCode);
            throw new IOException("sedstart CLI failed with exit code: " + exitCode);
        } else {
            listener.getLogger().println("[sedstart] CLI completed successfully (exit code 0).");
        }
    }

    private static String mapVariant(String os, String arch, TaskListener listener) {
        switch (os + "/" + arch) {
            case "darwin/amd64":  return "v1";
            case "darwin/arm64":  return "v8.0";
            case "linux/amd64":   return "v1";
            case "linux/arm64":   return "v8.0";
            case "linux/386":     return "sse2";
            case "windows/amd64": return "v1";
            case "windows/arm64": return "v8.0";
            case "windows/386":   return "sse2";
            default:
                if (listener != null) listener.getLogger().println("[sedstart] No variant mapping for " + os + "/" + arch);
                return null;
        }
    }

    private static void downloadFile(String urlStr, FilePath dst, TaskListener listener) throws IOException {
        listener.getLogger().println("[sedstart] Downloading: " + urlStr + " -> " + dst.getRemote());
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);

            int status = conn.getResponseCode();
            if (status >= 300 && status < 400) {
                String newLoc = conn.getHeaderField("Location");
                if (newLoc != null && !newLoc.isEmpty()) {
                    listener.getLogger().println("[sedstart] Redirected to: " + newLoc);
                    conn.disconnect();
                    url = new URL(newLoc);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(15_000);
                    conn.setReadTimeout(60_000);
                    status = conn.getResponseCode();
                }
            }

            if (status >= 400) {
                InputStream err = conn.getErrorStream();
                String errBody = "";
                if (err != null) try (BufferedReader br = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    errBody = sb.toString();
                }
                listener.getLogger().println("[sedstart] Failed to download binary (HTTP " + status + "): " + errBody);
                throw new IOException("Failed to download binary: HTTP " + status);
            }

            try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                try (OutputStream os = dst.write()) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while writing binary", ie);
                }
            }

        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) { return true; }
//        @Override
        // public String getDisplayName() { return Messages.SedStartBuilder_DescriptorImpl_DisplayName(); }

    }
}