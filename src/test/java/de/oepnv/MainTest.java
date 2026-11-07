package de.oepnv;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    @Test
    void helpDoesNotAccessNetwork() {
        StringWriter out = new StringWriter();
        int code = new CommandLine(new Main()).setOut(new PrintWriter(out)).execute("--help");
        assertEquals(0, code);
        assertTrue(out.toString().contains("--from"));
        assertTrue(out.toString().contains("--to"));
        assertTrue(out.toString().contains("--via"));
        assertTrue(out.toString().contains("Stationen müssen eindeutig sein"));
        assertTrue(out.toString().contains("BVG-Stationssuche"));
        assertFalse(out.toString().contains("--startzeit"));
        assertTrue(out.toString().contains("--departure"));
    }

    @Test
    void parsesDepartureTimeWithoutNetwork() {
        var parsed = new CommandLine(new Main())
                .parseArgs("--from", "A", "--to", "B", "--departure", "2026-11-07 15:22");
        assertEquals(LocalDateTime.of(2026, 11, 7, 15, 22), parsed.matchedOptionValue("--departure", null));
        var defaults = new CommandLine(new Main()).parseArgs("--from", "A", "--to", "B");
        assertNull(defaults.matchedOptionValue("--departure", null));
    }

    @Test
    void parsesOptionalViaWithoutNetwork() {
        var parsed = new CommandLine(new Main()).parseArgs("--from", "A", "--to", "B", "--via", "Potsdam");
        assertEquals("Potsdam", parsed.matchedOptionValue("--via", null));
        var defaults = new CommandLine(new Main()).parseArgs("--from", "A", "--to", "B");
        assertNull(defaults.matchedOptionValue("--via", null));
    }

    @Test
    void rejectsInvalidDepartureTimesWithoutNetwork() {
        for (String value : new String[]{"", "2026-11-07", "2026-11-07T15:22", "2026-11-07 15:22:00",
                "2026-02-29 15:22", "2026-11-31 15:22", "2026-11-07 24:00", "2026-11-07 15:60",
                "2026-1-07 15:22", " 2026-11-07 15:22", "not a date"}) {
            StringWriter err = new StringWriter();
            int code = new CommandLine(new Main()).setErr(new PrintWriter(err))
                    .execute("--from", "A", "--to", "B", "--departure", value);
            assertEquals(2, code, value);
            assertTrue(err.toString().contains("YYYY-MM-DD HH:mm"), value);
        }
        assertEquals(LocalDateTime.of(2028, 2, 29, 0, 0),
                new Main.DepartureTimeConverter().convert("2028-02-29 00:00"));
    }

    @Test
    void rejectsOldGermanOptionsWithoutNetwork() {
        var command = new CommandLine(new Main());
        assertNull(command.getCommandSpec().findOption("--start"));
        assertNull(command.getCommandSpec().findOption("--ziel"));
        assertNull(command.getCommandSpec().findOption("--startzeit"));
        for (String[] args : new String[][]{
                {"--start", "A", "--to", "B"}, {"--from", "A", "--ziel", "B"},
                {"--from", "A", "--to", "B", "--startzeit", "2026-11-07 15:22"}}) {
            int code = new CommandLine(new Main()).setErr(new PrintWriter(new StringWriter())).execute(args);
            assertEquals(2, code);
        }
    }

    @Test
    void invalidArgumentsReturnUsageErrorWithoutNetwork() {
        for (String[] args : new String[][]{
                {}, {"--from", "A"}, {"--to", "B"},
                {"--from", " ", "--to", "B"}, {"--from", "A", "--to", " "},
                {"--from", "A", "--to", "B", "--via", ""},
                {"--from", "A", "--to", "B", "--via", " "},
                {"--from", "A", "--to", "B", "--via"},
                {"--from", "A", "--to", "B", "--limit", "0"},
                {"--from", "A", "--to", "B", "--timeout", "-1"}}) {
            int code = new CommandLine(new Main()).setErr(new PrintWriter(new StringWriter())).execute(args);
            assertEquals(2, code);
        }
    }
}
