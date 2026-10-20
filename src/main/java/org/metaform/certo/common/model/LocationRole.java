package org.metaform.certo.common.model;

/**
 * The role of a certified location as stated on a certificate. Exactly one entry
 * in a certificate's {@code certifiedLocations} is the {@code MAIN_LOCATION}, whose {@code legalEntityId} is the
 * certificate holder.
 */
public enum LocationRole {
    MAIN_LOCATION,
    ENCLOSED_LOCATION,
    REMOTE_SUPPORT_LOCATION,
    EXTENDED_MANUFACTURING_SITE
}
