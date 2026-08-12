package org.metaform.certo.common.event;

import org.junit.jupiter.api.Test;
import org.metaform.certo.common.model.AcceptanceStatus;
import org.metaform.certo.common.model.FulfillmentStatus;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the published contract of the event catalogue. These are cheap assertions protecting an
 * expensive mistake: a status that maps to nothing publishes nothing, silently, and the gap only
 * shows up as a consumer that never fires.
 */
class ExchangeEventTypeTest {

    @Test
    void everyFulfillmentStatusHasACatalogueEntry() {
        for (var status : FulfillmentStatus.values()) {
            assertThat(ExchangeEventType.of(status))
                    .as("no event catalogue entry for FulfillmentStatus.%s", status)
                    .isNotNull()
                    .extracting(ExchangeEventType::phase)
                    .isEqualTo(ExchangePhase.FULFILLMENT);
        }
    }

    @Test
    void everyAcceptanceStatusHasACatalogueEntry() {
        for (var status : AcceptanceStatus.values()) {
            assertThat(ExchangeEventType.of(status))
                    .as("no event catalogue entry for AcceptanceStatus.%s", status)
                    .isNotNull()
                    .extracting(ExchangeEventType::phase)
                    .isEqualTo(ExchangePhase.ACCEPTANCE);
        }
    }

    @Test
    void catalogueCoversExactlyTheTwoStatusEnums() {
        var statusNames = Stream.concat(
                        Arrays.stream(FulfillmentStatus.values()).map(Enum::name),
                        Arrays.stream(AcceptanceStatus.values()).map(Enum::name))
                .toList();
        // Nothing extra either: an entry with no backing status is dead contract surface.
        assertThat(Arrays.stream(ExchangeEventType.values()).map(Enum::name))
                .containsExactlyInAnyOrderElementsOf(statusNames);
    }

    @Test
    void subjectsAreUniqueAndNamespaced() {
        var subjects = Arrays.stream(ExchangeEventType.values()).map(ExchangeEventType::subject).toList();
        assertThat(subjects).doesNotHaveDuplicates().allMatch(s -> s.startsWith("events.certificate.exchange."));
        // The events. prefix is what the platform's edc-events stream captures and what the NATS
        // permission matrix grants; losing it means the events are published nowhere observable.
        assertThat(subjects).allMatch(s -> s.startsWith("events."));
    }

    @Test
    void typesAreUniqueAndFollowTheCx0000Convention() {
        var types = Arrays.stream(ExchangeEventType.values()).map(ExchangeEventType::type).toList();
        assertThat(types).doesNotHaveDuplicates()
                .allMatch(t -> t.startsWith("org.catena-x.ccm."))
                .allMatch(t -> t.endsWith(".v1"));
    }

    @Test
    void subjectAndTypeAgreeOnTheStatusTheyName() {
        assertThat(ExchangeEventType.of(FulfillmentStatus.CERTIFICATION_REQUESTED))
                .returns("events.certificate.exchange.certificationRequested", ExchangeEventType::subject)
                .returns("org.catena-x.ccm.CertificateExchangeCertificationRequested.v1", ExchangeEventType::type);
        assertThat(ExchangeEventType.of(AcceptanceStatus.REJECTED))
                .returns("events.certificate.exchange.rejected", ExchangeEventType::subject)
                .returns("org.catena-x.ccm.CertificateExchangeRejected.v1", ExchangeEventType::type);
    }
}
