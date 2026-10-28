package me.fertiz.queryscope.agent.findings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlNormalizerTest {

    @Test
    void replacesNumberAndStringLiterals() {
        assertEquals("select * from customers where id = ? and name = ?",
                SqlNormalizer.normalize("select * from customers where id = 42 and name = 'O''Brien'"));
    }

    @Test
    void keepsDigitsThatArePartOfIdentifiers() {
        String hibernate = "select o1_0.id,o1_0.total from orders o1_0 where o1_0.customer_id=?";

        assertEquals(hibernate, SqlNormalizer.normalize(hibernate));
    }

    @Test
    void lowercasesAndCollapsesWhitespace() {
        assertEquals("select type, count(*) from audit_events group by type",
                SqlNormalizer.normalize("SELECT  type,\n       COUNT(*)\nFROM audit_events\n\tGROUP BY type;"));
    }

    @Test
    void collapsesInListsOfAnyLength() {
        assertEquals("select * from orders where id in (?)",
                SqlNormalizer.normalize("select * from orders where id in (1, 2, 3)"));
        assertEquals("select * from orders where id in (?)",
                SqlNormalizer.normalize("select * from orders where id IN(?,?)"));
    }

    @Test
    void dropsComments() {
        assertEquals("select * from orders where total > ?",
                SqlNormalizer.normalize("/* load orders */ select * from orders -- big ones\nwhere total > 10.50"));
    }

    @Test
    void keepsQuotedIdentifiersAsWritten() {
        assertEquals("select \"Total Spent\" from \"Customers\" where note = ?",
                SqlNormalizer.normalize("SELECT \"Total Spent\" FROM \"Customers\" WHERE note = 'vip'"));
    }
}
