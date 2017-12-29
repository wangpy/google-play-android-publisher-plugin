package org.jenkinsci.plugins.googleplayandroidpublisher;

/** Thrown when there's a temporary problem with validating the Google Play credentials. */
public class EphemeralCredentialsException extends UploadException {

    public EphemeralCredentialsException(String message) {
        super(message);
    }

}
