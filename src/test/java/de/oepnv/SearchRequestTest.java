/* SPDX-License-Identifier: AGPL-3.0-only */
package de.oepnv;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchRequestTest {
    @Test
    void carriesStationNamesLimitAndOptionalTimesWithoutChangingThem() {
        final LocalDateTime time = LocalDateTime.of(2026, 11, 7, 9, 15);
        final SearchRequest departure = new SearchRequest("Beelitz Stadt Bhf.", "Jüterbog", "Potsdam", 2, time, null);
        assertThat(departure.from()).isEqualTo("Beelitz Stadt Bhf.");
        assertThat(departure.to()).isEqualTo("Jüterbog");
        assertThat(departure.via()).isEqualTo("Potsdam");
        assertThat(departure.limit()).isEqualTo(2);
        assertThat(departure.departure()).isEqualTo(time);
        assertThat(departure.arrival()).isNull();

        final SearchRequest arrival = new SearchRequest("A", "B", null, 5, null, time);
        assertThat(arrival.arrival()).isEqualTo(time);
        assertThat(arrival.departure()).isNull();
        assertThat(arrival.via()).isNull();

        final SearchRequest now = new SearchRequest("A", "B", null, 5, null, null);
        assertThat(now.departure()).isNull();
        assertThat(now.arrival()).isNull();
    }

    @Test
    void rejectsConflictingTimesBeforeAnySearchCanStart() {
        final LocalDateTime time = LocalDateTime.of(2026, 11, 7, 9, 15);
        assertThatThrownBy(() -> new SearchRequest("A", "B", null, 5, time, time))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--departure und --arrival dürfen nicht gemeinsam angegeben werden.");
    }
}
