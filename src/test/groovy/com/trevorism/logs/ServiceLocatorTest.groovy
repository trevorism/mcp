package com.trevorism.logs

import com.trevorism.model.ServiceEntry
import com.trevorism.service.ServiceRegistry
import org.junit.jupiter.api.Test

class ServiceLocatorTest {

    private static ServiceLocator locator(List<ServiceEntry> entries) {
        new ServiceLocator(new ServiceRegistry() {
            @Override
            List<ServiceEntry> listServices(String bearer) { entries }
            @Override
            ServiceEntry byName(String name, String bearer) { entries.find { it.name == name } }
        })
    }

    @Test
    void testSubdomainServiceIsANamedModule() {
        def result = locator([new ServiceEntry("event", "https://event.data.trevorism.com", "data")])
                .locate("event", "tok")
        assert result == [project: "trevorism-data", module: "event"]
    }

    @Test
    void testCategoryDefaultServiceIsModuleDefault() {
        def result = locator([new ServiceEntry("testing", "https://testing.trevorism.com", "testing")])
                .locate("testing", "tok")
        assert result == [project: "trevorism-testing", module: "default"]
    }

    @Test
    void testAuthProviderResolvesToItsCategoryProject() {
        def result = locator([new ServiceEntry("auth-provider", "https://auth.trevorism.com", "auth")])
                .locate("auth-provider", "tok")
        assert result == [project: "trevorism-auth", module: "default"]
    }

    @Test
    void testMcpResolvesToItsOwnProject() {
        def result = locator([new ServiceEntry("mcp", "https://mcp.project.trevorism.com", "project")])
                .locate("mcp", "tok")
        assert result == [project: "trevorism-project", module: "mcp"]
    }

    @Test
    void testBareTrevorismCategoryHasNoSuffix() {
        assert ServiceLocator.projectFor("trevorism") == "trevorism"
        assert ServiceLocator.projectFor("draw") == "trevorism-draw"
    }

    @Test
    void testUnknownServiceReturnsNullRatherThanGuessing() {
        assert locator([]).locate("nope", "tok") == null
    }

    @Test
    void testServiceWithoutCategoryReturnsNull() {
        assert locator([new ServiceEntry("odd", "https://odd.trevorism.com", null)]).locate("odd", "tok") == null
    }
}
