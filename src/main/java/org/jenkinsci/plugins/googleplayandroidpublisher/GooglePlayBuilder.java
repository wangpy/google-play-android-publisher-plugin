package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.jenkins.plugins.credentials.domains.RequiresDomain;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.IOException;

@RequiresDomain(value = AndroidPublisherScopeRequirement.class)
public abstract class GooglePlayBuilder extends Builder {

    protected static transient final ThreadLocal<AbstractBuild> currentBuild = new ThreadLocal<AbstractBuild>();
    protected static transient final ThreadLocal<TaskListener> currentListener = new ThreadLocal<TaskListener>();

    private transient CredentialsHandler credentialsHandler;

    @DataBoundSetter
    private String googleCredentialsId;

    public final String getGoogleCredentialsId() {
        return googleCredentialsId;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
            BuildListener listener) throws InterruptedException, IOException {
        currentBuild.set(build);
        currentListener.set(listener);
        return true;
    }

    protected CredentialsHandler getCredentialsHandler() throws CredentialsException {
        if (credentialsHandler == null) {
            credentialsHandler = new CredentialsHandler(googleCredentialsId);
        }
        return credentialsHandler;
    }

    /** @return An expanded value, using the build and environment variables, plus token macro expansion. */
    protected String expand(String value) throws IOException, InterruptedException {
        return Util.expand(currentBuild.get(), currentListener.get(), value);
    }

    public BuildStepMonitor getRequiredMonitorService() {
        // Try to minimise concurrent editing, as the Google Play Developer Publishing API does not allow it
        return BuildStepMonitor.STEP;
    }

}
