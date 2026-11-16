/*
 * Copyright (c) 2026 Evolveum and contributors
 *
 * This work is licensed under European Union Public License v1.2. See LICENSE file for details.
 *
 */
package com.evolveum.polygon.litellm;

import com.evolveum.polygon.scimrest.groovy.impl.ManifestBasedConnector;
import com.evolveum.polygon.scimrest.groovy.impl.ReadOnlyConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.api.ConnectorFacadeFactory;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObjectReference;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.identityconnectors.framework.common.objects.filter.FilterBuilder;
import org.identityconnectors.test.common.TestHelpers;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * End-to-end smoke tests of the LiteLLM connector against a WireMock instance serving
 * recorded/synthetic LiteLLM payloads. Exercises listing, by-UID lookup, server-side filters,
 * reference population and paging through the full facade + REST pipeline.
 */
public class MockSmokeTest {

    private static WireMockServer server;

    private ConnectorFacade facade;

    @BeforeClass
    public static void startServer() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterClass
    public static void stopServer() {
        server.stop();
    }

    @BeforeMethod
    public void setUp() {
        server.resetAll();
        stub(100, urlPathEqualTo("/health/liveliness"), 200, fixture("health.json"), Map.of());
        facade = newFacade();
    }

    private ConnectorFacade newFacade() {
        var configuration = new ReadOnlyConfiguration();
        configuration.setBaseAddress("http://localhost:" + server.port());
        configuration.setRestTokenValue(new GuardedString("sk-test-api-key".toCharArray()));

        var apiConfiguration = TestHelpers.createTestConfiguration(ManifestBasedConnector.class, configuration);
        apiConfiguration.getResultsHandlerConfiguration().setEnableAttributesToGetSearchResultsHandler(false);
        apiConfiguration.getResultsHandlerConfiguration().setEnableCaseInsensitiveFilter(false);
        apiConfiguration.getResultsHandlerConfiguration().setEnableFilteredResultsHandler(false);
        apiConfiguration.getResultsHandlerConfiguration().setEnableNormalizingResultsHandler(false);
        apiConfiguration.getResultsHandlerConfiguration().setFilteredResultsHandlerInValidationMode(false);
        return ConnectorFacadeFactory.getInstance().newInstance(apiConfiguration);
    }

    // --- test() --------------------------------------------------------------

    @Test
    public void testHealthEndpoint() {
        // The facade is configured with the base address and bearer token; the health
        // probe endpoint (/health/liveliness) comes from configurationOverride.properties.
        facade.test();
    }

    // --- User ----------------------------------------------------------------

    @Test
    public void searchAllUsers() {
        stub(100, urlPathEqualTo("/user/list"), 200, fixture("user_list.json"), Map.of());
        stub(50, urlPathEqualTo("/user/list"), 200, fixture("user_list_empty.json"), Map.of("page", "2"));

        var results = search(new ObjectClass("User"), null);
        assertEquals(results.size(), 2);

        var tony = byUid(results, "user-tony");
        assertEquals(tony.getName().getNameValue(), "tony@example.com");
        assertEquals(val(attribute(tony, "user_alias")), "tony");
        assertEquals(val(attribute(tony, "user_role")), "proxy_admin");
        assertEquals(val(attribute(tony, "organization_id")), "org-1");
        assertEquals(refs(tony, "teams"), List.of("team-alpha", "team-beta"));
        assertEquals(refValue(ref(tony, "organization")).getUid().getUidValue(), "org-1");
        assertEquals(asString(attribute(tony, "key_count")), "2");

        var alice = byUid(results, "user-alice");
        assertEquals(val(attribute(alice, "user_role")), "user");
        assertEquals(refs(alice, "teams"), List.of("team-beta"));
        assertEquals(asString(attribute(alice, "tpm_limit")), "1000000");
        assertEquals(asList(attribute(alice, "models")), List.of("gpt-4o"));

        // The bearer token must be sent on the request.
        server.verify(com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
                .newRequestPattern(com.github.tomakehurst.wiremock.http.RequestMethod.GET, urlPathEqualTo("/user/list"))
                .withHeader("Authorization", equalTo("Bearer sk-test-api-key")));
    }

    @Test
    public void searchUserByUid() {
        stub(100, urlPathEqualTo("/user/list"), 200, fixture("user_list_tony.json"), Map.of("user_ids", "user-tony"));

        var results = search(new ObjectClass("User"), new EqualsFilter(new Uid("user-tony")));
        assertEquals(results.size(), 1);
        assertEquals(byUid(results, "user-tony").getName().getNameValue(), "tony@example.com");
    }

    @Test
    public void searchUserByReferenceFilter() {
        // User.teams eq Team(team-alpha) -> /user/list?team=team-alpha
        stub(100, urlPathEqualTo("/user/list"), 200, fixture("user_list_tony.json"), Map.of("team", "team-alpha"));

        var teamRef = new ConnectorObjectReference(
                new ConnectorObjectBuilder()
                        .setObjectClass(new ObjectClass("Team"))
                        .setUid("team-alpha")
                        .setName("Alpha")
                        .build());
        var filter = FilterBuilder.equalTo(AttributeBuilder.build("teams", teamRef));
        var results = search(new ObjectClass("User"), filter);
        assertEquals(results.size(), 1);
        assertEquals(results.getFirst().getUid().getUidValue(), "user-tony");
    }

    @Test
    public void usersPagedAcrossPages() {
        // A paged-result window of one record maps onto one real page of the same size:
        // offset 1 -> page 1, offset 2 -> page 2 (page_size=1 is sent on the wire).
        stub(100, urlPathEqualTo("/user/list"), 200, fixture("user_list_p1.json"),
                Map.of("page", "1", "page_size", "1"));
        stub(90, urlPathEqualTo("/user/list"), 200, fixture("user_list_p2.json"),
                Map.of("page", "2", "page_size", "1"));

        var firstPage = windowedSearch(new ObjectClass("User"), 1);
        assertEquals(firstPage.size(), 1);
        assertEquals(firstPage.get(0).getUid().getUidValue(), "user-tony");

        var secondPage = windowedSearch(new ObjectClass("User"), 2);
        assertEquals(secondPage.size(), 1);
        assertEquals(secondPage.get(0).getUid().getUidValue(), "user-alice");
    }

    // --- VirtualKey -----------------------------------------------------------

    @Test
    public void searchAllVirtualKeys() {
        stub(100, urlPathEqualTo("/key/list"), 200, fixture("key_list.json"), Map.of("return_full_object", "true"));

        var results = search(new ObjectClass("VirtualKey"), null);
        assertEquals(results.size(), 3);

        var mgmt = byUid(results, "tok-key-management");
        assertEquals(mgmt.getName().getNameValue(), "sk-...kgUQ");
        assertEquals(refValue(ref(mgmt, "user")).getUid().getUidValue(), "user-tony");
        assertEquals(refValue(ref(mgmt, "organization")).getUid().getUidValue(), "org-1");
        assertNullish(findAttribute(mgmt, "team"));

        var teamKey = byUid(results, "tok-key-team");
        assertEquals(refValue(ref(teamKey, "team")).getUid().getUidValue(), "team-alpha");
        assertEquals(asString(attribute(teamKey, "soft_budget")), "10.0");
        assertEquals(val(attribute(teamKey, "blocked")), false);
        assertEquals(asList(attribute(teamKey, "models")), List.of("gpt-4o", "qwen3.8"));
    }

    @Test
    public void searchVirtualKeyByUid() {
        stub(100, urlPathEqualTo("/key/list"), 200, fixture("key_list_team.json"),
                Map.of("key_hash", "tok-key-team", "return_full_object", "true"));

        var results = search(new ObjectClass("VirtualKey"), new EqualsFilter(new Uid("tok-key-team")));
        assertEquals(results.size(), 1);
        assertEquals(results.getFirst().getUid().getUidValue(), "tok-key-team");
    }

    // --- Model -----------------------------------------------------------------

    @Test
    public void searchAllModels() {
        stub(100, urlPathEqualTo("/model_group/info"), 200, fixture("model_group_info.json"), Map.of());

        var results = search(new ObjectClass("Model"), null);
        assertEquals(results.size(), 2);

        var gpt = byUid(results, "gpt-4o");
        assertEquals(asList(attribute(gpt, "providers")), List.of("openai", "azure"));
        assertEquals(val(attribute(gpt, "mode")), "chat");
        assertEquals(val(attribute(gpt, "supports_vision")), true);
        assertEquals(val(attribute(gpt, "health_status")), "healthy");

        var qwen = byUid(results, "qwen3.8");
        assertEquals(val(attribute(qwen, "supports_reasoning")), true);
        assertEquals(val(attribute(qwen, "is_public_model_group")), false);
    }

    @Test
    public void searchModelByUid() {
        stub(100, urlPathEqualTo("/model_group/info"), 200, fixture("model_group_info_gpt.json"),
                Map.of("model_group", "gpt-4o"));

        var results = search(new ObjectClass("Model"), new EqualsFilter(new Uid("gpt-4o")));
        assertEquals(results.size(), 1);
        assertEquals(results.getFirst().getUid().getUidValue(), "gpt-4o");
    }

    // --- Endpoint ---------------------------------------------------------------

    @Test
    public void searchAllEndpoints() {
        stub(100, urlPathEqualTo("/v2/model/info"), 200, fixture("v2_model_info.json"), Map.of());
        stub(50, urlPathEqualTo("/v2/model/info"), 200, fixture("v2_model_info_empty.json"), Map.of("page", "2"));

        var results = search(new ObjectClass("Endpoint"), null);
        assertEquals(results.size(), 3);

        var ep1 = byUid(results, "ep-1111");
        assertEquals(ep1.getName().getNameValue(), "gpt-4o");
        assertEquals(val(attribute(ep1, "deployment_model")), "gpt-4o");
        assertEquals(val(attribute(ep1, "provider")), "openai");
        assertEquals(val(attribute(ep1, "api_base")), "https://api.openai.com/v1");
        assertEquals(val(attribute(ep1, "credential_name")), "OpenAI");
        assertEquals(refValue(ref(ep1, "model")).getUid().getUidValue(), "gpt-4o");
        assertEquals(asList(attribute(ep1, "tags")), List.of("prod"));
        assertEquals(asList(attribute(ep1, "access_groups")), List.of("alpha"));

        var ep2 = byUid(results, "ep-2222");
        assertEquals(val(attribute(ep2, "blocked")), true);
        assertEquals(val(attribute(ep2, "provider")), "azure");

        var ep3 = byUid(results, "ep-3333");
        assertEquals(val(attribute(ep3, "deployment_model")), "qwen3.8:27b-256k");
        assertEquals(val(attribute(ep3, "api_base")), "http://ollama.internal:11434");
    }

    @Test
    public void searchEndpointById() {
        stub(100, urlPathEqualTo("/v2/model/info"), 200, fixture("v2_model_info_ep2.json"),
                Map.of("modelId", "ep-2222"));

        var results = search(new ObjectClass("Endpoint"), new EqualsFilter(new Uid("ep-2222")));
        assertEquals(results.size(), 1);
        assertEquals(val(attribute(results.getFirst(), "blocked")), true);
    }

    // --- Agent -------------------------------------------------------------------

    @Test
    public void searchAllAgents() {
        stub(100, urlPathEqualTo("/v1/agents"), 200, fixture("agents.json"), Map.of());

        var results = search(new ObjectClass("Agent"), null);
        assertEquals(results.size(), 1);

        var agent = results.getFirst();
        assertEquals(agent.getUid().getUidValue(), "agent-1");
        assertEquals(agent.getName().getNameValue(), "OpenCode @ Spark");
        assertEquals(val(attribute(agent, "execution_mode")), "autonomous");
        assertEquals(val(attribute(agent, "identity_managed")), false);
        assertEquals(refs(agent, "keys"), List.of("tok-key-agent"));
    }

    @Test
    public void searchAgentByUid() {
        stub(100, urlPathEqualTo("/v1/agents/agent-1"), 200, fixture("agent_a1.json"), Map.of());

        var results = search(new ObjectClass("Agent"), new EqualsFilter(new Uid("agent-1")));
        assertEquals(results.size(), 1);
        assertEquals(results.getFirst().getName().getNameValue(), "OpenCode @ Spark");
    }

    @Test
    public void searchMissingAgentReturnsEmpty() {
        stub(100, urlPathEqualTo("/v1/agents/agent-missing"), 404, "", Map.of());

        var results = search(new ObjectClass("Agent"), new EqualsFilter(new Uid("agent-missing")));
        assertTrue(results.isEmpty());
    }

    // --- MCP ---------------------------------------------------------------------

    @Test
    public void searchAllMcpServers() {
        stub(100, urlPathEqualTo("/v1/mcp/server"), 200, fixture("mcp_servers.json"), Map.of());

        var results = search(new ObjectClass("MCP"), null);
        assertEquals(results.size(), 2);

        var mcp1 = byUid(results, "mcp-1");
        assertEquals(mcp1.getName().getNameValue(), "fetch");
        assertEquals(val(attribute(mcp1, "transport")), "sse");
        assertEquals(val(attribute(mcp1, "status")), "healthy");
        assertEquals(val(attribute(mcp1, "allow_all_keys")), true);
        assertTrue(refs(mcp1, "teams").isEmpty());

        var mcp2 = byUid(results, "mcp-2");
        assertEquals(val(attribute(mcp2, "auth_type")), "bearer");
        assertEquals(refs(mcp2, "teams"), List.of("team-alpha"));
        var teamRefs = asRef(mcp2, "teams");
        assertEquals(refValue(teamRefs.getFirst()).getName().getNameValue(), "Alpha");
        // Naive timestamps (no zone) in the fixture must parse as UTC - a strict
        // ZonedDateTime.parse used to throw DateTimeParseException on them.
        assertEquals(val(attribute(mcp2, "created_at")),
                LocalDateTime.of(2026, 11, 17, 10, 57, 45, 383_683_000).atZone(ZoneOffset.UTC));
    }

    @Test
    public void searchMcpServerByUid() {
        stub(100, urlPathEqualTo("/v1/mcp/server/mcp-2"), 200, fixture("mcp_server_mcp2.json"), Map.of());

        var results = search(new ObjectClass("MCP"), new EqualsFilter(new Uid("mcp-2")));
        assertEquals(results.size(), 1);
        assertEquals(results.getFirst().getUid().getUidValue(), "mcp-2");
    }

    // --- Guardrail -------------------------------------------------------------------

    @Test
    public void searchAllGuardrails() {
        stub(100, urlPathEqualTo("/guardrails/list"), 200, fixture("guardrails_list.json"), Map.of());

        var results = search(new ObjectClass("Guardrail"), null);
        assertEquals(results.size(), 1);
        assertEquals(results.getFirst().getUid().getUidValue(), "gr-1");
        assertEquals(results.getFirst().getName().getNameValue(), "pii-filter");
        assertEquals(val(attribute(results.getFirst(), "guardrail_definition_location")), "database");
    }

    @Test
    public void searchGuardrailByUid() {
        stub(100, urlPathEqualTo("/guardrails/gr-1/info"), 200, fixture("guardrail_gr1.json"), Map.of());

        var results = search(new ObjectClass("Guardrail"), new EqualsFilter(new Uid("gr-1")));
        assertEquals(results.size(), 1);
        assertEquals(results.getFirst().getUid().getUidValue(), "gr-1");
    }

    // --- Team ------------------------------------------------------------------------

    @Test
    public void searchAllTeams() {
        stub(100, urlPathEqualTo("/team/list"), 200, fixture("team_list.json"), Map.of());

        var results = search(new ObjectClass("Team"), null);
        assertEquals(results.size(), 2);

        var alpha = byUid(results, "team-alpha");
        assertEquals(alpha.getName().getNameValue(), "Alpha");
        assertEquals(val(attribute(alpha, "organization_id")), "org-1");
        assertEquals(refValue(ref(alpha, "organization")).getUid().getUidValue(), "org-1");
        assertEquals(refs(alpha, "members"), List.of("user-tony", "user-alice"));
        assertEquals(refs(alpha, "admins"), List.of("user-tony"));
        assertEquals(asList(attribute(alpha, "models")), List.of("gpt-4o"));

        var beta = byUid(results, "team-beta");
        assertTrue(refs(beta, "admins").isEmpty());
    }

    @Test
    public void searchTeamByUid() {
        // /team/info serves by-id reads; the team object lives under team_info.
        stub(100, urlPathEqualTo("/team/info"), 200, fixture("team_info_alpha.json"), Map.of("team_id", "team-alpha"));

        var results = search(new ObjectClass("Team"), new EqualsFilter(new Uid("team-alpha")));
        assertEquals(results.size(), 1);
        assertEquals(byUid(results, "team-alpha").getName().getNameValue(), "Alpha");
    }

    // --- Organization ------------------------------------------------------------------

    @Test
    public void searchAllOrganizations() {
        stub(100, urlPathEqualTo("/organization/list"), 200, fixture("organization_list.json"), Map.of());
        stub(50, urlPathEqualTo("/organization/info"), 200, fixture("organization_info.json"),
                Map.of("org_id", "org-1"));

        var results = search(new ObjectClass("Organization"), null);
        assertEquals(results.size(), 1);

        var org = results.getFirst();
        assertEquals(org.getUid().getUidValue(), "org-1");
        assertEquals(org.getName().getNameValue(), "Example Org");
        assertEquals(refs(org, "members"), List.of("user-tony", "user-alice"));
        assertEquals(refs(org, "teams"), List.of("team-alpha", "team-beta"));
        assertEquals(asString(attribute(org, "spend")), "545.5");
    }

    @Test
    public void searchOrganizationByUid() {
        // By-UID reads are routed to /organization/info, which requires organization_id.
        stub(100, urlPathEqualTo("/organization/info"), 200, fixture("organization_info.json"),
                Map.of("organization_id", "org-1"));

        var results = search(new ObjectClass("Organization"), new EqualsFilter(new Uid("org-1")));
        assertEquals(results.size(), 1);
        assertEquals(results.getFirst().getUid().getUidValue(), "org-1");
    }

    // --- helpers --------------------------------------------------------------------

    private void stub(int priority, com.github.tomakehurst.wiremock.matching.UrlPattern url, int status, String body,
                      Map<String, String> query) {
        var request = get(url);
        for (var entry : query.entrySet()) {
            request = request.withQueryParam(entry.getKey(), equalTo(entry.getValue()));
        }
        server.stubFor(request.atPriority(priority).willReturn(response(body, status)));
    }

    private ResponseDefinitionBuilder response(String body, int code) {
        if (code == 200) {
            return okJson(body);
        }
        return WireMock.status(code).withBody(body);
    }

    private String fixture(String name) {
        try (var in = MockSmokeTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in, "Missing fixture: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private List<ConnectorObject> search(ObjectClass objectClass, Filter filter) {
        var results = new ArrayList<ConnectorObject>();
        facade.search(objectClass, filter, results::add, new OperationOptions(Map.of()));
        return results;
    }

    /** Fetches the paged-result window starting at the given 1-based offset, one record wide. */
    private List<ConnectorObject> windowedSearch(ObjectClass objectClass, int offset) {
        var options = new OperationOptions(Map.of(
                OperationOptions.OP_PAGED_RESULTS_OFFSET, offset,
                OperationOptions.OP_PAGE_SIZE, 1));
        var results = new ArrayList<ConnectorObject>();
        facade.search(objectClass, null, results::add, options);
        return results;
    }

    private ConnectorObject byUid(List<ConnectorObject> results, String uid) {
        return results.stream()
                .filter(o -> uid.equals(o.getUid().getUidValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No object with UID " + uid + " in "
                        + results.stream().map(o -> o.getUid().getUidValue()).toList()));
    }

    private Attribute attribute(ConnectorObject object, String name) {
        var attribute = findAttribute(object, name);
        assertNotNull(attribute, object.getUid().getUidValue() + " has no attribute '" + name + "'");
        return attribute;
    }

    /** Looks up an attribute without failing when it is absent (null-valued attributes are omitted). */
    private Attribute findAttribute(ConnectorObject object, String name) {
        return object.getAttributes().stream()
                .filter(a -> a.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /** First value of an attribute; attribute values are carried as single-element lists. */
    private Object val(Attribute attribute) {
        if (attribute.getValue() == null || attribute.getValue().isEmpty()) {
            return null;
        }
        return attribute.getValue().getFirst();
    }

    private ConnectorObjectReference ref(ConnectorObject object, String name) {
        var attribute = findAttribute(object, name);
        if (attribute == null || attribute.getValue() == null || attribute.getValue().isEmpty()) {
            return null;
        }
        return (ConnectorObjectReference) attribute.getValue().getFirst();
    }

    private List<ConnectorObjectReference> asRef(ConnectorObject object, String name) {
        var value = attribute(object, name).getValue();
        if (value == null) {
            return List.of();
        }
        return value.stream().map(v -> (ConnectorObjectReference) v).collect(Collectors.toList());
    }

    private ConnectorObject refValue(ConnectorObjectReference reference) {
        return (ConnectorObject) reference.getValue();
    }

    private List<String> refs(ConnectorObject object, String name) {
        return asRef(object, name).stream()
                .map(r -> refValue(r).getUid().getUidValue())
                .collect(Collectors.toList());
    }

    private List<String> asList(Attribute attribute) {
        if (attribute.getValue() == null) {
            return List.of();
        }
        return attribute.getValue().stream().map(String::valueOf).collect(Collectors.toList());
    }

    /** Attribute values keep the wire (JSON) number formatting; compare as strings. */
    private String asString(Attribute attribute) {
        var values = attribute.getValue();
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.valueOf(values.getFirst());
    }

    private void assertNullish(Attribute attribute) {
        if (attribute == null) {
            return; // the attribute is simply absent for null-valued references
        }
        var values = attribute.getValue();
        assertTrue(values == null || values.isEmpty() || values.size() == 1 && values.getFirst() == null,
                attribute.getName() + " should be empty, got " + values);
    }
}
