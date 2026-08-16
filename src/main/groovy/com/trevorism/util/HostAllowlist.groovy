package com.trevorism.util

import groovy.transform.CompileStatic

@CompileStatic
class HostAllowlist {

    private static final String DOMAIN = "trevorism.com"

    static boolean isAllowed(String url) {
        try {
            String host = new URI(url).host?.toLowerCase()
            return host != null && (host == DOMAIN || host.endsWith("." + DOMAIN))
        } catch (Exception ignored) {
            return false
        }
    }
}
