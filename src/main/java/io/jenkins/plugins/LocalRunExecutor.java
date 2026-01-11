package io.jenkins.plugins;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
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

        String apiKey = env.get("SEDSTART_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("SEDSTART_API_KEY is not set. Use Jenkins credentials binding.");
        }

        // ----------------------------------------------------
        // Prepare environment for agent-side scripts
        // ----------------------------------------------------
        env.put("SEDSTART_API_KEY", apiKey);
        env.put("SEDSTART_PROJECT_ID", projectId.toString());
        env.put("SEDSTART_PROFILE_ID", profileId.toString());
        env.put("SEDSTART_BROWSER", browser == null ? "chrome" : browser);
        env.put("SEDSTART_HEADLESS", Boolean.toString(headless));
        env.put("SEDSTART_ENV", environment == null ? "PROD" : environment);

        boolean isUnix = launcher.isUnix();

        // ----------------------------------------------------
        // 1. Install sedstart on AGENT
        // ----------------------------------------------------
        List<String> installCmd;

        if (isUnix) {
            installCmd = List.of("bash", "-c", """
                            set -euo pipefail

                            OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
                            ARCH="$(uname -m)"

                            case "$OS" in
                              linux) PLATFORM_OS=linux ;;
                              darwin) PLATFORM_OS=darwin ;;
                              *) echo "Unsupported OS: $OS"; exit 1 ;;
                            esac

                            case "$ARCH" in
                              x86_64|amd64) PLATFORM_ARCH=amd64 ;;
                              arm64|aarch64) PLATFORM_ARCH=arm64 ;;
                              i386|i686) PLATFORM_ARCH=386 ;;
                              *) echo "Unsupported ARCH: $ARCH"; exit 1 ;;
                            esac

                            case "$PLATFORM_OS/$PLATFORM_ARCH" in
                              linux/amd64|darwin/amd64) VARIANT=v1 ;;
                              linux/arm64|darwin/arm64) VARIANT=v8.0 ;;
                              linux/386) VARIANT=sse2 ;;
                              *) echo "No variant for $PLATFORM_OS/$PLATFORM_ARCH"; exit 1 ;;
                            esac

                            mkdir -p .sedstart

                            if [ "$SEDSTART_ENV" = "QA" ]; then
                              BASE_URL="https://cli.sedinqa.com/latest"
                              API_URL="https://sedstart.sedinqa.com/api"
                            else
                              BASE_URL="https://cli.sedstart.com/latest"
                              API_URL="https://app.sedstart.com/api"
                            fi

                            BIN=".sedstart/sedstart"
                            URL="$BASE_URL/cli_${PLATFORM_OS}_${PLATFORM_ARCH}_${VARIANT}/sedstart"

                            echo "Downloading $URL"
                            curl -fL "$URL" -o "$BIN"
                            chmod +x "$BIN"

                            cat > .sedstart/default.env <<EOF
                            url=$API_URL
                            key=$SEDSTART_API_KEY
                            project=$SEDSTART_PROJECT_ID
                            profile=$SEDSTART_PROFILE_ID
                            headless=$SEDSTART_HEADLESS
                            EOF
                            """);
        } else {
            installCmd = List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", """
                            $ErrorActionPreference = "Stop"

                            $os = "windows"
                            if ($Env:PROCESSOR_ARCHITECTURE -match "ARM64") { $arch = "arm64" }
                            elseif ($Env:PROCESSOR_ARCHITECTURE -match "AMD64") { $arch = "amd64" }
                            else { $arch = "386" }

                            switch ("$os/$arch") {
                              "windows/amd64" { $variant = "v1" }
                              "windows/arm64" { $variant = "v8.0" }
                              "windows/386"   { $variant = "sse2" }
                              default { throw "Unsupported $os/$arch" }
                            }

                            New-Item -ItemType Directory -Force .sedstart | Out-Null

                            if ($Env:SEDSTART_ENV -eq "QA") {
                              $base = "https://cli.sedinqa.com/latest"
                              $api  = "https://sedstart.sedinqa.com/api"
                            } else {
                              $base = "https://cli.sedstart.com/latest"
                              $api  = "https://app.sedstart.com/api"
                            }

                            $url = "$base/cli_${os}_${arch}_${variant}/sedstart.exe"
                            Invoke-WebRequest -Uri $url -OutFile ".sedstart\\sedstart.exe"

                            @(
                              "url=$api",
                              "key=$Env:SEDSTART_API_KEY",
                              "project=$Env:SEDSTART_PROJECT_ID",
                              "profile=$Env:SEDSTART_PROFILE_ID",
                              "headless=$Env:SEDSTART_HEADLESS"
                            ) | Set-Content ".sedstart\\default.env"
                            """);
        }

        Launcher.ProcStarter installPs = launcher.launch();
        installPs.cmds(installCmd);
        installPs.envs(env);
        installPs.pwd(workspace);
        installPs.stdout(listener.getLogger());
        installPs.stderr(listener.getLogger());

        if (installPs.join() != 0) {
            throw new IOException("sedstart installation failed");
        }

        // ----------------------------------------------------
        // 2. Run sedstart on AGENT
        // ----------------------------------------------------
        Launcher.ProcStarter runPs = launcher.launch();

        if (isUnix) {
            String cmd = "cd .sedstart && "
                    + (!headless ? "xvfb-run -a " : "")
                    + "./sedstart run "
                    + "--browser " + env.get("SEDSTART_BROWSER") + " "
                    + "--project " + projectId + " "
                    + "--data " + profileId + " "
                    + (testId != null ? "--test " + testId : "--suite " + suiteId);

            runPs.cmds("bash", "-c", cmd);
        } else {
            String cmd = "cd .sedstart; "
                    + ".\\sedstart.exe run "
                    + "--browser " + env.get("SEDSTART_BROWSER") + " "
                    + "--project " + projectId + " "
                    + "--data " + profileId + " "
                    + (testId != null ? "--test " + testId : "--suite " + suiteId);

            runPs.cmds("powershell", "-NoProfile", "-NonInteractive", "-Command", cmd);
        }

        runPs.envs(env);
        runPs.pwd(workspace);
        runPs.stdout(listener.getLogger());
        runPs.stderr(listener.getLogger());

        if (runPs.join() != 0) {
            throw new IOException("sedstart CLI failed");
        }
    }

    //
    //    private static String resolveApiUrl(String environment) {
    //        if ("QA".equalsIgnoreCase(environment)) {
    //            return "https://sedstart.sedinqa.com/api";
    //        }
    //        return "https://app.sedstart.com/api";
    //    }

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
