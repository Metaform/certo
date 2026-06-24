package org.metaform.certo.consumer.client;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.metaform.certo.common.CertoProperties;
import org.metaform.certo.common.model.CertificateDocument;
import org.metaform.certo.common.model.CertificateRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Retrieves certificates from a Certificate Provider's data plane (CX-0135 v3) using OkHttp, in two
 * steps (push-pull): {@code GET /certificates/{id}} returns JSON metadata listing the documents by
 * reference, then {@code GET /documents/{id}} fetches each document binary. No embedded-document
 * (push) form is used.
 *
 * <p>The provider base URL is hardcoded via {@code certo.provider-base-url} (no DSP catalog lookup).
 */
@Component
public class ProviderCertificateClient {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderCertificateClient.class);

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper;
    private final String providerBaseUrl;

    public ProviderCertificateClient(ObjectMapper mapper, CertoProperties properties) {
        this.mapper = mapper;
        this.providerBaseUrl = Objects.requireNonNull(properties.providerBaseUrl(),
                "certo.provider-base-url must be configured");
    }

    /**
     * Fetches a certificate's metadata and all its referenced document binaries.
     *
     * @param certificateId the certificate to retrieve
     * @param revision      the specific revision to retrieve, or {@code null} for the latest
     * @throws IOException on transport failure or a non-2xx response
     */
    public RetrievedCertificate fetch(String certificateId, Integer revision) throws IOException {
        var base = HttpUrl.parse(providerBaseUrl);
        if (base == null) {
            throw new IOException("Invalid provider base URL: " + providerBaseUrl);
        }
        var urlBuilder = base.newBuilder().addPathSegment("certificates").addPathSegment(certificateId);
        if (revision != null) {
            urlBuilder.addQueryParameter("revision", Integer.toString(revision));
        }
        var url = urlBuilder.build();

        var request = new Request.Builder().url(url).header("Accept", "application/json").get().build();
        LOG.info("Retrieving certificate {} (revision {}) from {}", certificateId, revision, url);

        CertificateRecord metadata;
        try (var response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Provider returned HTTP " + response.code()
                        + " retrieving certificate " + certificateId);
            }
            var body = response.body();
            if (body == null) {
                throw new IOException("Provider returned an empty body for certificate " + certificateId);
            }
            metadata = mapper.readValue(body.string(), CertificateRecord.class);
        }

        var documents = new ArrayList<RetrievedDocument>();
        if (metadata.documents() != null) {
            for (CertificateDocument ref : metadata.documents()) {
                documents.add(fetchDocument(base, ref));
            }
        }
        return new RetrievedCertificate(metadata, documents);
    }

    private RetrievedDocument fetchDocument(HttpUrl base, CertificateDocument ref) throws IOException {
        var url = base.newBuilder().addPathSegment("documents").addPathSegment(ref.documentId()).build();
        var request = new Request.Builder().url(url).get().build();
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
}
