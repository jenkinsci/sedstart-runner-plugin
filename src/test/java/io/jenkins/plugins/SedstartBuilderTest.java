package io.jenkins.plugins;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
@WithJenkins
public class SedstartBuilderTest {
    final String name = "Bobby";

    @Test
    void testConfigRoundtrip(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new SedStartBuilder(SedStartBuilder.Mode.CLOUD,name));
        project = jenkins.configRoundtrip(project);
        jenkins.assertEqualDataBoundBeans(
                new SedStartBuilder(SedStartBuilder.Mode.CLOUD,name), project.getBuildersList().get(0));
    }
}
