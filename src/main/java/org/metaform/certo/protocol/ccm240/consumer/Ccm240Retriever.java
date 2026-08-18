package org.metaform.certo.protocol.ccm240.consumer;

import okhttp3.HttpUrl;
import okhttp3.Request;
import org.metaform.certo.common.http.OutboundJsonClient;
import org.metaform.certo.common.http.RetryingHttpClient;
import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.common.security.outbound.OutboundTokenCache;
import org.metaform.certo.consumer.spi.RetrievedCertificate;
import org.metaform.certo.consumer.spi.RetrievedDocument;
import org.metaform.certo.protocol.ProtocolVersion;
import org.metaform.certo.protocol.ccm240.Ccm240Translation;
import org.metaform.certo.protocol.ccm240.model.BusinessPartnerCertificate31;
import org.metaform.certo.protocol.spi.ProtocolRetriever;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Retrieves a certificate from a v2.4.0 provider (the CX-0135 v2 <b>asset-based pull</b>).
 * Unlike v3 (a metadata record plus a separate {@code GET /documents/{id}} hop), a 2.4.0 certificate asset
 * resolves to a single {@code BusinessPartnerCertificate} 3.1.0 JSON with the document inline as
 * {@code contentBase64} — so one {@code GET} of the resolved endpoint yields the whole record.
 *
 * <p>The endpoint + bearer come from the token cache, scoped to the counterparty and keyed by the live
 * {@code flowId} on the {@link OutboundCall}; the flow (EDC catalog / negotiation / transfer) is established
 * upstream, so this retriever only performs the read. The resolved endpoint <b>is</b> the asset's
 * data address (no {@code /certificates/{id}} suffix, unlike {@code Ccm300Retriever}).
 *
 * <p>Identity: a 3.1.0 certificate carries no {@code certificateId}. When the caller knows it (a pull for an
 * existing exchange opened by an {@code /available} notice, whose {@code documentId} is the id) it is passed
 * through; otherwise (a proactive discovery pull) the id is <b>derived</b> the same way an inbound push
 * derives it — a name-based UUID of {@code issuerBpn|registrationNumber} — so a later re-pull maps to the
 * same certificate.
 */
@Component
public class Ccm240Retriever implements ProtocolRetriever {

    private final RetryingHttpClient http;
    private final ObjectMapper mapper;
    private final OutboundTokenCache outboundTokenCache;

    public Ccm240Retriever(RetryingHttpClient httpClient, ObjectMapper mapper, OutboundTokenCache outboundTokenCache) {
        this.http = httpClient;
        this.mapper = mapper;
        this.outboundTokenCache = outboundTokenCache;
    }

    @Override
    public ProtocolVersion version() {
        return ProtocolVersion.CCM_2_4_0;
    }

    /**
     * Pulls the certificate over the flow the {@code call} carries. {@code certificateId} may be {@code null}
     * for a discovery pull, in which case it is derived from the retrieved certificate's identity.
     *
     * @throws IOException on transport failure, a non-2xx response, or an empty body
     */
    @Override
    public RetrievedCertificate fetch(String certificateId, OutboundCall call) throws IOException {
        var resolved = outboundTokenCache.forCall(call);
        var url = HttpUrl.parse(resolved.baseUrl());
        if (url == null) {
            throw new IOException("Invalid provider data-plane URL: " + resolved.baseUrl());
        }

        var builder = new Request.Builder().url(url).header("Accept", "application/json").get();
        OutboundJsonClient.authorize(builder, resolved.bearer());

        BusinessPartnerCertificate31 cert;
        try (var response = http.execute(builder.build())) {
            if (!response.isSuccessful()) {
                throw new IOException("Provider returned HTTP " + response.code() + " retrieving v2.4.0 certificate");
            }
            var body = response.body();
            if (body == null) {
                throw new IOException("Provider returned an empty body for the v2.4.0 certificate");
            }
            cert = mapper.readValue(body.string(), BusinessPartnerCertificate31.class);
        }

        var id = certificateId != null ? certificateId : deriveCertificateId(cert, call.counterpartyBpn());
        // Retrieval is always latest-revision; 2.4.0 carries no revision on the wire (the known-certificate
        // view assigns its own on store). Up-convert carries the inline document through into the record.
        var metadata = Ccm240Translation.upConvert(cert, id, 1);

        List<RetrievedDocument> documents = List.of();
        if (metadata.documents() != null) {
            documents = metadata.documents().stream()
                    .map(d -> new RetrievedDocument(d.documentId(), d.mediaType(),
                            d.contentBase64() == null ? new byte[0]
                                    : Base64.getDecoder().decode(d.contentBase64())))
                    .toList();
        }
        return new RetrievedCertificate(metadata, documents);
    }

    /**
     * Derives a stable {@code certificateId} from the certificate's identity — a name-based UUID of
     * {@code issuerBpn|registrationNumber} (falling back to the transmitting {@code senderBpn} when the issuer
     * is absent). Matches the derivation an inbound v2.4.0 push uses, so a pushed and a pulled view of the same
     * certificate share an id.
     */
    private static String deriveCertificateId(BusinessPartnerCertificate31 cert, String senderBpn) {
        var issuerBpn = cert.issuer() != null ? cert.issuer().issuerBpn() : null;
        var identityKey = (issuerBpn != null ? issuerBpn : senderBpn) + "|" + cert.registrationNumber();
        return UUID.nameUUIDFromBytes(identityKey.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
