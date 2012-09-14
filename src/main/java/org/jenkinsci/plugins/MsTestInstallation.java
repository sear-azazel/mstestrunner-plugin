package org.jenkinsci.plugins;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.model.EnvironmentSpecific;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;

/**
 * @author Ido Ran
 */
public final class MsTestInstallation extends ToolInstallation implements NodeSpecific<MsTestInstallation>, EnvironmentSpecific<MsTestInstallation> {

    @SuppressWarnings("unused")
    /**
     * Backward compatibility
     */
    private transient String pathToMsTest;

    private String defaultArgs;

	private boolean omitNoIsolation;

    @DataBoundConstructor
    public MsTestInstallation(String name, String home, String defaultArgs, boolean omitNoIsolation) {
        super(name, home, null);
        this.defaultArgs = Util.fixEmpty(defaultArgs);
		this.omitNoIsolation = omitNoIsolation;
    }

    public MsTestInstallation forNode(Node node, TaskListener log) throws IOException, InterruptedException {
        return new MsTestInstallation(getName(), translateFor(node, log), getDefaultArgs(), getOmitNoIsolation());
    }

    public MsTestInstallation forEnvironment(EnvVars environment) {
        return new MsTestInstallation(getName(), environment.expand(getHome()), getDefaultArgs(), getOmitNoIsolation());
    }

    public String getDefaultArgs() {
        return this.defaultArgs;
    }

	public boolean getOmitNoIsolation() {
		return this.omitNoIsolation;
	}

    @Extension
    public static class DescriptorImpl extends ToolDescriptor<MsTestInstallation> {

        public String getDisplayName() {
            return "MSTest";
        }

        @Override
        public MsTestInstallation[] getInstallations() {
            return Hudson.getInstance().getDescriptorByType(MsTestBuilder.DescriptorImpl.class).getInstallations();
        }

        @Override
        public void setInstallations(MsTestInstallation... installations) {
            Hudson.getInstance().getDescriptorByType(MsTestBuilder.DescriptorImpl.class).setInstallations(installations);
        }

    }

    /**
     * Used for backward compatibility
     *
     * @return the new object, an instance of MsTestInstallation
     */
    protected Object readResolve() {
        if (this.pathToMsTest != null) {
            return new MsTestInstallation(this.getName(), this.pathToMsTest, this.defaultArgs, this.omitNoIsolation);
        }
        return this;
    }
}
