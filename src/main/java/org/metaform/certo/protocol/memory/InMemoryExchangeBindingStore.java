package org.metaform.certo.protocol.memory;

import org.metaform.certo.protocol.CounterpartyRole;
import org.metaform.certo.protocol.ExchangeBinding;
import org.metaform.certo.protocol.ExchangeBindingStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds the {@link ExchangeBinding} for exchanges whose counterparty speaks a non-native protocol
 * version. Keyed by {@code exchangeId} (an inbound push, or a provider-initiated publish, both mint the
 * exchangeId inside the core call before the binding is recorded). Also resolves a v2.4.0
 * {@code documentId} + peer back to an exchangeId for inbound status correlation. In-memory (non-persistent).
 */
@Component
@ConditionalOnProperty(name = "certo.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryExchangeBindingStore implements ExchangeBindingStore {

    private final ConcurrentMap<String, ExchangeBinding> byExchangeId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> exchangeByCertificateAndPeer = new ConcurrentHashMap<>();

    public void record(ExchangeBinding binding) {
        if (binding.exchangeId() != null) {
            byExchangeId.put(binding.exchangeId(), binding);
            if (binding.certificateId() != null && binding.peerBpn() != null) {
                exchangeByCertificateAndPeer.put(key(binding.certificateId(), binding.peerBpn()), binding.exchangeId());
            }
        }
    }

    public Optional<ExchangeBinding> byExchangeId(String exchangeId) {
        return Optional.ofNullable(exchangeId == null ? null : byExchangeId.get(exchangeId));
    }

    /**
     * Resolves the binding for an exchange whose counterparty plays the given role, by {@code exchangeId}.
     * A binding for the other role is ignored (an exchange has one counterparty per direction), so the two
     * outbound dispatchers never act on each other's bindings.
     */
    public Optional<ExchangeBinding> resolve(String exchangeId, CounterpartyRole role) {
        return byExchangeId(exchangeId).filter(binding -> binding.role() == role);
    }

    /** Resolves the v3 {@code exchangeId} for a {@code certificateId} reported by the given peer. */
    public Optional<String> exchangeFor(String certificateId, String peerBpn) {
        return Optional.ofNullable(exchangeByCertificateAndPeer.get(key(certificateId, peerBpn)));
    }

    private static String key(String certificateId, String peerBpn) {
        return certificateId + "|" + peerBpn;
    }
}
