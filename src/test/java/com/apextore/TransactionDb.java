package com.apexstore.client.e2e;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

/** Consulta de solo lectura a la tabla de transacciones, para verificar el resultado final. */
final class TransactionDb {

    private static final String PENDING = "PENDIENTE";

    private final E2EConfig config;
    private final String sql;

    TransactionDb(E2EConfig config) {
        this.config = config;
        this.sql = "SELECT %s FROM %s WHERE %s = ?"
                .formatted(config.statusColumn(), config.table(), config.orderColumn());
    }

    Optional<String> findStatus(String orderId) throws SQLException {
        try (Connection c = DriverManager.getConnection(config.dbUrl(), config.dbUser(), config.dbPassword());
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** Espera (sondeando) a que la transacción salga de PENDIENTE; devuelve el último estado visto. */
    String awaitFinalStatus(String orderId, Duration timeout) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = findStatus(orderId).orElse("(sin fila)");
        while (PENDING.equals(last) && System.nanoTime() < deadline) {
            Thread.sleep(500);
            last = findStatus(orderId).orElse("(sin fila)");
        }
        return last;
    }
}
