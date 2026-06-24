package org.metaform.certo.consumer.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.metaform.certo.common.model.CertifiedLocation;
import org.metaform.certo.common.model.LifecycleStatus;
import org.metaform.certo.consumer.model.KnownCertificate;

import java.time.LocalDate;
import java.util.List;

/** The consumer's lifecycle view of a certificate it has learned about (demo/inspection). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnownCertificateView(
        String certificateId,
        Integer revision,
        LifecycleStatus lifecycleStatus,
        String certificateType,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate validUntil,
        List<CertifiedLocation> certifiedLocations) {

    public static KnownCertificateView of(KnownCertificate certificate) {
        return new KnownCertificateView(
                certificate.certificateId(),
                certificate.revision(),
                certificate.lifecycleStatus(),
                certificate.certificateType(),
                certificate.validFrom(),
                certificate.validUntil(),
                certificate.certifiedLocations());
    }
}
