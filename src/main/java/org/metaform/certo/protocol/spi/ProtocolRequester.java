package org.metaform.certo.protocol.spi;

import org.metaform.certo.consumer.spi.CertificateRequester;
import org.metaform.certo.protocol.ProtocolVersion;

/**
 * A version-specific {@link CertificateRequester}: opens (and, where the wire supports it, polls) a
 * certificate request on a provider in one CX-0135 wire-protocol version.
 */
public interface ProtocolRequester extends CertificateRequester {

    /** The wire-protocol version this requester speaks. */
    ProtocolVersion version();
}
