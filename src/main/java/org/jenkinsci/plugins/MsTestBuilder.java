package org.jenkinsci.plugins;

import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tools.ToolInstallation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Ido Ran
 */
public class MsTestBuilder extends Builder {

    /**
     * GUI fields
     */
    private final String msTestName;
    private final String testFiles;
    private final String categories;
    private final String resultFile;
    private final String cmdLineArgs;
    private final boolean continueOnFail;

    /**
     * When this builder is created in the project configuration step,
     * the builder object will be created from the strings below.
     *
     * @param msTestName The MSTest logical name
     * @param testFiles The path of the test files
     * @param cmdLineArgs Whitespace separated list of command line arguments
     */
    @DataBoundConstructor
    @SuppressWarnings("unused")
    public MsTestBuilder(String msTestName, String testFiles, String categories, String resultFile, String cmdLineArgs, boolean continueOnFail) {
        this.msTestName = msTestName;
        this.testFiles = testFiles;
        this.categories = categories;
        this.resultFile = resultFile;
        this.cmdLineArgs = cmdLineArgs;
        this.continueOnFail = continueOnFail;
    }

    @SuppressWarnings("unused")
    public String getCmdLineArgs() {
        return cmdLineArgs;
    }

    @SuppressWarnings("unused")
    public String getTestFiles() {
        return testFiles;
    }

    @SuppressWarnings("unused")
    public String getCategories() {
        return categories;
    }

    @SuppressWarnings("unused")
    public String getResultFile() {
        return resultFile;
    }

    @SuppressWarnings("unused")
    public String getMsTestName() {
        return msTestName;
    }

    @SuppressWarnings("unused")
    public boolean getcontinueOnFail() {
        return continueOnFail;
    }

    public MsTestInstallation getMsTest() {
        for (MsTestInstallation i : DESCRIPTOR.getInstallations()) {
            if (msTestName != null && i.getName().equals(msTestName)) {
                return i;
            }
        }
        return null;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        List<String> args = new ArrayList<String>();

        FilePath workspace = build.getWorkspace();

        // Build MSTest.exe path.
        String execName = "mstest.exe";
        MsTestInstallation installation = getMsTest();
        if (installation == null) {
            listener.getLogger().println("Path To MSTest.exe: " + execName);
            args.add(execName);
        } else {
            EnvVars env = build.getEnvironment(listener);
            Node node = Computer.currentComputer().getNode();
            if (node == null) {
                listener.fatalError("Configuration has changed and node has been removed.");
                return false;
            }
            installation = installation.forNode(node, listener);
            installation = installation.forEnvironment(env);
            String pathToMsTest = installation.getHome();
            FilePath exec = new FilePath(launcher.getChannel(), pathToMsTest);
            try {
                if (!exec.exists()) {
                    listener.fatalError(pathToMsTest + " doesn't exist");
                    return false;
                }
            } catch (IOException e) {
                listener.fatalError("Failed checking for existence of " + pathToMsTest);
                return false;
            }
            listener.getLogger().println("Path To MSTest.exe: " + pathToMsTest);
            args.add(pathToMsTest);

            if (installation.getDefaultArgs() != null) {
                args.addAll(Arrays.asList(Util.tokenize(installation.getDefaultArgs())));
            }
        }

        if (resultFile == null || resultFile.trim().length() == 0) {
            listener.fatalError("Result file name was not specified");
            return false;
        }

        // Delete old result file
        FilePath resultFilePath = workspace.child(resultFile);
        if (!resultFilePath.exists()) {
            listener.getLogger().println("Result file was not found so no action has been taken. " + resultFilePath.toURI());
        } else {
            listener.getLogger().println("Delete old result file " + resultFilePath.toURI().toString());
            try {
                resultFilePath.delete();
            } catch (IOException ex) {
                ex.printStackTrace(listener.fatalError("Fail to delete old result file"));
                return false;
            } catch (InterruptedException ex) {
                ex.printStackTrace(listener.fatalError("Fail to delete old result file"));
                return false;
            }
        }

        // Add result file argument
        args.add("/resultsfile:" + resultFile);

        // Checks to use noisolation flag
        if (installation != null && !installation.getOmitNoIsolation()){
            args.add("/noisolation");
        }

        // Add command line arguments
        EnvVars env = build.getEnvironment(listener);
        String normalizedArgs = cmdLineArgs.replaceAll("[\t\r\n]+", " ");
        normalizedArgs = Util.replaceMacro(normalizedArgs, env);
        normalizedArgs = Util.replaceMacro(normalizedArgs, build.getBuildVariables());
        normalizedArgs = Util.fixEmptyAndTrim(normalizedArgs);
        if (normalizedArgs != null) {
            args.addAll(Arrays.asList(Util.tokenize(normalizedArgs)));
        }

        // check categories
        String categories = Util.fixEmptyAndTrim(this.categories);
        if (categories != null) {
        	// If filter consists of a single category such as /category:group1, do not have to enclose the filter in quotation marks.
        	// However, if filter references more than one category such as /category:"group1&group2" then the filter has to be enclosed in quotation marks.
        	boolean quotationMarks = categories.contains("&") || categories.contains("!") || categories.contains("|");
       		args.add("/category:" + (quotationMarks ? "\"" : "") + categories + (quotationMarks ? "\"" : ""));
        }

        String testFiles = Util.fixEmptyAndTrim(this.testFiles);
        // if no test files are specified fail the build.
        if (testFiles == null) {
            listener.fatalError("No test files are specified");
            return false;
        }

        // Add test containers to command line
        String macroReplacedTestFiles = Util.replaceMacro(testFiles, env);// Required to handle newlines properly
        StringTokenizer testFilesToknzr = new StringTokenizer(macroReplacedTestFiles, "\r\n");
        Set<String> testContainers = new LinkedHashSet<String>(7);

        while (testFilesToknzr.hasMoreTokens()) {
            String testFile = testFilesToknzr.nextToken();
            testFile = Util.replaceMacro(testFile, env);
            testFile = Util.replaceMacro(testFile, build.getBuildVariables());
            testFile = Util.fixEmptyAndTrim(testFile);

            if (testFile != null) {
            	File tcFile = new File(testFile);
				if (tcFile.isAbsolute()) {
					if (tcFile.isFile() && tcFile.exists()) {
						testContainers.add(testFile);
					}
            	} else {
					for (FilePath tcFilePath : workspace.list(testFile)) {
						// TODO make path relative to workspace to reduce length of command line (see windows max size)
	            		testContainers.add(tcFilePath.getRemote());
					}
            	}
            }
        }
        // nothing of include rule has match files in workspace folder
        if (testContainers.isEmpty()) {
        	listener.fatalError("No test files was found");
        	return false;
        }

        for (String testContainer : testContainers) {
        	args.add("/testcontainer:" + testContainer);
        }

        try {
            int r = launcher.launch().cmds(args).envs(env).stdout(listener).pwd(workspace).join();

            // If continueOnFail is set we always report success.
            // If not we report success if MSTest return 0 and exit value.
            return continueOnFail || (r == 0);
        } catch (IOException e) {
            Util.displayIOException(e, listener);
            e.printStackTrace(listener.fatalError("MSTest command execution failed"));
            return false;
        }
    }

    @Override
    public Descriptor<Builder> getDescriptor() {
        return DESCRIPTOR;
    }
    /**
     * Descriptor should be singleton.
     */
    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @CopyOnWrite
        private volatile MsTestInstallation[] installations = new MsTestInstallation[0];

        DescriptorImpl() {
            super(MsTestBuilder.class);
            load();
        }

        public String getDisplayName() {
            return "Run unit tests with MSTest";
        }

        @Override
        public boolean isApplicable(@SuppressWarnings("rawtypes") final Class<? extends AbstractProject> jobType) {
          return true;
        }

        public MsTestInstallation[] getInstallations() {
            return Arrays.copyOf(installations, installations.length);
        }

        public void setInstallations(MsTestInstallation... antInstallations) {
            this.installations = antInstallations;
            save();
        }

        /**
         * Obtains the {@link MsTestInstallation.DescriptorImpl} instance.
         */
        public MsTestInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(MsTestInstallation.DescriptorImpl.class);
        }
    }
}
