package com.trevorism.logs

import com.google.auth.oauth2.GoogleCredentials
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Mints Google access tokens from Application Default Credentials.
 *
 * No credential is stored in this repo. Deployed on App Engine, ADC resolves to the runtime's own
 * service account via the GCP metadata server; locally it resolves to whatever
 * `gcloud auth application-default login` left in the user's profile. A clone of this repo therefore
 * carries no access to anyone else's logs.
 *
 * Credentials are built lazily on first use so that cold starts of the other tools pay nothing for this.
 */
@Singleton
class GoogleTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenProvider)
    private static final List<String> SCOPES = ["https://www.googleapis.com/auth/logging.read"]

    private volatile GoogleCredentials credentials

    boolean isAvailable() {
        try {
            return resolve() != null
        } catch (Exception ignored) {
            return false
        }
    }

    String getAccessToken() {
        GoogleCredentials creds = resolve()
        creds.refreshIfExpired()
        return creds.getAccessToken()?.getTokenValue()
    }

    /** Loadable ADC, or an exception explaining that there is none. Overridable for tests. */
    protected GoogleCredentials resolve() {
        if (credentials != null) {
            return credentials
        }
        synchronized (this) {
            if (credentials == null) {
                GoogleCredentials loaded = loadDefault()
                // The metadata-server identity carries its scopes already and rejects createScoped;
                // user ADC and service-account keys need them applied explicitly.
                credentials = loaded.createScopedRequired() ? loaded.createScoped(SCOPES) : loaded
                log.info("Resolved Google application default credentials (${credentials.getClass().simpleName})")
            }
        }
        return credentials
    }

    protected GoogleCredentials loadDefault() {
        try {
            return GoogleCredentials.getApplicationDefault()
        } catch (IOException e) {
            throw new IllegalStateException(unavailableMessage(), e)
        }
    }

    static String unavailableMessage() {
        return "No Google application default credentials are available. Deployed, this comes from the " +
                "App Engine service account automatically; locally, run 'gcloud auth application-default login'."
    }
}
