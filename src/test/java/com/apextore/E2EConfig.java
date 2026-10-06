package com.apexstore.client.e2e;

import java.time.Duration;
import java.util.regex.Pattern;

/** Configuración de la prueba E2E, leída de variables de entorno (todas opcionales). */
record E2EConfig(
        String host,
        int port,
        String dbUrl,
        String dbUser,
        String dbPassword,
        String table,
        String orderColumn,
        String statusColumn,
        Duration callbackTimeout) {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    static E2EConfig fromEnv() {
        return new E2EConfig(
                env("E2E_HOST", "localhost"),
                Integer.parseInt(env("E2E_PORT", "10002")),
                env("E2E_DB_URL", ""),                         // vacío = no verificar la BD
                env("E2E_DB_USER", "postgres"),
                env("E2E_DB_PASSWORD", "postgres"),
                identifier(env("E2E_TABLE", "transacciones")),
                // AJUSTAR: nombres reales de columnas en schema.sql
                identifier(env("E2E_ORDER_COLUMN", "id_orden")),
                identifier(env("E2E_STATUS_COLUMN", "estado")),
                Duration.ofSeconds(Long.parseLong(env("E2E_CALLBACK_TIMEOUT_SECONDS", "20"))));
    }

    boolean dbEnabled() {
        return !dbUrl.isBlank();
    }

    String proxyString() {
        return "CheckoutService:default -h %s -p %d".formatted(host, port);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** Los nombres de tabla/columna se concatenan en el SQL: solo se admiten identificadores simples. */
    private static String identifier(String value) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Identificador SQL inválido: " + value);
        }
        return value;
    }
}
