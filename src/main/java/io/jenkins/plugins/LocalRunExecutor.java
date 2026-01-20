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

                SEDSTART_HOME="$HOME/.sedstart"
                BIN="$SEDSTART_HOME/sedstart"

                mkdir -p "$SEDSTART_HOME"

                if [ "$SEDSTART_ENV" = "QA" ]; then
                  BASE_URL="https://cli.sedinqa.com/latest"
                  API_URL="https://sedstart.sedinqa.com/api"
                else
                  BASE_URL="https://cli.sedstart.com/latest"
                  API_URL="https://app.sedstart.com/api"
                fi

                URL="$BASE_URL/cli_${PLATFORM_OS}_${PLATFORM_ARCH}_${VARIANT}/sedstart"

                if [ ! -f "$BIN" ]; then
                  echo "sedstart not found, downloading"
                  curl -fL "$URL" -o "$BIN"
                  chmod +x "$BIN"
                else
                  echo "sedstart already exists, skipping download"
                fi

                {
                  echo "url=$API_URL"
                  echo "key=$SEDSTART_API_KEY"
                } > "$SEDSTART_HOME/default.env"
            """);

        } else {
            installCmd = List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", """
                $ErrorActionPreference = "Stop"

                $home = $Env:USERPROFILE
                $sedstartHome = "$home\\.sedstart"
                $bin = "$sedstartHome\\sedstart.exe"

                New-Item -ItemType Directory -Force $sedstartHome | Out-Null

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

                if ($Env:SEDSTART_ENV -eq "QA") {
                  $base = "https://cli.sedinqa.com/latest"
                  $api  = "https://sedstart.sedinqa.com/api"
                } else {
                  $base = "https://cli.sedstart.com/latest"
                  $api  = "https://app.sedstart.com/api"
                }

                $url = "$base/cli_${os}_${arch}_${variant}/sedstart.exe"

                if (!(Test-Path $bin)) {
                  Invoke-WebRequest -Uri $url -OutFile $bin
                }

                @(
                  "url=$api",
                  "key=$Env:SEDSTART_API_KEY"
                ) | Set-Content "$sedstartHome\\default.env"
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
            String cmd = "SEDSTART_HOME=\"$HOME/.sedstart\"; cd \"$SEDSTART_HOME\" && " +

                    // -------- browser normalization (Linux only) --------
                    "BROWSER=\""
                    + env.get("SEDSTART_BROWSER") + "\"; "
                    + "if [ \"$(uname -s | tr A-Z a-z)\" = \"linux\" ] && [ \"$BROWSER\" = \"chrome\" ]; then "
                    + "  BROWSER=\"chromium\"; "
                    + "fi; "
                    +

                    // -------- build command --------
                    "CMD=\"./sedstart run "
                    + "-b $BROWSER "
                    + "-p "
                    + projectId + " " + "-d "
                    + profileId + " " + (testId != null ? "-t " + testId : "-s " + suiteId)
                    + " " + (headless ? "-q " : "")
                    + "-e default.env\"; "
                    +

                    // -------- headless / headed logic --------
                    "if [ \""
                    + headless + "\" = \"true\" ]; then " + "  echo 'Running HEADLESS'; "
                    + "  eval \"$CMD\"; "
                    + "else "
                    + "  echo 'Running HEADED'; "
                    + "  if [ \"$(uname -s | tr A-Z a-z)\" = \"linux\" ]; then "
                    + "    if [ -z \"${DISPLAY:-}\" ]; then "
                    + "      eval \"$CMD\"; "
                    + "    else "
                    + "      if command -v xvfb-run >/dev/null 2>&1; then "
                    + "        xvfb-run -a eval \"$CMD\"; "
                    + "      else "
                    + "        echo 'ERROR: DISPLAY set but xvfb not available'; exit 1; "
                    + "      fi; "
                    + "    fi; "
                    + "  else "
                    + "    eval \"$CMD\"; "
                    + "  fi; "
                    + "fi";

            runPs.cmds("bash", "-c", cmd);
        } else {
            String cmd = "$env:SEDSTART_HOME=\"$Env:USERPROFILE\\.sedstart\"; "
                    + "cd $env:SEDSTART_HOME; "
                    + ".\\sedstart.exe run "
                    + "-b " + env.get("SEDSTART_BROWSER") + " "
                    + "-p " + projectId + " "
                    + "-d " + profileId + " "
                    + (testId != null ? "-t " + testId : "-s " + suiteId) + " "
                    + (headless ? "-q " : "")
                    + "-e default.env";

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
