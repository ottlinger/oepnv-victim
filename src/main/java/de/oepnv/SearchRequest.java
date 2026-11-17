/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import java.time.LocalDateTime;

/** Journey parameters shared by the CLI and the BVG client; HTTP settings stay with the client. */
record SearchRequest(String from, String to, String via, int limit,
                     LocalDateTime departure, LocalDateTime arrival) {
    SearchRequest {
        if (departure != null && arrival != null) {
            throw new IllegalArgumentException("--departure und --arrival dürfen nicht gemeinsam angegeben werden.");
        }
    }
}
