package com.example.wordchain;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SQLiteDriverTest {
    @Test
    void sqliteDriverOpensAndQueriesAnInMemoryDatabase() throws Exception {
        try (Connection connection = new org.sqlite.JDBC().connect("jdbc:sqlite::memory:", new Properties());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 42")) {
            assertNotNull(connection);
            result.next();
            assertEquals(42, result.getInt(1));
        }
    }
}
