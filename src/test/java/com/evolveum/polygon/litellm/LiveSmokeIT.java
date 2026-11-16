/*
 * Copyright (c) 2026 Evolveum and contributors
 *
 * This work is licensed under European Union Public License v1.2. See LICENSE file for details.
 *
 */
package com.evolveum.polygon.litellm;

import com.evolveum.polygon.scimrest.groovy.impl.ManifestBasedConnector;
import com.evolveum.polygon.scimrest.groovy.impl.ReadOnlyConfiguration;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.api.ConnectorFacadeFactory;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.test.common.TestHelpers;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Live smoke tests against a real LiteLLM proxy. Skipped unless the LITELLM_API_KEY
 * environment variable is set; the base URL comes from LITELLM_BASE_URL (defaults to
 * 192.168.1.10:14000). Run with: mvn -pl litellm -P live-tests verify
 */
public class LiveSmokeIT {

    private static final String DEFAULT_BASE_URL = "http://192.168.1.10:14000";

    private static String apiKey;
    private static String baseUrl;
    private static ConnectorFacade facade;

    @BeforeClass
    public void requireApiKey() {
        apiKey = System.getenv("LITELLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new SkipException("LITELLM_API_KEY is not set; skipping live tests");
        }
        baseUrl = System.getenv("LITELLM_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        var configuration = new ReadOnlyConfiguration();
        configuration.setBaseAddress(baseUrl);
        configuration.setRestTestEndpoint("/health/liveliness");
        configuration.setRestTokenValue(new GuardedString(apiKey.toCharArray()));

        var apiConfiguration = TestHelpers.createTestConfiguration(ManifestBasedConnector.class, configuration);
        apiConfiguration.getResultsHandlerConfiguration().setEnableAttributesToGetSearchResultsHandler(false);
        apiConfiguration.getResultsHandlerConfiguration().setEnableCaseInsensitiveFilter(false);
        apiConfiguration.getResultsHandlerConfiguration().setEnableFilteredResultsHandler(false);
        apiConfiguration.getResultsHandlerConfiguration().setEnableNormalizingResultsHandler(false);
        apiConfiguration.getResultsHandlerConfiguration().setFilteredResultsHandlerInValidationMode(false);
        facade = ConnectorFacadeFactory.getInstance().newInstance(apiConfiguration);
    }

    @Test
    public void testHealth() {
        // The facade was created with the base address, the API key and the health
        // probe endpoint; test() exercises exactly that configuration.
        facade.test();
    }

    @Test
    public void searchAllUsers() {
        var results = search(new ObjectClass("User"), null);
        assertTrue(results.size() >= 1, "Expected at least one user, got " + results.size());
        for (var user : results) {
            assertNotNull(user.getUid());
            assertNotNull(user.getName());
        }
    }

    @Test
    public void searchUserByUid() {
        var results = search(new ObjectClass("User"), null);
        assertTrue(results.size() >= 1, "Expected at least one user");
        var uid = results.getFirst().getUid().getUidValue();

        var byUid = search(new ObjectClass("User"), new EqualsFilter(new Uid(uid)));
        assertEquals(byUid.size(), 1);
        assertEquals(byUid.getFirst().getUid().getUidValue(), uid);
    }

    @Test
    public void searchAllVirtualKeys() {
        var results = search(new ObjectClass("VirtualKey"), null);
        assertTrue(results.size() >= 1, "Expected at least one virtual key, got " + results.size());
        for (var key : results) {
            assertNotNull(key.getUid(), "key without token: " + key.getName());
        }
    }

    @Test
    public void searchAllModels() {
        var results = search(new ObjectClass("Model"), null);
        assertTrue(results.size() >= 1, "Expected at least one model group, got " + results.size());
    }

    @Test
    public void searchAllEndpoints() {
        var results = search(new ObjectClass("Endpoint"), null);
        assertTrue(results.size() >= 1, "Expected at least one model deployment, got " + results.size());
        for (var endpoint : results) {
            assertNotNull(endpoint.getUid(), "deployment without model_info.id: " + endpoint.getName());
        }
    }

    @Test
    public void searchAllAgents() {
        var results = search(new ObjectClass("Agent"), null);
        assertTrue(results.size() >= 1, "Expected at least one agent, got " + results.size());
        for (var agent : results) {
            assertNotNull(agent.getName(), "agent without a name: " + agent.getUid());
        }
    }

    @Test
    public void searchAllTeams() {
        // The live instance may have no teams; the call itself must succeed.
        search(new ObjectClass("Team"), null);
    }

    @Test
    public void searchAllMcpServers() {
        search(new ObjectClass("MCP"), null);
    }

    @Test
    public void searchAllGuardrails() {
        search(new ObjectClass("Guardrail"), null);
    }

    @Test
    public void organizationsAreEnterpriseGated() {
        try {
            var results = search(new ObjectClass("Organization"), null);
            // An enterprise deployment answers with the organization list - it must be readable.
            for (var org : results) {
                assertNotNull(org.getUid(), "organization without an id");
            }
        } catch (Exception e) {
            // Community deployment: /organization/list answers 403 "Organizations are only
            // available for LiteLLM Enterprise users". The connector must surface that error
            // instead of failing with an unrelated exception.
            var message = messageChain(e).toLowerCase(Locale.ROOT);
            assertTrue(message.contains("403") || message.contains("forbidden") || message.contains("enterprise"),
                    "Expected the enterprise-gate 403 error, got: " + messageChain(e));
        }
    }

    // --- helpers -----------------------------------------------------------------

    private List<ConnectorObject> search(ObjectClass objectClass, org.identityconnectors.framework.common.objects.filter.Filter filter) {
        var results = new ArrayList<ConnectorObject>();
        facade.search(objectClass, filter, results::add, new OperationOptions(Map.of()));
        return results;
    }

    private static String messageChain(Throwable e) {
        return e == null ? "" : e.getMessage() + (e.getCause() != null ? " | " + messageChain(e.getCause()) : "");
    }
}
