/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import java.time.LocalDateTime;

/** Journey parameters shared by the CLI and the BVG client; HTTP settings stay with the client. */
record SearchRequest(String from, String to, String via, int limit,
                     LocalDateTime departure, LocalDateTime arrival, boolean withFastTrains) {
    SearchRequest(final String from, final String to, final String via, final int limit,
                  final LocalDateTime departure, final LocalDateTime arrival) {
        this(from, to, via, limit, departure, arrival, false);
    }

    SearchRequest {
        if (departure != null && arrival != null) {
            throw new IllegalArgumentException("--departure und --arrival dürfen nicht gemeinsam angegeben werden.");
        }
    }
}
