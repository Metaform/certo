package org.metaform.certo.common.cloudevent;

import org.metaform.certo.common.web.ApiException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes inbound CloudEvents request bodies. A body may be a single CloudEvent (JSON object) or a
 * batch (JSON array), per CX-0000 &sect;4; both are normalized to a list. The concrete {@code data}
 * payload type is selected by the caller after inspecting the event {@code type}.
 */
@Component
public class CloudEventCodec {

    private final ObjectMapper mapper;

    public CloudEventCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Parses a request body and splits a single-event or batch payload into individual event nodes. */
    public List<JsonNode> toEventNodes(byte[] body) {
        if (body == null || body.length == 0) {
            throw ApiException.badRequest("Request body is empty");
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Request body is not valid JSON: " + rootMessage(e));
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            throw ApiException.badRequest("Request body is empty");
        }
        var nodes = new ArrayList<JsonNode>();
        if (root.isArray()) {
            root.forEach(nodes::add);
        } else if (root.isObject()) {
            nodes.add(root);
        } else {
            throw ApiException.badRequest("Request body must be a CloudEvent object or an array of CloudEvents");
        }
        if (nodes.isEmpty()) {
            throw ApiException.badRequest("Request body contains no events");
        }
        return nodes;
    }

    /** Reads the CloudEvents {@code type} attribute from an event node. */
    public String typeOf(JsonNode eventNode) {
        var type = eventNode.get("type");
        if (type == null || !type.isString()) {
            throw ApiException.badRequest("CloudEvent is missing a 'type' attribute");
        }
        return type.asString();
    }

    /** Binds an event node to a {@link CloudEvent} with the given {@code data} payload type. */
    public <T> CloudEvent<T> decode(JsonNode eventNode, Class<T> dataType) {
        JavaType javaType = mapper.getTypeFactory().constructParametricType(CloudEvent.class, dataType);
        try {
            return mapper.convertValue(eventNode, javaType);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Malformed CloudEvent: " + rootMessage(e));
        }
    }

    private static String rootMessage(Throwable t) {
        var cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
