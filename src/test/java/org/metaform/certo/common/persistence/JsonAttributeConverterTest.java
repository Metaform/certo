package org.metaform.certo.common.persistence;

import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.CertificateIssuer;
import org.metaform.certo.common.model.StatusError;
import org.metaform.certo.provider.domain.CertificateRevision;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The JPA JSON {@link JsonAttributeConverter}s must round-trip cleanly (they back @Convert columns). */
class JsonAttributeConverterTest {

    @Test
    void statusErrorList_roundTripsWithSpecifier() {
        var converter = new StatusErrorListConverter();
        var value = List.of(new StatusError("expired", "BPNS-1"), new StatusError("plain"));

        var json = converter.convertToDatabaseColumn(value);
        assertThat(converter.convertToEntityAttribute(json)).isEqualTo(value);
    }

    @Test
    void statusErrorList_mapsNullToNullBothWays() {
        var converter = new StatusErrorListConverter();
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void certificateRevisionList_roundTripsIncludingDatesAndDocuments() {
        var converter = new CertificateRevisionListConverter();
        var value = List.of(new CertificateRevision(1,
                LocalDate.parse("2024-01-01"), LocalDate.parse("2030-01-01"), List.of("doc-1", "doc-2")));

        var json = converter.convertToDatabaseColumn(value);
        var restored = converter.convertToEntityAttribute(json);
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).revision()).isEqualTo(1);
        assertThat(restored.get(0).validUntil()).isEqualTo(LocalDate.parse("2030-01-01"));
        assertThat(restored.get(0).documentIds()).containsExactly("doc-1", "doc-2");
    }

    @Test
    void singleValueObject_roundTrips() {
        var converter = new CertificateIssuerConverter();
        var value = new CertificateIssuer("TÜV", "BPNL-ISSUER");

        var json = converter.convertToDatabaseColumn(value);
        assertThat(converter.convertToEntityAttribute(json)).isEqualTo(value);
    }
}
