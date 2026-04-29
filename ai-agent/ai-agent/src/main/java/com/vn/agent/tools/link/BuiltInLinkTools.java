package com.vn.agent.tools.link;

import com.vaadin.flow.router.Route;
import com.vn.agent.tools.ToolEntityResolver;
import com.vn.agent.tools.ToolResultFormatter;
import com.vn.agent.tools.ToolUserError;
import com.vn.agent.tools.UnknownEntityHints;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.flowui.view.ViewInfo;
import io.jmix.flowui.view.ViewRegistry;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Always-on link generator tools (independent of {@code ai-agent.tools.mutation.enabled} per
 * Phase 11 D-05). Produces deep-link URLs for the host shell using {@link ViewRegistry}
 * routes and the {@link ServerProperties}-derived servlet context path.
 *
 * <p>Both tools enforce the Phase 10 R4 uniform-opacity contract through
 * {@link ToolEntityResolver#resolveReadableEntityOrThrow}: unknown entity name, unknown
 * metaClass, and {@code LlmExposurePolicy.canReadEntity} denial all collapse to
 * {@code unknown_entity} (never {@code access_denied}). When a visible entity has no
 * registered list/detail view, the same {@code unknown_entity} code is returned — the LLM
 * cannot distinguish "no such entity" from "entity has no browsable view".
 *
 * <p>{@code generate_entity_detail_link} validates the supplied id as a UUID but does NOT
 * verify the row exists in the database (per D-05 contract — that is {@code get_record}'s
 * job). The id is URL-encoded before being appended to the route.
 *
 * <p>Mutation result schema stays clean ({@code {outcome, entityId, instanceName, ...}});
 * the LLM calls these tools separately when it wants to surface a verify-link.
 */
@Component
public class BuiltInLinkTools {

    private final ServerProperties serverProperties;
    private final ViewRegistry viewRegistry;
    private final ToolEntityResolver toolEntityResolver;
    private final ToolResultFormatter toolResultFormatter;

    public BuiltInLinkTools(ServerProperties serverProperties,
                            ViewRegistry viewRegistry,
                            ToolEntityResolver toolEntityResolver,
                            ToolResultFormatter toolResultFormatter) {
        this.serverProperties = serverProperties;
        this.viewRegistry = viewRegistry;
        this.toolEntityResolver = toolEntityResolver;
        this.toolResultFormatter = toolResultFormatter;
    }

    @Tool(name = "generate_entity_list_link", description = """
            Generate a relative URL to the host application's list view for a given entity.

            MANDATORY WORKFLOW:
            1. Call list_entities first to confirm the entity name is visible to you.
            2. Pass the EXACT entity name (e.g. 'sample_Customer'), never a display label
               such as 'Customer' and never a route fragment such as 'customers'.
            3. Render the returned URL as a Markdown link in your reply to the user.

            INPUT CONTRACT:
            - entityName: exact internal name from list_entities / agent.entities.

            PARAMETER FORMATS:
            - entityName: String, Jmix metaClass name (host-prefixed, e.g. 'sample_Customer').

            ERROR HANDLING:
            - unknown_entity: entity is hidden, unknown to the metamodel, OR has no list view
              registered with the host. Recovery: call list_entities once, retry with the
              corrected name; if the result is still unknown_entity, tell the user the entity
              is not browsable and do NOT guess.

            STRICTNESS + EXAMPLES:
            CORRECT: generate_entity_list_link("sample_Customer") -> {"url":"/sample-app/customers"}
            INCORRECT: generate_entity_list_link("Customer")        (display label, not entity name)
            INCORRECT: generate_entity_list_link("customers")       (route fragment, not entity name)
            INCORRECT: generate_entity_list_link("")                (blank — returns unknown_entity)
            """)
    public String generateEntityListLink(
            @ToolParam(description = "Exact entity name from list_entities; never a display label or route fragment")
            String entityName) {
        try {
            MetaClass metaClass = toolEntityResolver.resolveReadableEntityOrThrow(entityName);
            String listViewId = viewRegistry.getListViewId(metaClass);
            String route = findRouteForViewId(listViewId);
            if (StringUtils.isEmpty(route)) {
                // No registered list view OR no @Route on the controller — collapse to
                // unknown_entity per D-05 (uniform-opacity for "not browsable").
                throw new ToolUserError("unknown_entity",
                        "no entity named " + entityName,
                        UnknownEntityHints.AS_LIST);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("url", contextPath() + "/" + route);
            return toolResultFormatter.toJson(payload);
        } catch (ToolUserError err) {
            return toolResultFormatter.error(err);
        }
    }

    @Tool(name = "generate_entity_detail_link", description = """
            Generate a relative URL to the host application's detail (edit) view for a given
            entity row. Does NOT verify the row exists — call get_record for that.

            MANDATORY WORKFLOW:
            1. Call list_entities to confirm the entity name is visible to you.
            2. Use an entityId returned by create_record / update_record / get_record /
               find_records.
            3. This tool does NOT verify the id exists; the URL may still 404 in the host
               shell. Call get_record first if you must confirm existence.

            INPUT CONTRACT:
            - entityName: exact internal name from list_entities.
            - entityId: 36-character hyphenated UUID string from a prior tool call.

            PARAMETER FORMATS:
            - entityName: String, Jmix metaClass name (host-prefixed).
            - entityId: hyphenated UUID, e.g. 'f1a2b3c4-5d6e-7890-abcd-ef0123456789'.

            ERROR HANDLING:
            - unknown_entity: entity hidden, unknown, OR has no detail view route.
              Recovery: surface to user; do not retry without a valid entity name.
            - parameter_conversion_error: entityId is not a valid UUID.
              Recovery: re-read the id from the prior tool call; do not invent an id.

            STRICTNESS + EXAMPLES:
            CORRECT: generate_entity_detail_link("sample_Customer", "f1a2b3c4-5d6e-7890-abcd-ef0123456789")
            INCORRECT: generate_entity_detail_link("sample_Customer", "Alice")  (label, not id)
            INCORRECT: generate_entity_detail_link("Customer", "<uuid>")        (label, not entity name)
            INCORRECT: generate_entity_detail_link("sample_Customer", "")       (blank id)
            """)
    public String generateEntityDetailLink(
            @ToolParam(description = "Exact entity name from list_entities; never a display label")
            String entityName,
            @ToolParam(description = "UUID returned by create_record / update_record / get_record / find_records")
            String entityId) {
        try {
            MetaClass metaClass = toolEntityResolver.resolveReadableEntityOrThrow(entityName);
            String detailViewId = viewRegistry.getDetailViewId(metaClass);
            String route = findRouteForViewId(detailViewId);
            if (StringUtils.isEmpty(route)) {
                throw new ToolUserError("unknown_entity",
                        "no entity named " + entityName,
                        UnknownEntityHints.AS_LIST);
            }

            // Detail routes typically end in "/:id" — strip the placeholder segment.
            String trimmed = StringUtils.substringBeforeLast(route, "/");
            if (StringUtils.isEmpty(trimmed)) {
                trimmed = route;
            }

            // Validate the id as a UUID (Phase 11 D-01 — v1.1 mutation tools are UUID-only;
            // the link tool follows the same id contract). Do NOT verify existence — that
            // is get_record's job (D-05).
            UUID uuid = UUID.fromString(entityId);
            String encodedId = URLEncoder.encode(uuid.toString(), StandardCharsets.UTF_8);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("url", contextPath() + "/" + trimmed + "/" + encodedId);
            return toolResultFormatter.toJson(payload);
        } catch (IllegalArgumentException invalidUuid) {
            return toolResultFormatter.error(new ToolUserError(
                    "parameter_conversion_error",
                    "entityId must be a UUID",
                    List.of("use the UUID returned by get_record / create_record / update_record / find_records")));
        } catch (NullPointerException nullId) {
            // UUID.fromString throws NPE on null id; surface as parameter_conversion_error.
            return toolResultFormatter.error(new ToolUserError(
                    "parameter_conversion_error",
                    "entityId must not be null",
                    List.of("pass the UUID string returned by get_record / create_record / update_record / find_records")));
        } catch (ToolUserError err) {
            return toolResultFormatter.error(err);
        }
    }

    /**
     * Resolves a {@link ViewRegistry}-known view id to the {@link Route#value()} declared on
     * the controller class. Returns {@code null} when the view id is not registered or the
     * controller carries no {@link Route} annotation.
     */
    @Nullable
    private String findRouteForViewId(@Nullable String viewId) {
        if (StringUtils.isEmpty(viewId)) {
            return null;
        }
        Optional<ViewInfo> viewInfo = viewRegistry.findViewInfo(viewId);
        if (viewInfo.isEmpty()) {
            return null;
        }
        Route routeAnnotation = AnnotationUtils.findAnnotation(
                viewInfo.get().getControllerClass(), Route.class);
        return routeAnnotation != null ? routeAnnotation.value() : null;
    }

    private String contextPath() {
        String contextPath = serverProperties.getServlet().getContextPath();
        return contextPath == null ? "" : contextPath;
    }
}
