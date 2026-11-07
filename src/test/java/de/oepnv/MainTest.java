package de.oepnv;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @Test
    void helpDoesNotAccessNetwork() {
        StringWriter out = new StringWriter();
        int code = new CommandLine(new Main()).setOut(new PrintWriter(out)).execute("--help");
        assertEquals(0, code);
        assertTrue(out.toString().contains("--start"));
        assertTrue(out.toString().contains("--ziel"));
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
