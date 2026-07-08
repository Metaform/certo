package org.metaform.certo.protocol;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds the {@link ExchangeBinding} for exchanges whose counterparty speaks a non-native protocol
 * version. Keyed by {@code exchangeId}, with {@code certificateId} as a fallback for the window before
 * the exchangeId is assigned (an inbound push, or a provider-initiated publish, both mint the exchangeId
 * inside the core call). Also resolves a legacy {@code documentId} + peer back to an exchangeId for
 * inbound status correlation. In-memory, demo only.
 */
@Component
public class ExchangeBindingStore {

    private final ConcurrentMap<String, ExchangeBinding> byExchangeId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ExchangeBinding> byCertificateId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> exchangeByDocumentAndPeer = new ConcurrentHashMap<>();

    public void record(ExchangeBinding binding) {
        if (binding.exchangeId() != null) {
            byExchangeId.put(binding.exchangeId(), binding);
        }
        if (binding.certificateId() != null) {
            byCertificateId.put(binding.certificateId(), binding);
            if (binding.peerBpn() != null && binding.exchangeId() != null) {
                exchangeByDocumentAndPeer.put(key(binding.certificateId(), binding.peerBpn()), binding.exchangeId());
            }
        }
    }

    public Optional<ExchangeBinding> byExchangeId(String exchangeId) {
        return Optional.ofNullable(exchangeId == null ? null : byExchangeId.get(exchangeId));
    }

    public Optional<ExchangeBinding> byCertificateId(String certificateId) {
        return Optional.ofNullable(certificateId == null ? null : byCertificateId.get(certificateId));
    }

    /**
     * Resolves the binding for an exchange whose counterparty plays the given role, by {@code exchangeId}
     * then {@code certificateId}. A binding for the other role is ignored (an exchange has one
     * counterparty per direction), so the two outbound dispatchers never act on each other's bindings.
     */
    public Optional<ExchangeBinding> resolve(String exchangeId, String certificateId, CounterpartyRole role) {
        var binding = byExchangeId(exchangeId).or(() -> byCertificateId(certificateId)).orElse(null);
        return binding != null && binding.role() == role ? Optional.of(binding) : Optional.empty();
    }

    /** Resolves the v3 {@code exchangeId} for a legacy {@code documentId} reported by the given peer. */
    public Optional<String> exchangeFor(String documentId, String peerBpn) {
        return Optional.ofNullable(exchangeByDocumentAndPeer.get(key(documentId, peerBpn)));
    }

    private static String key(String documentId, String peerBpn) {
        return documentId + "|" + peerBpn;
    }
}
