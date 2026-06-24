package org.metaform.certo.common.model;

/**
 * The role of a certified location as stated on a certificate (CX-0135 &sect;4.2.4). Exactly one entry
 * in a certificate's {@code certifiedLocations} is the {@code MAIN_LOCATION}, whose {@code bpnl} is the
 * certificate holder.
 */
public enum LocationRole {
    MAIN_LOCATION,
    ENCLOSED_LOCATION,
    REMOTE_SUPPORT_LOCATION,
    EXTENDED_MANUFACTURING_SITE
}
