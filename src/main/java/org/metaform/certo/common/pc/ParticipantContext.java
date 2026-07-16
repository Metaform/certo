package org.metaform.certo.common.pc;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A participant context (PCI) — the unit of multi-tenancy. It owns a participant's identity: the
 * {@code bpn} (Business Partner Number), the {@code source} (CloudEvents {@code source} URI it emits with),
 * and the {@code did} (the token audience it is addressed by). The same context may act as a provider
 * (issuing certificates) and/or a consumer (opening requests). Certificates and exchanges are stamped with
 * a {@code participantContextId}; outbound identity and the siglet participant-context key both derive from
 * it. The id is system-generated; the context never appears on the CCM wire.
 *
 * <p>Persisted via JPA. The {@code did} carries a unique constraint so concurrent tenant creation with the
 * same audience is rejected by the database (the former create-time JVM lock is gone).
 */
@Entity
@Table(name = "participant_context")
public class ParticipantContext {

    // @JsonProperty on each wire field: this is a plain class (not a record), so Jackson serializes fields
    // it is told about rather than the record-style x() accessors — and the internal @Version stays off the wire.
    @Id
    @JsonProperty
    private String participantContextId;
    @JsonProperty
    private String bpn;
    @JsonProperty
    private String source;
    @Column(unique = true)
    @JsonProperty
    private String did;
    @Version
    private long version;

    protected ParticipantContext() {
        // for JPA
    }

    public ParticipantContext(String participantContextId, String bpn, String source, String did) {
        this.participantContextId = participantContextId;
        this.bpn = bpn;
        this.source = source;
        this.did = did;
    }

    public String participantContextId() {
        return participantContextId;
    }

    public String bpn() {
        return bpn;
    }

    public String source() {
        return source;
    }

    public String did() {
        return did;
    }
}
