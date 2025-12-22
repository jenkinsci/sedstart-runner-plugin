package io.jenkins.plugins;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.IOException;

public class SedStartBuilder extends Builder implements SimpleBuildStep {
    public enum Mode { CLOUD, LOCAL }

    private final Mode mode;
    private final String name;

    // local-run
    private Integer projectId;
    private Integer suiteId;
    private Integer testId;
    private Integer profileId;
    private String browser = "chrome";
    // lgtm[jenkins/plaintext-storage]
    private String apiKeyEnvName = "SEDSTART_API_KEY";
    private boolean headless = false;
    private String environment = "PROD";

    @DataBoundConstructor
    public SedStartBuilder(Mode mode, String name) {
        this.mode = (mode == null) ? Mode.CLOUD : mode;
        this.name = name;
    }

    public Mode getMode() { return mode; }
    public String getName() { return name; }

    // getters + setters for local-run fields
    public Integer getProjectId() { return projectId; }
    @DataBoundSetter public void setProjectId(Integer projectId) { this.projectId = projectId; }

    public Integer getSuiteId() { return suiteId; }
    @DataBoundSetter public void setSuiteId(Integer suiteId) { this.suiteId = suiteId; }

    public Integer getTestId() { return testId; }
    @DataBoundSetter public void setTestId(Integer testId) { this.testId = testId; }

    public Integer getProfileId() { return profileId; }
    @DataBoundSetter public void setProfileId(Integer profileId) { this.profileId = profileId; }

    public String getBrowser() { return browser; }
    @DataBoundSetter public void setBrowser(String browser) { this.browser = browser; }

    public String getApiKeyEnvName() { return apiKeyEnvName; }
    @DataBoundSetter public void setApiKeyEnvName(String apiKeyEnvName) { this.apiKeyEnvName = apiKeyEnvName; }

    public boolean isHeadless() { return headless; }
    @DataBoundSetter public void setHeadless(boolean headless) { this.headless = headless; }

    public String getEnvironment() { return environment; }
    @DataBoundSetter public void setEnvironment(String environment) { this.environment = environment; }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, EnvVars env, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {
        if (mode == Mode.CLOUD) {
            listener.getLogger().println("CloudRunBuilder behavior: " + (name == null ? "" : name));
            CloudRunBuilder cloud = new CloudRunBuilder(name);

            cloud.setProjectId(this.projectId);
            cloud.setSuiteId(this.suiteId);
            cloud.setTestId(this.testId);
            cloud.setProfileId(this.profileId);
            cloud.setBrowser(this.browser);
            cloud.setHeadless(this.headless);
            cloud.setEnvironment(this.environment);
            cloud.setApiKeyEnvName(this.apiKeyEnvName);

            // delegate synchronously to the CloudRunBuilder implementation
            cloud.perform(run, workspace, env, launcher, listener);
            return;
        }

        // LOCAL mode: validate and run CLI (same logic you already have)
        if (projectId == null) throw new IOException("projectId is required");
        if ((suiteId == null && testId == null) || (suiteId != null && testId != null)) {
            throw new IOException("Exactly one of suiteId or testId must be provided");
        }
        if (profileId == null) throw new IOException("profileId is required");

        listener.getLogger().println("[sedstart] Running Local mode for project " + projectId);
        LocalRunBuilder delegate = new LocalRunBuilder(name);

        delegate.setProjectId(projectId);
        delegate.setSuiteId(suiteId);
        delegate.setTestId(testId);
        delegate.setProfileId(profileId);
        delegate.setBrowser(browser);
        delegate.setHeadless(headless);
        delegate.setEnvironment(environment);
        delegate.setApiKeyEnvName(apiKeyEnvName);
        delegate.perform(run, workspace, env, launcher, listener);
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Override public boolean isApplicable(Class jobType) { return true; }
        @Override public String getDisplayName() { return "SedStart Runner"; }
        public ListBoxModel doFillModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Local", "LOCAL");
            items.add("Cloud", "CLOUD");
            return items;
        }
    }
}