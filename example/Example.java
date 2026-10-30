// A complete program that uses vsql (after mvn package):
//
//   java --enable-native-access=ALL-UNNAMED -cp target/vsql-0.0.0-SNAPSHOT.jar example/Example.java

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

public class Example {
    public static void main(String[] args) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:vsql::memory:")) {
            try (Statement s = conn.createStatement()) {
                s.execute("""
                        CREATE TABLE products (
                            id INTEGER NOT NULL,
                            name VARCHAR(50) NOT NULL,
                            price NUMERIC(10, 2),
                            added DATE)""");
            }

            conn.setAutoCommit(false);
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO products (id, name, price, added) VALUES (?, ?, ?, ?)")) {
                Object[][] rows = {
                    {1, "Bolt", new BigDecimal("0.25"), LocalDate.of(2024, 1, 2)},
                    {2, "Nut", new BigDecimal("0.10"), LocalDate.of(2024, 3, 4)},
                    {3, "Washer", null, null},
                };
                for (Object[] row : rows) {
                    for (int i = 0; i < row.length; i++) {
                        insert.setObject(i + 1, row[i]);
                    }
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            conn.commit();
            conn.setAutoCommit(true);

            try (PreparedStatement query = conn.prepareStatement(
                    "SELECT name, price, added FROM products WHERE id > ? ORDER BY id")) {
                query.setInt(1, 1);
                try (ResultSet rs = query.executeQuery()) {
                    System.out.println("[" + rs.getMetaData().getColumnName(1) + ", "
                            + rs.getMetaData().getColumnName(2) + ", " + rs.getMetaData().getColumnName(3) + "]");
                    while (rs.next()) {
                        System.out.println(rs.getString("name") + " " + rs.getBigDecimal("price") + " "
                                + rs.getObject("added", LocalDate.class));
                    }
                }
            }

            try (Statement s = conn.createStatement()) {
                s.executeQuery("SELECT * FROM nope");
            } catch (SQLException e) {
                System.out.println(e.getSQLState() + ": " + e.getMessage());
            }
        }
    }
}
