package org.metaform.certo.protocol.ccm300.consumer;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.metaform.certo.common.model.CertificateDocument;
import org.metaform.certo.common.model.CertificateRecord;
import org.metaform.certo.common.security.OutboundCall;
import org.metaform.certo.common.security.OutboundTokens;
import org.metaform.certo.consumer.spi.CertificateRetriever;
import org.metaform.certo.consumer.spi.RetrievedCertificate;
import org.metaform.certo.consumer.spi.RetrievedDocument;
import org.metaform.certo.protocol.ccm300.Ccm300CertificateCodec;
import org.metaform.certo.protocol.ccm300.model.Ccm300Certificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Retrieves certificates from a Certificate Provider's data plane (CX-0135 v3) using OkHttp, in two
 * steps (push-pull): {@code GET /certificates/{id}} returns JSON metadata listing the documents by
 * reference, then {@code GET /documents/{id}} fetches each document binary. No embedded-document
 * (push) form is used.
 *
 * <p>The provider endpoint comes from the siglet cache (per flow).
 */
@Component
public class Ccm300Retriever implements CertificateRetriever {

    private static final Logger LOG = LoggerFactory.getLogger(Ccm300Retriever.class);

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final OutboundTokens outboundTokens;

    public Ccm300Retriever(OkHttpClient httpClient, ObjectMapper mapper, OutboundTokens outboundTokens) {
        this.http = httpClient;
        this.mapper = mapper;
        this.outboundTokens = outboundTokens;
    }

    /**
     * Fetches a certificate's (latest-revision) metadata and all its referenced document binaries.
     * {@code GET /certificates/{id}} always returns the latest revision (CX-0135 &sect;3.3.2). The endpoint +
     * bearer come from the siglet cache scoped to the counterparty.
     *
     * @throws IOException on transport failure or a non-2xx response
     */
    public RetrievedCertificate fetch(String certificateId, OutboundCall call) throws IOException {
        var resolved = outboundTokens.forCall(call);
        var base = HttpUrl.parse(resolved.baseUrl());
        if (base == null) {
            throw new IOException("Invalid provider base URL: " + resolved.baseUrl());
        }
        var url = base.newBuilder().addPathSegment("certificates").addPathSegment(certificateId).build();

        var builder = new Request.Builder().url(url).header("Accept", "application/json").get();
        authorize(builder, resolved.bearer());

        CertificateRecord metadata;
        try (var response = http.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Provider returned HTTP " + response.code()
                        + " retrieving certificate " + certificateId);
            }
            var body = response.body();
            if (body == null) {
                throw new IOException("Provider returned an empty body for certificate " + certificateId);
            }
            // Deserialize the v3 wire certificate and map it to the neutral domain record.
            metadata = Ccm300CertificateCodec.toDomain(mapper.readValue(body.string(), Ccm300Certificate.class));
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
        authorize(builder, bearer);
        var request = builder.build();
        try (var response = http.newCall(request).execute()) {
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

    private static void authorize(Request.Builder builder, String bearer) {
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
    }
}
