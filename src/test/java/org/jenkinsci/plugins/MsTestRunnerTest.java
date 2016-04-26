package org.jenkinsci.plugins;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import hudson.Util;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.util.jna.GNUCLibrary;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class MsTestRunnerTest {

    private static final String MS_TEST_INSTALLATION = "MSTestInstallation";
    
    private static final String SUCCESS_MESSAGE = "Running MsTest and succeeds";
    private static final String FAILURE_MESSAGE = "Running MsTest and fails";

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void runTestsBasic() throws URISyntaxException, IOException, InterruptedException, ExecutionException {
        // Configure mocked MsTest tool that will not fail
        mockMSTestInstallation(false);
        FreeStyleProject p = createFreeStyleProjectWithMsTest("", "", false);
        FreeStyleBuild build = p.scheduleBuild2(0).get();
        
        assertLogContains(SUCCESS_MESSAGE, build);
        assertBuildStatus(Result.SUCCESS, build);
    }

    @Test
    public void runTestsFail() throws InterruptedException, ExecutionException, URISyntaxException, IOException {
        // Configure mocked MsTest tool that will fail
        mockMSTestInstallation(true);
        FreeStyleProject p = createFreeStyleProjectWithMsTest("", "", false);
        FreeStyleBuild build = p.scheduleBuild2(0).get();
        
        assertLogContains(FAILURE_MESSAGE, build);
        assertBuildStatus(Result.FAILURE, build);
    }

    @Test
    public void ignoreFailingTests() throws InterruptedException, ExecutionException, URISyntaxException, IOException {
        // Configure mocked MsTest tool that will fail
        mockMSTestInstallation(true);
        FreeStyleProject p = createFreeStyleProjectWithMsTest("", "", true);
        FreeStyleBuild build = p.scheduleBuild2(0).get();
        
        assertLogContains(FAILURE_MESSAGE, build);
        assertBuildStatus(Result.SUCCESS, build);
    }
    
    @Test
    public void runTestsWithCategories() throws URISyntaxException, IOException, InterruptedException, ExecutionException {
        String category = "somecategory";
        // Configure mocked MsTest tool that will not fail
        mockMSTestInstallation(false);
        FreeStyleProject p = createFreeStyleProjectWithMsTest(category, "", true);
        FreeStyleBuild build = p.scheduleBuild2(0).get();
        
        assertLogContains(SUCCESS_MESSAGE, build);
        assertLogContains("/category:" + category, build);
        assertBuildStatus(Result.SUCCESS, build);
    }

    @Test
    public void runTestsWithCmdArguments() throws URISyntaxException, IOException, InterruptedException, ExecutionException {
        String cmdArg = "/testsettings:Local.Testsettings";
        // Configure mocked MsTest tool that will not fail
        mockMSTestInstallation(false);
        FreeStyleProject p = createFreeStyleProjectWithMsTest("", cmdArg, true);
        FreeStyleBuild build = p.scheduleBuild2(0).get();
        
        assertLogContains(SUCCESS_MESSAGE, build);
        assertLogContains(cmdArg, build);
        assertBuildStatus(Result.SUCCESS, build);
    }

    /**
     * Configures the MSTest installation.
     * 
     * @param failingInstallation. If true, the use of MsTest will make the build fail.
     * @return
     * @throws URISyntaxException
     */
    private MsTestInstallation mockMSTestInstallation(boolean failingInstallation) throws URISyntaxException {
        // Get appropriate installation file depending on parameter
        String home = failingInstallation ? getClass().getResource("mstest/tool/mstest-fail").toURI().getPath()
                                          : getClass().getResource("mstest/tool/mstest").toURI().getPath();
        // Execution permissions for the tool
        GNUCLibrary.LIBC.chmod(home, 0755);
        // Configure installation
        MsTestInstallation msTestInstallation = new MsTestInstallation(MS_TEST_INSTALLATION, home, null, false);
        r.jenkins.getDescriptorByType(MsTestInstallation.DescriptorImpl.class).setInstallations(msTestInstallation);
        return msTestInstallation;
    }
    
    /**
     * Creates a FreeStyleProject with a MSTestRunner build step.
     * 
     * @param categories the test categories to run
     * @param cmdLineArgs the cmd line arguments to use when running MsTest
     * @param ignoreFailingTests whether to ignore failing tests or not
     * @return the FreeStyleProject
     * @throws URISyntaxException
     * @throws IOException
     */
    private FreeStyleProject createFreeStyleProjectWithMsTest(String categories, String cmdLineArgs, boolean ignoreFailingTests) throws URISyntaxException, IOException {
        String msTestFiles = getClass().getResource("mstest/testfile").toURI().getPath();
        FreeStyleProject p = r.jenkins.createProject(FreeStyleProject.class, "MSTestProject");
        p.getBuildersList().add(new MsTestBuilder(MS_TEST_INSTALLATION, msTestFiles, categories, "resultFile", cmdLineArgs, ignoreFailingTests));
        return p;
    }
    
    private void assertLogContains(String text, FreeStyleBuild build) throws IOException {
        String log = Util.loadFile(build.getLogFile(), build.getCharset());
        assertTrue(log.contains(text));
    }

    private void assertBuildStatus(Result expectedStatus, FreeStyleBuild build) {
        assertThat(build.getResult(), equalTo(expectedStatus));
    }
}
