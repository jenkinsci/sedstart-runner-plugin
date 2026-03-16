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

                echo "Installing Sedstart via universal installer"
                SEDSTART_HOME="$HOME/.sedstart"
                mkdir -p "$SEDSTART_HOME"

                if [ "$SEDSTART_ENV" = "QA" ]; then
                  curl -fsSL https://raw.githubusercontent.com/sedstart/setup/main/install.sh | bash -s -- --base-url https://cli.sedinqa.com/latest
                  API_URL="https://sedstart.sedinqa.com/api"
                else
                  curl -fsSL https://raw.githubusercontent.com/sedstart/setup/main/install.sh | bash
                  API_URL="https://app.sedstart.com/api"
                fi

                {
                  echo "url=$API_URL"
                  echo "key=$SEDSTART_API_KEY"
                } > "$SEDSTART_HOME/default.env"
            """);

        } else {
            FilePath ps1 = workspace.child("install-sedstart.ps1");
            ps1.write("""
                $ErrorActionPreference = "Stop"
                Write-Host "Installing Sedstart via universal installer"

                $userHome = $Env:USERPROFILE
                $sedstartHome = Join-Path $userHome ".sedstart"
                New-Item -ItemType Directory -Force $sedstartHome | Out-Null

                if ($Env:SEDSTART_ENV -eq "QA") {
                  iex "& { $(iwr https://raw.githubusercontent.com/sedstart/setup/main/install.ps1 -UseBasicParsing) } -BaseUrl 'https://cli.sedinqa.com/latest'"
                  $api  = "https://sedstart.sedinqa.com/api"
                } else {
                  iwr https://raw.githubusercontent.com/sedstart/setup/main/install.ps1 -UseBasicParsing | iex
                  $api  = "https://app.sedstart.com/api"
                }

                $env:PATH += ";$env:LOCALAPPDATA\\Programs\\sedstart"
                @(
                  "url=$api"
                  "key=$Env:SEDSTART_API_KEY"
                ) | Set-Content (Join-Path $sedstartHome "default.env")
            """, "UTF-8");

            installCmd = List.of(
                    "powershell",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    ps1.getRemote());
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
                    "CMD=\"sedstart run "
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
            String cmd = "$env:SEDSTART_HOME = Join-Path $Env:USERPROFILE '.sedstart'; " + "cd $env:SEDSTART_HOME; "
                    + "sedstart run "
                    + "-b "
                    + env.get("SEDSTART_BROWSER") + " " + "-p "
                    + projectId + " " + "-d "
                    + profileId + " " + (testId != null ? "-t " + testId : "-s " + suiteId)
                    + " " + (headless ? "-q " : "")
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
