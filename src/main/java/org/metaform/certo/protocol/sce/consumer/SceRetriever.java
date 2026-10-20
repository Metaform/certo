package org.metaform.certo.protocol.sce.consumer;

import okhttp3.HttpUrl;
import okhttp3.Request;
import org.metaform.certo.common.http.OutboundJsonClient;
import org.metaform.certo.common.http.RetryingHttpClient;
import org.metaform.certo.common.model.CertificateDocument;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.security.outbound.OutboundCall;
import org.metaform.certo.common.security.outbound.OutboundTokenCache;
import org.metaform.certo.consumer.spi.RetrievedCertificate;
import org.metaform.certo.consumer.spi.RetrievedDocument;
import org.metaform.certo.protocol.sce.Sce;
import org.metaform.certo.protocol.sce.SceCertificateCodec;
import org.metaform.certo.protocol.sce.model.SceCertificate;
import org.metaform.certo.protocol.spi.ProtocolRetriever;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Retrieves certificates from a Certificate Provider's data plane (the SCE specification) using OkHttp, in two
 * steps (push-pull): {@code GET /certificates/{id}} returns JSON metadata listing the documents by
 * reference, then {@code GET /documents/{id}} fetches each document binary. No embedded-document
 * (push) form is used.
 *
 * <p>The provider endpoint comes from the siglet cache (per flow).
 */
@Component
public class SceRetriever implements ProtocolRetriever {

    @Override
    public String protocolId() {
        return Sce.PROTOCOL_ID;
    }



    private final RetryingHttpClient http;
    private final ObjectMapper mapper;
    private final OutboundTokenCache outboundTokenCache;

    public SceRetriever(RetryingHttpClient httpClient, ObjectMapper mapper, OutboundTokenCache outboundTokenCache) {
        this.http = httpClient;
        this.mapper = mapper;
        this.outboundTokenCache = outboundTokenCache;
    }

    /**
     * Fetches a certificate's metadata and all its referenced document binaries —
     * {@code GET /certificates/{id}?revision=} pinned to the given revision, the latest when null. The
     * endpoint + bearer come from the siglet cache scoped to the counterparty.
     *
     * @throws IOException on transport failure or a non-2xx response
     */
    public RetrievedCertificate fetch(String certificateId, Integer revision, OutboundCall call) throws IOException {
        var resolved = outboundTokenCache.forCall(call);
        var base = HttpUrl.parse(resolved.baseUrl());
        if (base == null) {
            throw new IOException("Invalid provider base URL: " + resolved.baseUrl());
        }
        var urlBuilder = base.newBuilder().addPathSegment("certificates").addPathSegment(certificateId);
        if (revision != null) {
            urlBuilder.addQueryParameter("revision", revision.toString());
        }
        var url = urlBuilder.build();

        var builder = new Request.Builder().url(url).header("Accept", "application/json").get();
        OutboundJsonClient.authorize(builder, resolved.bearer());

        CertificateRecord metadata;
        try (var response = http.execute(builder.build())) {
            if (!response.isSuccessful()) {
                throw new IOException("Provider returned HTTP " + response.code()
                        + " retrieving certificate " + certificateId);
            }
            var body = response.body();
            if (body == null) {
                throw new IOException("Provider returned an empty body for certificate " + certificateId);
            }
            // Deserialize the SCE wire certificate and map it to the neutral domain record.
            metadata = SceCertificateCodec.toDomain(mapper.readValue(body.string(), SceCertificate.class));
        }

        var documents = new ArrayList<RetrievedDocument>();
        if (metadata.documents() != null) {
            for (CertificateDocument ref : metadata.documents()) {
                documents.add(fetchDocument(base, ref, resolved.bearer()));
            }
        }
        return new RetrievedCertificate(metadata, documents);
    }

    private RetrievedDocument fetchDocument(HttpUrl base, CertificateDocument ref, String bearer) throws IOException {
        var url = base.newBuilder().addPathSegment("documents").addPathSegment(ref.documentId()).build();
        var builder = new Request.Builder().url(url).get();
        OutboundJsonClient.authorize(builder, bearer);
        var request = builder.build();
        try (var response = http.execute(request)) {
            if (!response.isSuccessful()) {
                throw new IOException("Provider returned HTTP " + response.code()
                        + " retrieving document " + ref.documentId());
            }
            var body = response.body();
            if (body == null) {
                throw new IOException("Provider returned an empty body for document " + ref.documentId());
            }
            var contentType = response.header("Content-Type", ref.mediaType());
            return new RetrievedDocument(ref.documentId(), contentType, body.bytes());
        }
    }

}
