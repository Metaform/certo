package org.metaform.certo.protocol.ccm240.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The shared message header of the CX-0135 v2.4.0 Company Certificate Notification API. The v3
 * equivalent is the CloudEvents envelope; the adapter maps between them
 * ({@code context} &rarr; event {@code type}, {@code messageId} &rarr; {@code id},
 * {@code senderBpn} &rarr; {@code source}/{@code sourcebpn}, {@code receiverBpn} &rarr; {@code subject}).
 *
 * @param context           the message-type discriminator (see {@link Ccm240Contexts})
 * @param messageId         UUID uniquely identifying this message
 * @param senderBpn         BPNL of the sending party
 * @param receiverBpn       BPNL of the receiving party
 * @param sentDateTime      ISO-8601 timestamp the message was sent
 * @param version           aspect-model version of the message
 * @param relatedMessageId  id of a message this one relates to, if any
 * @param senderFeedbackUrl the sender's endpoint to send feedback to (push/status/available only)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Ccm240Header(
        String context,
        String messageId,
        String senderBpn,
        String receiverBpn,
        String sentDateTime,
        String version,
        String relatedMessageId,
        String senderFeedbackUrl) {
}
