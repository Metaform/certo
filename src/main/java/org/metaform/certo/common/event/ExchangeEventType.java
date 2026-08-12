package org.metaform.certo.common.event;

import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;

import java.util.EnumMap;
import java.util.Map;

/**
 * The published catalogue of certificate-exchange events: one entry per CX-0135 &sect;2.1.3 status,
 * binding it to the NATS subject it is published on and the CloudEvents {@code type} it carries.
 *
 * <p>Subject and type live together here on purpose — they are the two halves of this component's
 * public contract, and a consumer routing on one while switching on the other must never see them
 * disagree. {@code ExchangeEventTypeTest} asserts the mapping is total over both status enums, so a
 * status added to {@link FulfillmentStatus} or {@link AcceptanceStatus} fails the build here rather
 * than silently emitting nothing.
 *
 * <p>The {@code events.} prefix is not cosmetic: it is the subject space the platform's
 * {@code edc-events} JetStream stream captures ({@code subjects: "events.>"}), and the only one the
 * NATS permission matrix grants publishers. Leaves are lowerCamelCase, matching the EDC event bridge
 * (e.g. {@code events.transfer.process.deprovisioningRequested}). Types follow the CX-0000 &sect;2.3
 * reverse-DNS convention already used by {@link org.metaform.certo.common.cloudevent.CcmEvents}.
 */
public enum ExchangeEventType {

    REQUESTED(ExchangePhase.FULFILLMENT, "requested", "CertificateExchangeRequested"),
    ACKNOWLEDGED(ExchangePhase.FULFILLMENT, "acknowledged", "CertificateExchangeAcknowledged"),
    CERTIFICATION_REQUESTED(ExchangePhase.FULFILLMENT, "certificationRequested", "CertificateExchangeCertificationRequested"),
    FULFILLED(ExchangePhase.FULFILLMENT, "fulfilled", "CertificateExchangeFulfilled"),
    DECLINED(ExchangePhase.FULFILLMENT, "declined", "CertificateExchangeDeclined"),
    FAILED(ExchangePhase.FULFILLMENT, "failed", "CertificateExchangeFailed"),

    RETRIEVED(ExchangePhase.ACCEPTANCE, "retrieved", "CertificateExchangeRetrieved"),
    ACCEPTED(ExchangePhase.ACCEPTANCE, "accepted", "CertificateExchangeAccepted"),
    REJECTED(ExchangePhase.ACCEPTANCE, "rejected", "CertificateExchangeRejected"),
    ERRORED(ExchangePhase.ACCEPTANCE, "errored", "CertificateExchangeErrored");

    /** Subject namespace of every event in this catalogue; {@code events.certificate.exchange.>} takes them all. */
    public static final String SUBJECT_PREFIX = "events.certificate.exchange.";
    private static final String TYPE_PREFIX = "org.catena-x.ccm.";
    private static final String TYPE_SUFFIX = ".v1";

    private static final Map<FulfillmentStatus, ExchangeEventType> BY_FULFILLMENT = new EnumMap<>(FulfillmentStatus.class);
    private static final Map<AcceptanceStatus, ExchangeEventType> BY_ACCEPTANCE = new EnumMap<>(AcceptanceStatus.class);

    static {
        for (var status : FulfillmentStatus.values()) {
            BY_FULFILLMENT.put(status, valueOf(status.name()));
        }
        for (var status : AcceptanceStatus.values()) {
            BY_ACCEPTANCE.put(status, valueOf(status.name()));
        }
    }

    private final ExchangePhase phase;
    private final String subject;
    private final String type;

    ExchangeEventType(ExchangePhase phase, String subjectLeaf, String typeName) {
        this.phase = phase;
        this.subject = SUBJECT_PREFIX + subjectLeaf;
        this.type = TYPE_PREFIX + typeName + TYPE_SUFFIX;
    }

    /**
     * The catalogue entry for a Fulfillment status. Total by construction — the static initializer
     * resolves every {@link FulfillmentStatus} constant by name and fails class initialization if one
     * has no counterpart here.
     */
    public static ExchangeEventType of(FulfillmentStatus status) {
        return BY_FULFILLMENT.get(status);
    }

    /** The catalogue entry for an Acceptance status. Total by construction — see {@link #of(FulfillmentStatus)}. */
    public static ExchangeEventType of(AcceptanceStatus status) {
        return BY_ACCEPTANCE.get(status);
    }

    public ExchangePhase phase() {
        return phase;
    }

    /** The NATS subject this event is published on. */
    public String subject() {
        return subject;
    }

    /** The CloudEvents {@code type} attribute (CX-0000 &sect;2.3 reverse-DNS). */
    public String type() {
        return type;
    }
}
