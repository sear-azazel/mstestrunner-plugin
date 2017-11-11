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
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

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
    private final boolean doNotUseChcpCommand;

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
    public MsTestBuilder(String msTestName, String testFiles, String categories, String resultFile, String cmdLineArgs, boolean continueOnFail, boolean doNotUseChcpCommand) {
        this.msTestName = msTestName;
        this.testFiles = testFiles;
        this.categories = categories;
        this.resultFile = resultFile;
        this.cmdLineArgs = cmdLineArgs;
        this.continueOnFail = continueOnFail;
        this.doNotUseChcpCommand = doNotUseChcpCommand;
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

    @SuppressWarnings("unused")
    public boolean getDoNotUseChcpCommand() {
        return doNotUseChcpCommand;
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
                    FilePath testFilePath = workspace.child(testFile);
                    // JENKINS-30457. To support dot-segments and maintain backwards compatibility since it was supported up till 1.1.2
                    if (testFilePath.exists()) {
                        testContainers.add(testFilePath.getRemote());
                    } else {
                        for (FilePath tcFilePath : workspace.list(testFile)) {
                            // TODO make path relative to workspace to reduce length of command line (see windows max size)
                            testContainers.add(tcFilePath.getRemote());
                        }
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

        if (!launcher.isUnix()) {
            if (!doNotUseChcpCommand) {
                final int cpi = getCodePageIdentifier(build.getCharset());
                if(cpi != 0) {
                    args.add(0, "&");
                	args.add(0, String.valueOf(cpi));
                    args.add(0, "chcp");
                }
            }
            
            args.add(0, "\"");
            args.add(0, "/C");
            args.add(0, "cmd.exe");
            args.add("\"");
            args.add("&&");
            args.add("exit");
            args.add("%%ERRORLEVEL%%");
        }
        else {
            listener.fatalError("Unable to use this plugin on this kind of operation system");
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
        
        public FormValidation doCheckResultFile(@QueryParameter String value) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.warning("Result File Name must be provided.");
            }
            return FormValidation.ok();
        }
    }

    private static int getCodePageIdentifier(Charset charset) {
        final String s_charset = charset.name();
        if(s_charset.equalsIgnoreCase("utf-8"))             // Unicode
            return 65001;
        else if(s_charset.equalsIgnoreCase("ibm437"))       // US
            return 437;
        else if(s_charset.equalsIgnoreCase("ibm850"))       // OEM Multilingual Latin 1
            return 850;
        else if(s_charset.equalsIgnoreCase("ibm852"))       // OEM Latin2
            return 852;
        else if(s_charset.equalsIgnoreCase("shift_jis") || s_charset.equalsIgnoreCase("windows-31j"))//Japanese
            return 932;
        else if(s_charset.equalsIgnoreCase("us-ascii"))     // US-ASCII
            return 20127;
        else if(s_charset.equalsIgnoreCase("euc-jp"))       // Japanese
            return 20932;
        else if(s_charset.equalsIgnoreCase("iso-8859-1"))   // Latin 1
            return 28591;
        else if(s_charset.equalsIgnoreCase("iso-8859-2"))   // Latin 2
            return 28592;
        else if(s_charset.equalsIgnoreCase("IBM00858"))
            return 858;
        else if(s_charset.equalsIgnoreCase("IBM775"))
            return 775;
        else if(s_charset.equalsIgnoreCase("IBM855"))
            return 855;
        else if(s_charset.equalsIgnoreCase("IBM857"))
            return 857;
        else if(s_charset.equalsIgnoreCase("ISO-8859-4"))
            return 28594;
        else if(s_charset.equalsIgnoreCase("ISO-8859-5"))
            return 28595;
        else if(s_charset.equalsIgnoreCase("ISO-8859-7"))
            return 28597;
        else if(s_charset.equalsIgnoreCase("ISO-8859-9"))
            return 28599;
        else if(s_charset.equalsIgnoreCase("ISO-8859-13"))
            return 28603;
        else if(s_charset.equalsIgnoreCase("ISO-8859-15"))
            return 28605;
        else if(s_charset.equalsIgnoreCase("KOI8-R"))
            return 20866;
        else if(s_charset.equalsIgnoreCase("KOI8-U"))
            return 21866;
        else if(s_charset.equalsIgnoreCase("UTF-16"))
            return 1200;
        else if(s_charset.equalsIgnoreCase("UTF-32"))
            return 12000;
        else if(s_charset.equalsIgnoreCase("UTF-32BE"))
            return 12001;
        else if(s_charset.equalsIgnoreCase("windows-1250"))
            return 1250;
        else if(s_charset.equalsIgnoreCase("windows-1251"))
            return 1251;
        else if(s_charset.equalsIgnoreCase("windows-1252"))
            return 1252;
        else if(s_charset.equalsIgnoreCase("windows-1253"))
            return 1253;
        else if(s_charset.equalsIgnoreCase("windows-1254"))
            return 1254;
        else if(s_charset.equalsIgnoreCase("windows-1257"))
            return 1257;
        else if(s_charset.equalsIgnoreCase("Big5"))
            return 950;
        else if(s_charset.equalsIgnoreCase("EUC-KR"))
            return 51949;
        else if(s_charset.equalsIgnoreCase("GB18030"))
            return 54936;
        else if(s_charset.equalsIgnoreCase("GB2312"))
            return 936;
        else if(s_charset.equalsIgnoreCase("IBM-Thai"))
            return 20838;
        else if(s_charset.equalsIgnoreCase("IBM01140"))
            return 1140;
        else if(s_charset.equalsIgnoreCase("IBM01141"))
            return 1141;
        else if(s_charset.equalsIgnoreCase("IBM01142"))
            return 1142;
        else if(s_charset.equalsIgnoreCase("IBM01143"))
            return 1143;
        else if(s_charset.equalsIgnoreCase("IBM01144"))
            return 1144;
        else if(s_charset.equalsIgnoreCase("IBM01145"))
            return 1145;
        else if(s_charset.equalsIgnoreCase("IBM01146"))
            return 1146;
        else if(s_charset.equalsIgnoreCase("IBM01147"))
            return 1147;
        else if(s_charset.equalsIgnoreCase("IBM01148"))
            return 1148;
        else if(s_charset.equalsIgnoreCase("IBM01149"))
            return 1149;
        else if(s_charset.equalsIgnoreCase("IBM037"))
            return 37;
        else if(s_charset.equalsIgnoreCase("IBM1026"))
            return 1026;
        else if(s_charset.equalsIgnoreCase("IBM273"))
            return 20273;
        else if(s_charset.equalsIgnoreCase("IBM277"))
            return 20277;
        else if(s_charset.equalsIgnoreCase("IBM278"))
            return 20278;
        else if(s_charset.equalsIgnoreCase("IBM280"))
            return 20280;
        else if(s_charset.equalsIgnoreCase("IBM284"))
            return 20284;
        else if(s_charset.equalsIgnoreCase("IBM285"))
            return 20285;
        else if(s_charset.equalsIgnoreCase("IBM297"))
            return 20297;
        else if(s_charset.equalsIgnoreCase("IBM420"))
            return 20420;
        else if(s_charset.equalsIgnoreCase("IBM424"))
            return 20424;
        else if(s_charset.equalsIgnoreCase("IBM500"))
            return 500;
        else if(s_charset.equalsIgnoreCase("IBM860"))
            return 860;
        else if(s_charset.equalsIgnoreCase("IBM861"))
            return 861;
        else if(s_charset.equalsIgnoreCase("IBM863"))
            return 863;
        else if(s_charset.equalsIgnoreCase("IBM864"))
            return 864;
        else if(s_charset.equalsIgnoreCase("IBM865"))
            return 865;
        else if(s_charset.equalsIgnoreCase("IBM869"))
            return 869;
        else if(s_charset.equalsIgnoreCase("IBM870"))
            return 870;
        else if(s_charset.equalsIgnoreCase("IBM871"))
            return 20871;
        else if(s_charset.equalsIgnoreCase("ISO-2022-JP"))
            return 50220;
        else if(s_charset.equalsIgnoreCase("ISO-2022-KR"))
            return 50225;
        else if(s_charset.equalsIgnoreCase("ISO-8859-3"))
            return 28593;
        else if(s_charset.equalsIgnoreCase("ISO-8859-6"))
            return 28596;
        else if(s_charset.equalsIgnoreCase("ISO-8859-8"))
            return 28598;
        else if(s_charset.equalsIgnoreCase("windows-1255"))
            return 1255;
        else if(s_charset.equalsIgnoreCase("windows-1256"))
            return 1256;
        else if(s_charset.equalsIgnoreCase("windows-1258"))
            return 1258;
        else
            return 0;
    }
}
