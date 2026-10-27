package me.fertiz.queryscope.agent.trace;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParameterValuesTest {

    @Test
    void showsSimpleValuesAsTheyAre() {
        assertEquals("42", ParameterValues.describe("setInt", 42));
        assertEquals("19.99", ParameterValues.describe("setBigDecimal", new BigDecimal("19.99")));
        assertEquals("2026-10-27", ParameterValues.describe("setObject", LocalDate.of(2026, 10, 27)));
        assertEquals("'Customer 7'", ParameterValues.describe("setString", "Customer 7"));
    }

    @Test
    void treatsSetNullAsNullWhateverTheSqlType() {
        assertEquals("null", ParameterValues.describe("setNull", 12));
        assertEquals("null", ParameterValues.describe("setString", null));
    }

    @Test
    void keepsBigValuesOutOfMemory() {
        assertEquals("'" + "x".repeat(64) + "...'", ParameterValues.describe("setString", "x".repeat(500)));
        assertEquals("<3 bytes>", ParameterValues.describe("setBytes", new byte[3]));
        assertEquals("<ByteArrayInputStream>",
                ParameterValues.describe("setBinaryStream", new ByteArrayInputStream(new byte[0])));
    }
}
