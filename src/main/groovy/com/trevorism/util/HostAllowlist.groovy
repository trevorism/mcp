package com.trevorism.util

import groovy.transform.CompileStatic

/**
 * Guards outbound calls so the caller's token (and server-side fetches) only ever go to Trevorism hosts.
 * Prevents a confused-deputy / token-exfiltration path where an agent is directed to an arbitrary URL.
 */
@CompileStatic
class HostAllowlist {

    private static final String DOMAIN = "trevorism.com"

    /** True only if {@code url}'s host is {@code trevorism.com} or a subdomain of it. */
    static boolean isAllowed(String url) {
        try {
            String host = new URI(url).host?.toLowerCase()
            return host != null && (host == DOMAIN || host.endsWith("." + DOMAIN))
        } catch (Exception ignored) {
            return false
        }
    }
}
