/*
 * Copyright (c) 2026 Evolveum and contributors
 *
 * This work is licensed under European Union Public License v1.2. See LICENSE file for details.
 *
 * LiteLLM connector - reference attributes declared as relationships.
 *
 * midPoint requires every reference attribute to carry a role in its reference
 * (the subject or the object side of the relationship), which the declarative
 * YAML front-end cannot express - so the references of the LiteLLM connector are
 * declared here in Groovy instead of the YAML schemas:
 *
 *  - where both sides of a relationship are available in the payloads, a full
 *    relationship(...) declaration is used; the framework assigns the subject /
 *    object roles, the counterparty object classes and the relationship name
 *    (the reference subtype) to both attributes automatically;
 *  - where only one side exists in the payloads, a plain reference(...) with an
 *    explicit role and subtype is used.
 *
 * The entity that holds the relationship (a member, a key, a deployment) is the
 * SUBJECT; the container it points at (a team, an organization, an agent, a
 * model group) is the OBJECT.
 */

import com.evolveum.polygon.conndev.api.BasicJsonPathFormat
import org.identityconnectors.framework.common.objects.AttributeInfo
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder
import org.identityconnectors.framework.common.objects.ConnectorObjectReference
import org.identityconnectors.framework.common.objects.ObjectClass

// ---------------------------------------------------------------------------
// Two-sided relationships - both sides are read directly from the payloads.
// ---------------------------------------------------------------------------

relationship("UserTeamMembership") {
    subject("User") {
        attribute("teams") {
            description "Teams the user is a member of"
            multiValued(true)
            json {
                type "string"
                path BasicJsonPathFormat.INSTANCE.parse('$.teams')
                implementation {
                    deserialize {
                        var node = value
                        if (node == null || node.isNull()) { return null }
                        var uid = node.asText()
                        if (uid == null || uid.isEmpty()) { return null }
                        return new ConnectorObjectReference(
                            new ConnectorObjectBuilder()
                                .setObjectClass(new ObjectClass("Team"))
                                .setUid(uid)
                                .setName(null)
                                .build())
                    }
                }
            }
        }
    }
    object("Team") {
        attribute("members") {
            description "Users that are members of the team"
            multiValued(true)
            json {
                type "string"
                path BasicJsonPathFormat.INSTANCE.parse('$.members')
                implementation {
                    deserialize {
                        var node = value
                        if (node == null || node.isNull()) { return null }
                        var uid = node.asText()
                        if (uid == null || uid.isEmpty()) { return null }
                        return new ConnectorObjectReference(
                            new ConnectorObjectBuilder()
                                .setObjectClass(new ObjectClass("User"))
                                .setUid(uid)
                                .setName(null)
                                .build())
                    }
                }
            }
        }
    }
}

relationship("UserOrganizationMembership") {
    subject("User") {
        attribute("organization") {
            description "Organization the user belongs to"
            json {
                type "string"
                path BasicJsonPathFormat.INSTANCE.parse('$.organization_id')
                implementation {
                    deserialize {
                        var node = value
                        if (node == null || node.isNull()) { return null }
                        var uid = node.asText()
                        if (uid == null || uid.isEmpty()) { return null }
                        return new ConnectorObjectReference(
                            new ConnectorObjectBuilder()
                                .setObjectClass(new ObjectClass("Organization"))
                                .setUid(uid)
                                .setName(null)
                                .build())
                    }
                }
            }
        }
    }
    object("Organization") {
        attribute("members") {
            description "Users that belong to the organization"
            multiValued(true)
            json {
                type "string"
                path BasicJsonPathFormat.INSTANCE.parse('$.members')
                implementation {
                    deserialize {
                        var node = value
                        if (node == null || node.isNull()) { return null }
                        // Members are LiteLLM_OrganizationMembershipTable rows carrying the user id.
                        var uid = node.isObject() ? node.get("user_id")?.asText() : node.asText()
                        if (uid == null || uid.isEmpty()) { return null }
                        var name = node.isObject() ? node.get("user_email")?.asText() : null
                        return new ConnectorObjectReference(
                            new ConnectorObjectBuilder()
                                .setObjectClass(new ObjectClass("User"))
                                .setUid(uid)
                                .setName(name)
                                .build())
                    }
                }
            }
        }
    }
}

relationship("TeamOrganizationMembership") {
    subject("Team") {
        attribute("organization") {
            description "Organization the team belongs to"
            json {
                type "string"
                path BasicJsonPathFormat.INSTANCE.parse('$.organization_id')
                implementation {
                    deserialize {
                        var node = value
                        if (node == null || node.isNull()) { return null }
                        var uid = node.asText()
                        if (uid == null || uid.isEmpty()) { return null }
                        return new ConnectorObjectReference(
                            new ConnectorObjectBuilder()
                                .setObjectClass(new ObjectClass("Organization"))
                                .setUid(uid)
                                .setName(null)
                                .build())
                    }
                }
            }
        }
    }
    object("Organization") {
        attribute("teams") {
            description "Teams that belong to the organization"
            multiValued(true)
            json {
                type "string"
                path BasicJsonPathFormat.INSTANCE.parse('$.teams')
                implementation {
                    deserialize {
                        var node = value
                        if (node == null || node.isNull()) { return null }
                        // Teams arrive as LiteLLM_TeamTable rows carrying the team id.
                        var uid = node.isObject() ? node.get("team_id")?.asText() : node.asText()
                        if (uid == null || uid.isEmpty()) { return null }
                        var name = node.isObject() ? node.get("team_alias")?.asText() : null
                        return new ConnectorObjectReference(
                            new ConnectorObjectBuilder()
                                .setObjectClass(new ObjectClass("Team"))
                                .setUid(uid)
                                .setName(name)
                                .build())
                    }
                }
            }
        }
    }
}

relationship("VirtualKeyAgentAssignment") {
    subject("Agent") {
        attribute("keys") {
            description "Virtual keys issued for the agent"
            multiValued(true)
            json {
                type "string"
                path BasicJsonPathFormat.INSTANCE.parse('$.keys')
                implementation {
                    deserialize {
                        var node = value
                        if (node == null || node.isNull()) { return null }
                        var uid = node.get("token")?.asText()
                        if (uid == null || uid.isEmpty()) { return null }
                        return new ConnectorObjectReference(
                                new ConnectorObjectBuilder()
                                        .setObjectClass(new ObjectClass("VirtualKey"))
                                        .setUid(uid)
                                        .setName(node.get("key_name")?.asText())
                                        .build())
                    }
                }
            }
        }
    }
    object("VirtualKey") {
        attribute("agent") {
            description "Agent the key was issued for"
            json {
                type "string"
                path BasicJsonPathFormat.INSTANCE.parse('$.agent_id')
                implementation {
                    deserialize {
                        var node = value
                        if (node == null || node.isNull()) { return null }
                        var uid = node.asText()
                        if (uid == null || uid.isEmpty()) { return null }
                        return new ConnectorObjectReference(
                            new ConnectorObjectBuilder()
                                .setObjectClass(new ObjectClass("Agent"))
                                .setUid(uid)
                                .setName(null)
                                .build())
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// One-sided references - the counterpart side does not exist in the payloads,
// so the relationship is declared on the side that carries the data, with an
// explicit role and subtype (the relationship name midPoint shows as the
// association type).
// ---------------------------------------------------------------------------

objectClass("VirtualKey") {
    reference("user") {
        description "User the key belongs to"
        objectClass "User"
        role(AttributeInfo.RoleInReference.SUBJECT)
        subtype "VirtualKeyUserAssignment"
        json {
            type "string"
            path BasicJsonPathFormat.INSTANCE.parse('$.user_id')
            implementation {
                deserialize {
                    var node = value
                    if (node == null || node.isNull()) { return null }
                    var uid = node.asText()
                    if (uid == null || uid.isEmpty()) { return null }
                    return new ConnectorObjectReference(
                        new ConnectorObjectBuilder()
                            .setObjectClass(new ObjectClass("User"))
                            .setUid(uid)
                            .setName(null)
                            .build())
                }
            }
        }
    }
    reference("team") {
        description "Team the key belongs to"
        objectClass "Team"
        role(AttributeInfo.RoleInReference.SUBJECT)
        subtype "VirtualKeyTeamAssignment"
        json {
            type "string"
            path BasicJsonPathFormat.INSTANCE.parse('$.team_id')
            implementation {
                deserialize {
                    var node = value
                    if (node == null || node.isNull()) { return null }
                    var uid = node.asText()
                    if (uid == null || uid.isEmpty()) { return null }
                    return new ConnectorObjectReference(
                        new ConnectorObjectBuilder()
                            .setObjectClass(new ObjectClass("Team"))
                            .setUid(uid)
                            .setName(null)
                            .build())
                }
            }
        }
    }
    reference("organization") {
        description "Organization the key belongs to"
        objectClass "Organization"
        role(AttributeInfo.RoleInReference.SUBJECT)
        subtype "VirtualKeyOrganizationAssignment"
        json {
            type "string"
            // GET /key/list reports the field as org_id, GET /key/info as
            // organization_id - the root is read and both spellings are handled.
            path BasicJsonPathFormat.INSTANCE.parse('$')
            implementation {
                deserialize {
                    var node = value
                    if (node == null || node.isNull() || !node.isObject()) { return null }
                    var idNode = node.get("organization_id") != null ? node.get("organization_id") : node.get("org_id")
                    if (idNode == null || idNode.isNull()) { return null }
                    var uid = idNode.asText()
                    if (uid == null || uid.isEmpty()) { return null }
                    return new ConnectorObjectReference(
                        new ConnectorObjectBuilder()
                            .setObjectClass(new ObjectClass("Organization"))
                            .setUid(uid)
                            .setName(null)
                            .build())
                }
            }
        }
    }
}

objectClass("Team") {
    // The users that administer a team: the User is the subject of the
    // relationship, so this attribute sits on the object side.
    reference("admins") {
        description "Users that are administrators of the team"
        objectClass "User"
        role(AttributeInfo.RoleInReference.OBJECT)
        subtype "UserTeamAdmin"
        multiValued(true)
        json {
            type "string"
            path BasicJsonPathFormat.INSTANCE.parse('$.admins')
            implementation {
                deserialize {
                    var node = value
                    if (node == null || node.isNull()) { return null }
                    var uid = node.asText()
                    if (uid == null || uid.isEmpty()) { return null }
                    return new ConnectorObjectReference(
                        new ConnectorObjectBuilder()
                            .setObjectClass(new ObjectClass("User"))
                            .setUid(uid)
                            .setName(null)
                            .build())
                }
            }
        }
    }
}

objectClass("MCP") {
    reference("teams") {
        description "Teams granted access to the server"
        objectClass "Team"
        role(AttributeInfo.RoleInReference.SUBJECT)
        subtype "McpServerTeamMembership"
        multiValued(true)
        json {
            type "string"
            path BasicJsonPathFormat.INSTANCE.parse('$.teams')
            implementation {
                deserialize {
                    var node = value
                    if (node == null || node.isNull()) { return null }
                    // Team grants arrive as flat maps {"<team_id>": alias} (or with explicit
                    // team_id/team_alias fields in newer payloads); both shapes are handled.
                    var uid = null
                    var name = null
                    if (node.isObject()) {
                        if (node.has("team_id")) {
                            uid = node.get("team_id")?.asText()
                            name = node.get("team_alias")?.asText()
                        } else if (!node.isEmpty()) {
                            var entry = node.fields().next()
                            uid = entry.getKey()
                            name = entry.getValue() == null || entry.getValue().isNull() ? null : entry.getValue().asText()
                        }
                    } else {
                        uid = node.asText()
                    }
                    if (uid == null || uid.isEmpty()) { return null }
                    return new ConnectorObjectReference(
                        new ConnectorObjectBuilder()
                            .setObjectClass(new ObjectClass("Team"))
                            .setUid(uid)
                            .setName(name)
                            .build())
                }
            }
        }
    }
}

objectClass("Endpoint") {
    reference("model") {
        description "Model group the deployment belongs to"
        objectClass "Model"
        role(AttributeInfo.RoleInReference.SUBJECT)
        subtype "ModelDeployment"
        json {
            type "string"
            path BasicJsonPathFormat.INSTANCE.parse('$.model_name')
            implementation {
                deserialize {
                    var node = value
                    if (node == null || node.isNull()) { return null }
                    var uid = node.asText()
                    if (uid == null || uid.isEmpty()) { return null }
                    return new ConnectorObjectReference(
                        new ConnectorObjectBuilder()
                            .setObjectClass(new ObjectClass("Model"))
                            .setUid(uid)
                            .setName(null)
                            .build())
                }
            }
        }
    }
}
