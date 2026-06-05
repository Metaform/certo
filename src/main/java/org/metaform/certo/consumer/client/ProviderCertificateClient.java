package org.metaform.certo.consumer.client;

import okhttp3.HttpUrl;
import okhttp3.MultipartReader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.metaform.certo.common.CertoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Objects;

/**
 * Retrieves certificates from a Certificate Provider's data plane via {@code GET /certificates/{id}}
 * (CX-0135 &sect;4.4.3) using OkHttp. The response is a {@code multipart/related} message whose JSON
 * part is parsed into {@link CertificateMetadata} and whose PDF part is read as raw bytes.
 *
 * <p>The provider base URL is hardcoded via configuration ({@code certo.provider-base-url}); there is
 * no DSP catalog lookup or contract negotiation (out of scope).
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
     * Fetches a certificate from the provider.
     *
     * @param certificateId the certificate to retrieve
     * @param version       the specific version to retrieve, or {@code null} for the latest
     * @return the metadata and PDF binary
     * @throws IOException on transport failure or a non-2xx response
     */
    public RetrievedCertificate fetch(String certificateId, Integer version) throws IOException {
        var base = HttpUrl.parse(providerBaseUrl);
        if (base == null) {
            throw new IOException("Invalid provider base URL: " + providerBaseUrl);
        }
        var urlBuilder = base.newBuilder()
                .addPathSegment("certificates")
                .addPathSegment(certificateId);
        if (version != null) {
            urlBuilder.addQueryParameter("version", Integer.toString(version));
        }
        var url = urlBuilder.build();

        var request = new Request.Builder()
                .url(url)
                .header("Accept", "multipart/related")
                .get()
                .build();

        LOG.info("Retrieving certificate {} (version {}) from {}", certificateId, version, url);
        try (var response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Provider returned HTTP " + response.code()
                        + " retrieving certificate " + certificateId);
            }
            var body = response.body();
            if (body == null) {
                throw new IOException("Provider returned an empty body for certificate " + certificateId);
            }
            return parseMultipart(body);
        }
    }

    private RetrievedCertificate parseMultipart(ResponseBody body) throws IOException {
        CertificateMetadata metadata = null;
        byte[] pdf = null;
        try (var reader = new MultipartReader(body)) {
            MultipartReader.Part part;
            while ((part = reader.nextPart()) != null) {
                var contentType = part.headers().get("Content-Type");
                if (contentType != null && contentType.contains("application/json")) {
                    metadata = mapper.readValue(part.body().readUtf8(), CertificateMetadata.class);
                } else if (contentType != null && contentType.contains("application/pdf")) {
                    pdf = part.body().readByteArray();
                } else {
                    part.body().readByteArray(); // drain any unexpected part
                }
            }
        }
        if (metadata == null) {
            throw new IOException("multipart/related response is missing its application/json metadata part");
        }
        return new RetrievedCertificate(metadata, pdf);
    }
}
