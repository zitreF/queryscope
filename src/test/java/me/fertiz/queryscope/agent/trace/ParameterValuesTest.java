package me.fertiz.queryscope.agent.trace;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParameterValuesTest {

    @Test
    void showsSimpleValuesAsTheyAre() {
        assertEquals("42", ParameterValues.format("setInt", 42));
        assertEquals("19.99", ParameterValues.format("setBigDecimal", new BigDecimal("19.99")));
        assertEquals("2026-10-27", ParameterValues.format("setObject", LocalDate.of(2026, 10, 27)));
        assertEquals("'Customer 7'", ParameterValues.format("setString", "Customer 7"));
    }

    @Test
    void treatsSetNullAsNullWhateverTheSqlType() {
        assertEquals("null", ParameterValues.format("setNull", 12));
        assertEquals("null", ParameterValues.format("setString", null));
    }

    @Test
    void keepsBigValuesOutOfMemory() {
        assertEquals("'" + "x".repeat(64) + "...'", ParameterValues.format("setString", "x".repeat(500)));
        assertEquals("<3 bytes>", ParameterValues.format("setBytes", new byte[3]));
        assertEquals("<ByteArrayInputStream>",
                ParameterValues.format("setBinaryStream", new ByteArrayInputStream(new byte[0])));
    }
}
