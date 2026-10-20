package org.metaform.certo.common.pc.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A participant context (PCI) — the unit of multi-tenancy. It owns a participant's identity: the
 * {@code did} (the participant identity — the token audience it is addressed by and the CloudEvents
 * {@code source} it emits with) and an optional {@code dataspaceIdentifier} (the identifier a dataspace
 * assigns when one exists distinct from the participant identity; emitted as the {@code dataspaceidentifier}
 * extension attribute). The same context may act as a provider (issuing certificates) and/or a consumer
 * (opening requests). Certificates and exchanges are stamped with a {@code participantContextId}; outbound
 * identity and the siglet participant-context key both derive from it. The id is system-generated; the
 * context never appears on the wire.
 *
 * <p>Persisted via JPA. The {@code did} carries a unique constraint so concurrent tenant creation with the
 * same audience is rejected by the database.
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
    private String dataspaceIdentifier;
    @Column(unique = true)
    @JsonProperty
    private String did;
    @Version
    private long version;

    protected ParticipantContext() {
        // for JPA
    }

    public ParticipantContext(String participantContextId, String dataspaceIdentifier, String did) {
        this.participantContextId = participantContextId;
        this.dataspaceIdentifier = dataspaceIdentifier;
        this.did = did;
    }

    public String participantContextId() {
        return participantContextId;
    }

    /** The dataspace-assigned identifier, when the deployment's dataspace uses one; null otherwise. */
    public String dataspaceIdentifier() {
        return dataspaceIdentifier;
    }

    public String did() {
        return did;
    }
}
