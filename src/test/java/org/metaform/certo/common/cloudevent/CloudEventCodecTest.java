package org.metaform.certo.common.cloudevent;

import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.AcceptanceStatusData;
import org.metaform.certo.common.web.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link CloudEventCodec}: envelope validation, single/batch splitting, dedup key, and typed decode. */
class CloudEventCodecTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final CloudEventCodec codec = new CloudEventCodec(mapper);

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String validEnvelope(String extra) {
        return "{\"specversion\":\"1.0\",\"type\":\"org.catena-x.ccm.X.v1\",\"source\":\"urn:bpn:BPNL-C\","
                + "\"id\":\"id-1\",\"sourcebpn\":\"BPNL-C\"" + extra + "}";
    }

    @Test
    void toEventNodes_acceptsASingleObject() {
        assertThat(codec.toEventNodes(bytes(validEnvelope("")))).hasSize(1);
    }

    @Test
    void toEventNodes_splitsABatchArray() {
        var batch = "[" + validEnvelope("") + "," + validEnvelope("") + "]";
        assertThat(codec.toEventNodes(bytes(batch))).hasSize(2);
    }

    @Test
    void toEventNodes_rejectsEmptyOrNonJsonBodies() {
        assertBadRequest(() -> codec.toEventNodes(new byte[0]));
        assertBadRequest(() -> codec.toEventNodes(bytes("not json{")));
        assertBadRequest(() -> codec.toEventNodes(bytes("42")));   // neither object nor array
    }

    @Test
    void validateEnvelope_requiresEachMandatoryAttribute() {
        assertBadRequest(() -> codec.toEventNodes(bytes(
                "{\"type\":\"t\",\"source\":\"s\",\"id\":\"i\",\"sourcebpn\":\"b\"}")));         // no specversion
        assertBadRequest(() -> codec.toEventNodes(bytes(
                "{\"specversion\":\"1.0\",\"source\":\"s\",\"id\":\"i\",\"sourcebpn\":\"b\"}"))); // no type
        assertBadRequest(() -> codec.toEventNodes(bytes(
                "{\"specversion\":\"1.0\",\"type\":\"t\",\"id\":\"i\",\"sourcebpn\":\"b\"}")));    // no source
        assertBadRequest(() -> codec.toEventNodes(bytes(
                "{\"specversion\":\"1.0\",\"type\":\"t\",\"source\":\"s\",\"sourcebpn\":\"b\"}"))); // no id
        assertBadRequest(() -> codec.toEventNodes(bytes(
                "{\"specversion\":\"1.0\",\"type\":\"t\",\"source\":\"s\",\"id\":\"i\"}")));       // no sourcebpn
    }

    @Test
    void timeAttribute_isOptionalOnReceipt() {
        // Valid envelope without a 'time' — accepted (lenient inbound, per Postel).
        assertThat(codec.toEventNodes(bytes(validEnvelope("")))).hasSize(1);
    }

    @Test
    void typeOf_readsTheTypeAttribute() {
        var node = codec.toEventNodes(bytes(validEnvelope(""))).get(0);
        assertThat(codec.typeOf(node)).isEqualTo("org.catena-x.ccm.X.v1");
    }

    @Test
    void dedupKey_isSourcePlusId() {
        var event = new CloudEvent<>("1.0", "type", "urn:bpn:BPNL-C", "subj", "id-9",
                null, "application/json", "schema", "BPNL-C", "data");
        assertThat(codec.dedupKey(event)).isEqualTo("urn:bpn:BPNL-C id-9");
    }

    @Test
    void decode_bindsTheDataPayloadToTheGivenType() {
        var json = validEnvelope(",\"data\":{\"exchangeId\":\"e-1\",\"certificateId\":\"c-1\",\"status\":\"ACCEPTED\"}");
        var node = codec.toEventNodes(bytes(json)).get(0);
        CloudEvent<AcceptanceStatusData> event = codec.decode(node, AcceptanceStatusData.class);
        assertThat(event.data().exchangeId()).isEqualTo("e-1");
        assertThat(event.data().status()).isEqualTo(AcceptanceStatus.ACCEPTED);
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
