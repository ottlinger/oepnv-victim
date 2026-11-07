package de.oepnv;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @Test
    void helpDoesNotAccessNetwork() {
        StringWriter out = new StringWriter();
        int code = new CommandLine(new Main()).setOut(new PrintWriter(out)).execute("--help");
        assertEquals(0, code);
        assertTrue(out.toString().contains("--start"));
        assertTrue(out.toString().contains("--ziel"));
        assertTrue(out.toString().contains("--startzeit"));
        assertTrue(out.toString().contains("--departure"));
    }

    @Test
    void parsesDepartureTimeWithEitherAliasWithoutNetwork() {
        for (String option : new String[]{"--startzeit", "--departure"}) {
            var command = new CommandLine(new Main());
            var parsed = command.parseArgs("--from", "A", "--to", "B", option, "2026-11-07 15:22");
            assertEquals(LocalDateTime.of(2026, 11, 7, 15, 22), parsed.matchedOptionValue(option, null));
        }
        var parsed = new CommandLine(new Main()).parseArgs("--from", "A", "--to", "B");
        assertNull(parsed.matchedOptionValue("--departure", null));
    }

    @Test
    void rejectsInvalidDepartureTimesWithoutNetwork() {
        for (String value : new String[]{"", "2026-11-07", "2026-11-07T15:22", "2026-11-07 15:22:00",
                "2026-02-29 15:22", "2026-11-31 15:22", "2026-11-07 24:00", "2026-11-07 15:60",
                "2026-1-07 15:22", " 2026-11-07 15:22", "not a date"}) {
            StringWriter err = new StringWriter();
            int code = new CommandLine(new Main()).setErr(new PrintWriter(err))
                    .execute("--from", "A", "--to", "B", "--startzeit", value);
            assertEquals(2, code, value);
            assertTrue(err.toString().contains("YYYY-MM-DD HH:mm"), value);
        }
        assertEquals(LocalDateTime.of(2028, 2, 29, 0, 0),
                new Main.DepartureTimeConverter().convert("2028-02-29 00:00"));
    }

    @Test
    void invalidArgumentsReturnUsageErrorWithoutNetwork() {
        for (String[] args : new String[][]{
                {}, {"--start", "A"},
                {"--start", " ", "--ziel", "B"},
                {"--start", "A", "--ziel", "B", "--limit", "0"},
                {"--start", "A", "--ziel", "B", "--timeout", "-1"}}) {
            int code = new CommandLine(new Main()).setErr(new PrintWriter(new StringWriter())).execute(args);
            assertEquals(2, code);
        }
    }
}
