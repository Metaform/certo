package org.metaform.certo.provider.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.metaform.certo.common.model.CertificateIssuer;
import org.metaform.certo.common.model.CertificateValidator;
import org.metaform.certo.common.model.CertifiedLocation;

import java.time.LocalDate;
import java.util.List;

/**
 * The body of the management "add certificate" call — the certificate the backend (certification authority)
 * has issued, carrying its real attributes rather than any synthesized by the provider. The documents are
 * uploaded first via {@code POST /management/v1/documents} and referenced here by {@code documentIds};
 * adding the certificate fulfils every waiting exchange it covers.
 *
 * <p>Required: {@code certificateType}, {@code registrationNumber}, {@code validFrom},
 * {@code validUntil}, a non-empty {@code certifiedLocations} with exactly one {@code MAIN_LOCATION}, and a
 * non-empty {@code documentIds}. {@code certificateTypeVersion} (the type edition, e.g. {@code 2015}),
 * {@code trustLevel}, {@code areaOfApplication}, {@code issuer} and {@code validator} are optional.
 */
public record NewCertificate(
        String certificateType,
        String certificateTypeVersion,
        String registrationNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validUntil,
        String trustLevel,
        String areaOfApplication,
        List<CertifiedLocation> certifiedLocations,
        CertificateIssuer issuer,
        CertificateValidator validator,
        List<String> documentIds) {
}
