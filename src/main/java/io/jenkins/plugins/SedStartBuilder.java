package io.jenkins.plugins;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;

public class SedStartBuilder extends Builder implements SimpleBuildStep {

    public enum Mode { CLOUD, LOCAL }

    private final Mode mode;
    private final String name;

    private Integer projectId;
    private Integer suiteId;
    private Integer testId;
    private Integer profileId;
    private String browser = "chrome";
    private boolean headless = false;
    private String environment = "PROD";
    // lgtm[jenkins/plaintext-storage]

    @DataBoundConstructor
    public SedStartBuilder(Mode mode, String name) {
        this.mode = (mode == null) ? Mode.CLOUD : mode;
        this.name = name;
    }

    public Mode getMode() { return mode; }
    public String getName() { return name; }

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

    public boolean isHeadless() { return headless; }
    @DataBoundSetter public void setHeadless(boolean headless) { this.headless = headless; }

    public String getEnvironment() { return environment; }
    @DataBoundSetter public void setEnvironment(String environment) { this.environment = environment; }


    @Override
    public void perform(
            Run<?, ?> run,
            FilePath workspace,
            EnvVars env,
            Launcher launcher,
            TaskListener listener
    ) throws InterruptedException, IOException {

        if (mode == Mode.CLOUD) {
            new CloudRunExecutor().execute(
                    run, workspace, env, launcher, listener,
                    projectId, suiteId, testId, profileId,
                    browser, headless, environment
            );
        } else {
            new LocalRunExecutor().execute(
                    run, workspace, env, launcher, listener,
                    projectId, suiteId, testId, profileId,
                    browser, headless, environment
            );
        }
    }

    @Extension
    @Symbol("sedStart")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "SedStart Runner";
        }

        public ListBoxModel doFillModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Local", "LOCAL");
            items.add("Cloud", "CLOUD");
            return items;
        }

        /* ------------------
         * Field validations
         * ------------------ */


        @RequirePOST
        public FormValidation doCheckSuiteId(
                @QueryParameter String value,
                @QueryParameter("testId") String testId
        ) {
            Jenkins.get().checkPermission(Item.CONFIGURE);
            return xorNumeric(value, testId, "Suite ID", "Test ID");
        }

        @RequirePOST
        public FormValidation doCheckTestId(
                @QueryParameter String value,
                @QueryParameter("suiteId") String suiteId
        ) {
            Jenkins.get().checkPermission(Item.CONFIGURE);
            return xorNumeric(value, suiteId, "Test ID", "Suite ID");
        }

        /* ------------------
         * Helpers
         * ------------------ */

        private static FormValidation xorNumeric(
                String primary,
                String other,
                String primaryName,
                String otherName
        ) {
            boolean primaryEmpty = primary == null || primary.trim().isEmpty();
            boolean otherEmpty = other == null || other.trim().isEmpty();

            if (primaryEmpty && otherEmpty) {
                return FormValidation.error(
                        "Provide either " + primaryName + " or " + otherName
                );
            }
            if (!primaryEmpty && !otherEmpty) {
                return FormValidation.error(
                        "Provide only one of " + primaryName + " or " + otherName
                );
            }
            if (!primaryEmpty) {
                try {
                    int v = Integer.parseInt(primary.trim());
                    if (v <= 0) {
                        return FormValidation.error(primaryName + " must be a positive number");
                    }
                } catch (NumberFormatException e) {
                    return FormValidation.error(primaryName + " must be numeric");
                }
            }
            return FormValidation.ok();
        }
    }

}
