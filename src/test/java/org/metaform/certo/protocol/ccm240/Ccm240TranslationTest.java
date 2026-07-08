package org.metaform.certo.protocol.ccm240;

import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;
import org.metaform.certo.common.model.LocationRole;
import org.metaform.certo.protocol.ccm240.model.BusinessPartnerCertificate31;
import org.metaform.certo.protocol.ccm240.model.Ccm240RequestStatus;
import org.metaform.certo.protocol.ccm240.model.Ccm240StatusValue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the pure legacy &harr; v3 translation core (Phase 1 of the backward-compat adapter). */
class Ccm240TranslationTest {

    @Test
    void fulfillmentStatus_mapsToCcm240RequestReplyStatus() {
        assertThat(Ccm240Translation.toReplyStatus(FulfillmentStatus.FULFILLED)).isEqualTo(Ccm240RequestStatus.COMPLETED);
        assertThat(Ccm240Translation.toReplyStatus(FulfillmentStatus.ACKNOWLEDGED)).isEqualTo(Ccm240RequestStatus.IN_PROGRESS);
        assertThat(Ccm240Translation.toReplyStatus(FulfillmentStatus.CERTIFICATION_REQUESTED)).isEqualTo(Ccm240RequestStatus.IN_PROGRESS);
        assertThat(Ccm240Translation.toReplyStatus(FulfillmentStatus.DECLINED)).isEqualTo(Ccm240RequestStatus.REJECTED);
        assertThat(Ccm240Translation.toReplyStatus(FulfillmentStatus.FAILED)).isEqualTo(Ccm240RequestStatus.REJECTED);
    }

    @Test
    void legacyStatus_mapsToV3AcceptanceStatus() {
        assertThat(Ccm240Translation.toAcceptanceStatus(Ccm240StatusValue.RECEIVED)).isEqualTo(AcceptanceStatus.RETRIEVED);
        assertThat(Ccm240Translation.toAcceptanceStatus(Ccm240StatusValue.ACCEPTED)).isEqualTo(AcceptanceStatus.ACCEPTED);
        assertThat(Ccm240Translation.toAcceptanceStatus(Ccm240StatusValue.REJECTED)).isEqualTo(AcceptanceStatus.REJECTED);
    }

    @Test
    void v3AcceptanceStatus_mapsToCcm240Status_erroredDownMapsToRejected() {
        assertThat(Ccm240Translation.toCcm240StatusValue(AcceptanceStatus.RETRIEVED)).isEqualTo(Ccm240StatusValue.RECEIVED);
        assertThat(Ccm240Translation.toCcm240StatusValue(AcceptanceStatus.ACCEPTED)).isEqualTo(Ccm240StatusValue.ACCEPTED);
        assertThat(Ccm240Translation.toCcm240StatusValue(AcceptanceStatus.REJECTED)).isEqualTo(Ccm240StatusValue.REJECTED);
        assertThat(Ccm240Translation.toCcm240StatusValue(AcceptanceStatus.ERRORED)).isEqualTo(Ccm240StatusValue.REJECTED);
    }

    @Test
    void upConvert_splitsDocument_andMapsMetadataAndLocations() {
        var pdf = "PDF-BYTES".getBytes(StandardCharsets.UTF_8);
        var cert = new BusinessPartnerCertificate31(
                "BPNL000000000AAA",
                new BusinessPartnerCertificate31.Type("iso9001", "2015"),
                "REG-123",
                "Production",
                List.of(new BusinessPartnerCertificate31.EnclosedSite("BPNS0000000ABS01", "Site A")),
                "2023-01-25",
                "2026-01-24",
                new BusinessPartnerCertificate31.Issuer("TUV", "BPNL133631123120"),
                "high",
                new BusinessPartnerCertificate31.Validator("Data service X", "BPNL00000007YREZ"),
                "BPNL00000003AYRE",
                new BusinessPartnerCertificate31.Document("2024-08-23T13:19:00.280+02:00",
                        "urn:uuid:doc-1", "application/pdf", Base64.getEncoder().encodeToString(pdf)));

        var up = Ccm240Translation.upConvert(cert, "cert-x", 1);
        var record = up.record();

        assertThat(record.certificateId()).isEqualTo("cert-x");
        assertThat(record.revision()).isEqualTo(1);
        assertThat(record.certificateType()).isEqualTo("iso9001");
        assertThat(record.certificateTypeVersion()).isEqualTo("2015");
        assertThat(record.registrationNumber()).isEqualTo("REG-123");
        assertThat(record.validFrom()).isEqualTo(LocalDate.of(2023, 1, 25));
        assertThat(record.validUntil()).isEqualTo(LocalDate.of(2026, 1, 24));
        assertThat(record.trustLevel()).isEqualTo("high");
        assertThat(record.issuer().issuerName()).isEqualTo("TUV");
        assertThat(record.issuer().issuerBpn()).isEqualTo("BPNL133631123120");
        assertThat(record.validator().validatorBpn()).isEqualTo("BPNL00000007YREZ");

        assertThat(record.certifiedLocations()).hasSize(2);
        var mainLocation = record.certifiedLocations().get(0);
        assertThat(mainLocation.locationRole()).isEqualTo(LocationRole.MAIN_LOCATION);
        assertThat(mainLocation.bpnl()).isEqualTo("BPNL000000000AAA");
        var enclosed = record.certifiedLocations().get(1);
        assertThat(enclosed.locationRole()).isEqualTo(LocationRole.ENCLOSED_LOCATION);
        assertThat(enclosed.bpns()).isEqualTo("BPNS0000000ABS01");

        // document is split out of the inline content into a separate binary + a reference
        assertThat(up.document().documentId()).isEqualTo("urn:uuid:doc-1");
        assertThat(up.document().mediaType()).isEqualTo("application/pdf");
        assertThat(up.document().content()).isEqualTo(pdf);
        assertThat(up.document().createdDate()).isEqualTo(LocalDate.of(2024, 8, 23));
        assertThat(record.documents()).singleElement()
                .satisfies(ref -> assertThat(ref.documentId()).isEqualTo("urn:uuid:doc-1"));
    }

    @Test
    void downConvert_roundTripsKeyFields() {
        var pdf = "PDF-BYTES".getBytes(StandardCharsets.UTF_8);
        var original = new BusinessPartnerCertificate31(
                "BPNL000000000AAA",
                new BusinessPartnerCertificate31.Type("iso9001", "2015"),
                "REG-123",
                "Production",
                List.of(new BusinessPartnerCertificate31.EnclosedSite("BPNS0000000ABS01", "Site A")),
                "2023-01-25",
                "2026-01-24",
                new BusinessPartnerCertificate31.Issuer("TUV", "BPNL133631123120"),
                "high",
                new BusinessPartnerCertificate31.Validator("Data service X", "BPNL00000007YREZ"),
                "BPNL00000003AYRE",
                new BusinessPartnerCertificate31.Document("2024-08-23T13:19:00.280+02:00",
                        "urn:uuid:doc-1", "application/pdf", Base64.getEncoder().encodeToString(pdf)));

        var up = Ccm240Translation.upConvert(original, "cert-x", 1);
        var back = Ccm240Translation.downConvert(up.record(), up.document());

        assertThat(back.businessPartnerNumber()).isEqualTo("BPNL000000000AAA");
        assertThat(back.type().certificateType()).isEqualTo("iso9001");
        assertThat(back.type().certificateVersion()).isEqualTo("2015");
        assertThat(back.registrationNumber()).isEqualTo("REG-123");
        assertThat(back.validFrom()).isEqualTo("2023-01-25");
        assertThat(back.validUntil()).isEqualTo("2026-01-24");
        assertThat(back.trustLevel()).isEqualTo("high");
        assertThat(back.issuer().issuerBpn()).isEqualTo("BPNL133631123120");
        assertThat(back.enclosedSites()).singleElement()
                .satisfies(site -> assertThat(site.enclosedSiteBpn()).isEqualTo("BPNS0000000ABS01"));
        assertThat(Base64.getDecoder().decode(back.document().contentBase64())).isEqualTo(pdf);
        assertThat(back.document().documentId()).isEqualTo("urn:uuid:doc-1");
    }
}
