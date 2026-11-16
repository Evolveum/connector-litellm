/*
 * Copyright (c) 2026 Evolveum and contributors
 *
 * This work is licensed under European Union Public License v1.2. See LICENSE file for details.
 *
 */
package com.evolveum.polygon.litellm;

import com.evolveum.polygon.scimrest.groovy.impl.ManifestBasedConnector;
import com.evolveum.polygon.scimrest.groovy.impl.ReadOnlyConfiguration;
import org.identityconnectors.framework.common.objects.AttributeInfo;
import org.identityconnectors.framework.common.objects.EmbeddedObject;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Validates the schema built from the LiteLLM manifest scripts without touching any HTTP endpoint.
 * Guarantees that the connector exposes exactly the declared inventory classes, each with UID and
 * NAME mappings, flat (non-embedded) plain attributes and the expected reference attributes.
 */
public class SchemaSmokeTest {

    private static final List<String> OBJECT_CLASSES = List.of(
            "User", "VirtualKey", "Model", "Endpoint", "Agent", "MCP", "Guardrail", "Team", "Organization");

    /** The role strings midPoint's schema parser accepts on reference attributes. */
    private static final String SUBJECT = AttributeInfo.RoleInReference.SUBJECT.toString();
    private static final String OBJECT = AttributeInfo.RoleInReference.OBJECT.toString();

    private ManifestBasedConnector newConnector() {
        var connector = new ManifestBasedConnector();
        connector.init(new ReadOnlyConfiguration());
        return connector;
    }

    private ObjectClassInfo objectClass(String name) {
        var info = newConnector().schema().findObjectClassInfo(name);
        assertNotNull(info, "Object class " + name + " is missing from the schema");
        return info;
    }

    private AttributeInfo attribute(ObjectClassInfo oc, String name) {
        return oc.getAttributeInfo().stream()
                .filter(a -> a.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(oc.getType() + " has no attribute '" + name + "'"));
    }

    @Test
    public void allObjectClassesArePresent() {
        for (var name : OBJECT_CLASSES) {
            var info = objectClass(name);
            assertFalse(info.isEmbedded(), name + " must be a top-level object class");
        }
    }

    @Test
    public void everyClassHasUidAndName() {
        for (var name : OBJECT_CLASSES) {
            var info = objectClass(name);
            assertNotNull(attribute(info, "__UID__"), name + " must map UID");
            assertNotNull(attribute(info, "__NAME__"), name + " must map NAME");
        }
    }

    @Test
    public void attributesAreFlat() {
        var schema = newConnector().schema();
        for (var info : schema.getObjectClassInfo()) {
            if (!OBJECT_CLASSES.contains(info.getType())) {
                continue;
            }
            assertFalse(info.isEmbedded(), info.getType() + " must not be embedded");
            for (var attr : info.getAttributeInfo()) {
                assertFalse(attr.isEmbedded(), info.getType() + "." + attr.getName() + " must not be embedded");
                if (!attr.isReference()) {
                    assertFalse(EmbeddedObject.class.equals(attr.getType()),
                            info.getType() + "." + attr.getName() + " must be a flat attribute, got an embedded object type");
                }
            }
        }
    }

    @Test
    public void referenceAttributesPointToTheRightClasses() {
        // role / subtype expectations follow relationships.schema.groovy: the entity that
        // holds the relationship (member, key, deployment) is the SUBJECT of the relationship,
        // the container it points at is the OBJECT.
        assertReference("User", "teams", "Team", true, SUBJECT, "UserTeamMembership");
        assertReference("User", "organization", "Organization", false, SUBJECT, "UserOrganizationMembership");

        assertReference("VirtualKey", "user", "User", false, SUBJECT, "VirtualKeyUserAssignment");
        assertReference("VirtualKey", "team", "Team", false, SUBJECT, "VirtualKeyTeamAssignment");
        assertReference("VirtualKey", "agent", "Agent", false, OBJECT, "VirtualKeyAgentAssignment");
        assertReference("VirtualKey", "organization", "Organization", false, SUBJECT, "VirtualKeyOrganizationAssignment");

        assertReference("Endpoint", "model", "Model", false, SUBJECT, "ModelDeployment");

        assertReference("Agent", "keys", "VirtualKey", true, SUBJECT, "VirtualKeyAgentAssignment");

        assertReference("MCP", "teams", "Team", true, SUBJECT, "McpServerTeamMembership");

        assertReference("Team", "organization", "Organization", false, SUBJECT, "TeamOrganizationMembership");
        assertReference("Team", "members", "User", true, OBJECT, "UserTeamMembership");
        assertReference("Team", "admins", "User", true, OBJECT, "UserTeamAdmin");

        assertReference("Organization", "members", "User", true, OBJECT, "UserOrganizationMembership");
        assertReference("Organization", "teams", "Team", true, OBJECT, "TeamOrganizationMembership");

        // Model and Guardrail carry no references.
        for (var name : Set.of("Model", "Guardrail")) {
            var info = objectClass(name);
            var references = info.getAttributeInfo().stream().filter(AttributeInfo::isReference).count();
            assertEquals(references, 0, name + " must not declare reference attributes");
        }
    }

    /** Every reference attribute must carry a role - midPoint fails schema parsing otherwise. */
    @Test
    public void everyReferenceHasARole() {
        var schema = newConnector().schema();
        for (var info : schema.getObjectClassInfo()) {
            if (!OBJECT_CLASSES.contains(info.getType())) {
                continue;
            }
            for (var attr : info.getAttributeInfo()) {
                if (!attr.isReference()) {
                    continue;
                }
                var role = attr.getRoleInReference();
                assertTrue(SUBJECT.equals(role) || OBJECT.equals(role),
                        info.getType() + "." + attr.getName() + " reference is missing a role (got '" + role + "')");
                assertNotNull(attr.getSubtype(),
                        info.getType() + "." + attr.getName() + " reference is missing a relationship name (subtype)");
            }
        }
    }

    private void assertReference(String objectClass, String attribute, String target, boolean multiValued,
                                 String role, String subtype) {
        var info = objectClass(objectClass);
        var ref = attribute(info, attribute);
        assertTrue(ref.isReference(), objectClass + "." + attribute + " must be a reference attribute");
        assertEquals(ref.getReferencedObjectClassName(), target,
                objectClass + "." + attribute + " must reference " + target);
        assertEquals(ref.isMultiValued(), multiValued,
                objectClass + "." + attribute + " multiValued mismatch");
        assertEquals(ref.getRoleInReference(), role,
                objectClass + "." + attribute + " role mismatch");
        assertEquals(ref.getSubtype(), subtype,
                objectClass + "." + attribute + " subtype (relationship name) mismatch");
    }
}
