package org.metaform.certo.protocol.spi;

import org.metaform.certo.consumer.spi.CertificateRequester;

/**
 * A protocol-specific {@link CertificateRequester}: opens (and, where the wire supports it, polls) a
 * certificate request on a provider in one wire protocol.
 */
public interface ProtocolRequester extends CertificateRequester {

    /** The open protocol id this requester speaks (e.g. {@code "sce-1.0"}). */
    String protocolId();
}
