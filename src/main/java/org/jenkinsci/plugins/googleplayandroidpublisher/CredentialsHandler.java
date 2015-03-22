package org.jenkinsci.plugins.googleplayandroidpublisher;

import com.google.jenkins.plugins.credentials.oauth.GoogleOAuth2ScopeRequirement;
import com.google.jenkins.plugins.credentials.oauth.GoogleRobotCredentials;
import org.apache.commons.lang.exception.ExceptionUtils;

import java.io.FileNotFoundException;
import java.security.GeneralSecurityException;

public class CredentialsHandler {

    private final String googleCredentialsId;

    public CredentialsHandler(String googleCredentialsId) {
        this.googleCredentialsId = googleCredentialsId;
    }

    /** @return The Google API credentials configured for this job. */
    public final GoogleRobotCredentials getServiceAccountCredentials() throws UploadException {
        try {
            GoogleOAuth2ScopeRequirement req = new AndroidPublisherScopeRequirement();
            GoogleRobotCredentials credentials = GoogleRobotCredentials.getById(googleCredentialsId);
            if (credentials == null) {
                throw new UploadException("Credentials for the configured Google Account could not be found");
            }
            return credentials.forRemote(req);
        } catch (NullPointerException e) {
            // This should really be handled by the Google OAuth plugin
            throw new UploadException("Failed to get Google service account info.\n" +
                    "\tCheck that the correct 'Client Secrets JSON' file has been uploaded for the " +
                    "'"+ googleCredentialsId +"' credential.\n" +
                    "\tThe correct JSON file can be obtained by visiting the *old* Google APIs Console, selecting "+
                    "'API Access' and then clicking 'Download JSON' for the appropriate service account.\n" +
                    "\tSee: https://code.google.com/apis/console/?noredirect", e);
        } catch (IllegalStateException e) {
            if (ExceptionUtils.getRootCause(e) instanceof FileNotFoundException) {
                throw new UploadException("Failed to get Google service account info. Ensure that the JSON file and " +
                        "P12 private key for the '"+ googleCredentialsId +"' credential have both been uploaded.", e);
            }
            throw new UploadException(e);
        } catch (GeneralSecurityException e) {
            throw new UploadException(e);
        }
    }

}
