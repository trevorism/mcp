package com.trevorism.logs

import com.google.auth.oauth2.GoogleCredentials
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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

    protected GoogleCredentials resolve() {
        if (credentials != null) {
            return credentials
        }
        synchronized (this) {
            if (credentials == null) {
                GoogleCredentials loaded = loadDefault()
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
