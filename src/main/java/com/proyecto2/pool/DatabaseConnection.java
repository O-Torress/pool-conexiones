package com.proyecto2.pool;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Conexion del pool.
 *
 * Tiene dos modos:
 *  - JDBC: envuelve una Connection real de PostgreSQL (Sakila).
 *  - Simulada: no usa base de datos; sirve para tests y demos sin PostgreSQL.
 *
 * En ambos modos el pool solo ve executeQuery(), isValid() y close().
 */
public class DatabaseConnection implements AutoCloseable {

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private static final double DEFAULT_QUERY_FAILURE_RATE = 0.10;
    private static final long DEFAULT_CONNECT_LATENCY_MILLIS = 40L;
    private static final long DEFAULT_QUERY_LATENCY_MILLIS = 10L;
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 10;

    private final int id;
    private final Connection jdbcConnection;
    private final double simulatedQueryFailureRate;
    private final long simulatedQueryLatencyMillis;
    private final int queryTimeoutSeconds;

    private volatile boolean closed;

    /**
     * Conexion simulada con los valores por defecto. Se mantiene para que
     * ConnectionPool(int) pueda usarse sin base de datos.
     */
    public DatabaseConnection() {
        this(
            DEFAULT_QUERY_FAILURE_RATE,
            DEFAULT_CONNECT_LATENCY_MILLIS,
            DEFAULT_QUERY_LATENCY_MILLIS
        );
    }

    /**
     * Conexion JDBC real.
     */
    public DatabaseConnection(Connection jdbcConnection) {
        this(jdbcConnection, DEFAULT_QUERY_TIMEOUT_SECONDS);
    }

    public DatabaseConnection(Connection jdbcConnection, int queryTimeoutSeconds) {
        if (jdbcConnection == null) {
            throw new IllegalArgumentException("jdbcConnection no puede ser null");
        }
        if (queryTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                "queryTimeoutSeconds debe ser mayor que 0");
        }
        this.id = NEXT_ID.getAndIncrement();
        this.jdbcConnection = jdbcConnection;
        this.simulatedQueryFailureRate = 0.0;
        this.simulatedQueryLatencyMillis = 0L;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /**
     * Conexion simulada, sin base de datos. Se conserva para tests y para la
     * demo independiente que no requiere PostgreSQL.
     */
    public DatabaseConnection(double queryFailureRate,
                              long connectLatencyMillis,
                              long queryLatencyMillis) {
        if (queryFailureRate < 0.0 || queryFailureRate > 1.0) {
            throw new IllegalArgumentException(
                "queryFailureRate debe estar entre 0.0 y 1.0");
        }
        if (connectLatencyMillis < 0 || queryLatencyMillis < 0) {
            throw new IllegalArgumentException("Las latencias no pueden ser negativas");
        }

        this.id = NEXT_ID.getAndIncrement();
        this.jdbcConnection = null;
        this.simulatedQueryFailureRate = queryFailureRate;
        this.simulatedQueryLatencyMillis = queryLatencyMillis;
        this.queryTimeoutSeconds = DEFAULT_QUERY_TIMEOUT_SECONDS;

        sleepOrThrow(connectLatencyMillis, "apertura de la conexion " + id);
    }

    public String executeQuery(String query) throws SQLException {
        if (closed) {
            throw new SQLException("La conexion " + id + " esta cerrada");
        }
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("La query no puede estar vacia");
        }

        if (jdbcConnection == null) {
            return executeSimulatedQuery(query);
        }
        return executeJdbcQuery(query);
    }

    private String executeSimulatedQuery(String query) throws SQLException {
        try {
            Thread.sleep(simulatedQueryLatencyMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException(
                "Ejecucion interrumpida en la conexion " + id, e);
        }

        if (closed) {
            throw new SQLException(
                "La conexion " + id + " se cerro durante la consulta");
        }

        if (ThreadLocalRandom.current().nextDouble() < simulatedQueryFailureRate) {
            throw new SQLException(
                "Fallo simulado de la query en la conexion " + id);
        }

        return "OK[" + id + "] simulada: " + query;
    }

    private String executeJdbcQuery(String query) throws SQLException {
        try (Statement statement = jdbcConnection.createStatement()) {
            statement.setQueryTimeout(queryTimeoutSeconds);

            boolean hasResultSet = statement.execute(query);
            if (!hasResultSet) {
                return "OK[" + id + "] actualizadas=" + statement.getUpdateCount();
            }

            try (ResultSet resultSet = statement.getResultSet()) {
                int rows = 0;
                while (resultSet.next()) {
                    rows++;
                }
                return "OK[" + id + "] filas=" + rows;
            }
        } catch (SQLException e) {
            throw new SQLException(
                "Error en la conexion " + id + ": " + e.getMessage(), e);
        }
    }

    /**
     * Valida la conexion. En modo JDBC hace un ping liviano a PostgreSQL.
     */
    public boolean isValid() {
        if (closed) {
            return false;
        }
        if (jdbcConnection == null) {
            return true;
        }
        try {
            return jdbcConnection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isJdbc() {
        return jdbcConnection != null;
    }

    public int getId() {
        return id;
    }

    public double getQueryFailureRate() {
        return simulatedQueryFailureRate;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (jdbcConnection != null) {
            try {
                jdbcConnection.close();
            } catch (SQLException ignored) {
                // Cierre best-effort.
            }
        }
    }

    private static void sleepOrThrow(long millis, String context) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Creacion interrumpida: " + context, e);
        }
    }

    @Override
    public String toString() {
        return "DatabaseConnection{id=" + id
            + ", modo=" + (jdbcConnection == null ? "simulada" : "jdbc")
            + ", closed=" + closed + '}';
    }
}
