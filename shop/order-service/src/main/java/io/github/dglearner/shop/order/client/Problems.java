package io.github.dglearner.shop.order.client;

import java.io.IOException;
import org.springframework.http.client.ClientHttpResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/** Reads an RFC 9457 problem body; anything that is not JSON reads as an empty problem. */
final class Problems {

    static JsonNode read(JsonMapper json, ClientHttpResponse response) throws IOException {
        byte[] body = response.getBody().readAllBytes();
        if (body.length == 0) {
            return MissingNode.getInstance();
        }
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            return MissingNode.getInstance();
        }
    }

    private Problems() {
    }
}
