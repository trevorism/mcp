package com.trevorism.model

import groovy.transform.CompileStatic
import groovy.transform.ToString

/**
 * A discovered Trevorism service: its repo/service name, resolved base URL, and category (dns).
 */
@CompileStatic
@ToString(includeNames = true)
class ServiceEntry {
    String name
    String baseUrl
    String category

    ServiceEntry() {}

    ServiceEntry(String name, String baseUrl, String category) {
        this.name = name
        this.baseUrl = baseUrl
        this.category = category
    }

    Map toMap() {
        [name: name, baseUrl: baseUrl, category: category]
    }
}
