package com.trevorism.util

import groovy.transform.CompileStatic

@CompileStatic
class HostAllowlist {

    private static final String DOMAIN = "trevorism.com"

    static boolean isAllowed(String url) {
        try {
            URI uri = new URI(url)
            String host = uri.host?.toLowerCase()
            return "https".equalsIgnoreCase(uri.scheme) && host != null && (host == DOMAIN || host.endsWith("." + DOMAIN))
        } catch (Exception ignored) {
            return false
        }
    }
}
