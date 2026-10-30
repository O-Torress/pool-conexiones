package com.proyecto2.client;

import com.proyecto2.database.DatabaseConfig;
import com.proyecto2.database.PostgresConnectionFactory;
import com.proyecto2.metrics.MetricsCollector;
import com.proyecto2.pool.ConnectionPool;
import com.proyecto2.query.QueryRandomizer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public final class SakilaSimulationDemo {

    private static final int CLIENT_COUNT = 10;
    private static final int REQUESTS_PER_CLIENT = 20;
    private static final int POOL_SIZE = 5;
    private static final int MAX_RETRIES = 1;
    private static final long RETRY_BACKOFF_MILLIS = 25L;
    private static final long POOL_TIMEOUT_MILLIS = 10_000L;

    private SakilaSimulationDemo() {
    }

    public static void main(String[] args) throws Exception {
        DatabaseConfig config = DatabaseConfig.load();
        System.out.println("Base de datos: " + config.getSafeDescription());

        try {
            verifyConnection(config);
        } catch (SQLException e) {
            System.err.println();
            System.err.println("No se pudo conectar a Sakila.");
            System.err.println("Revisa database.properties o las variables:");
            System.err.println("  SAKILA_DB_URL, SAKILA_DB_USER, SAKILA_DB_PASSWORD");
            System.err.println("Detalle: " + e.getMessage());
            return;
        }

        PostgresConnectionFactory factory =
            new PostgresConnectionFactory(config);
        QueryRandomizer randomizer = new QueryRandomizer();

        System.out.println();
        System.out.println("Corrida: " + CLIENT_COUNT + " clientes x "
            + REQUESTS_PER_CLIENT + " peticiones = "
            + (CLIENT_COUNT * REQUESTS_PER_CLIENT) + " peticiones");
        System.out.println("Pool size: " + POOL_SIZE
            + " | Reintentos maximos: " + MAX_RETRIES);
        System.out.println();

        SimulationResult withPool =
            runWithPool(factory, randomizer);
        SimulationResult withoutPool =
            runWithoutPool(factory, randomizer);

        printResult("CON POOL", withPool);
        printResult("SIN POOL", withoutPool);

        double poolAverage = withPool.metrics.getAverageResponseTime();
        double noPoolAverage = withoutPool.metrics.getAverageResponseTime();
        if (noPoolAverage > 0.0) {
            double improvement =
                ((noPoolAverage - poolAverage) / noPoolAverage) * 100.0;
            System.out.printf(
                "Mejora del tiempo promedio usando pool: %.2f%%%n",
                improvement
            );
        }
    }

    private static void verifyConnection(DatabaseConfig config)
            throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", config.getUser());
        properties.setProperty("password", config.getPassword());
        properties.setProperty("connectTimeout", "5");

        try (Connection connection =
                 DriverManager.getConnection(config.getUrl(), properties);
             Statement statement = connection.createStatement()) {

            try (ResultSet resultSet =
                     statement.executeQuery("SELECT version()")) {
                if (resultSet.next()) {
                    System.out.println("Conectado a: " + resultSet.getString(1));
                }
            }

            try (ResultSet resultSet =
                     statement.executeQuery("SELECT COUNT(*) FROM film")) {
                if (resultSet.next()) {
                    System.out.println("Films en Sakila: " + resultSet.getLong(1));
                }
            }

            try (ResultSet resultSet =
                     statement.executeQuery("SELECT COUNT(*) FROM actor")) {
                if (resultSet.next()) {
                    System.out.println("Actores en Sakila: " + resultSet.getLong(1));
                }
            }
        }
    }

    private static SimulationResult runWithPool(
            PostgresConnectionFactory factory,
            QueryRandomizer randomizer) throws InterruptedException, SQLException {
        MetricsCollector metrics = new MetricsCollector();
        ConnectionPool pool =
            new ConnectionPool(POOL_SIZE, factory, POOL_TIMEOUT_MILLIS);

        try {
            pool.warmUp();
            long elapsedMillis =
                runClients(true, pool, factory, randomizer, metrics);
            return new SimulationResult(metrics, elapsedMillis, pool.getStats());
        } finally {
            pool.close();
        }
    }

    private static SimulationResult runWithoutPool(
            PostgresConnectionFactory factory,
            QueryRandomizer randomizer) throws InterruptedException {
        MetricsCollector metrics = new MetricsCollector();
        long elapsedMillis =
            runClients(false, null, factory, randomizer, metrics);
        return new SimulationResult(metrics, elapsedMillis, null);
    }

    private static long runClients(
            boolean withPool,
            ConnectionPool pool,
            PostgresConnectionFactory factory,
            QueryRandomizer randomizer,
            MetricsCollector metrics) throws InterruptedException {

        List<Thread> threads = new ArrayList<>();

        for (int i = 1; i <= CLIENT_COUNT; i++) {
            ClientThread client = withPool
                ? ClientThread.withPool(
                    i, REQUESTS_PER_CLIENT, pool, randomizer, metrics,
                    MAX_RETRIES, RETRY_BACKOFF_MILLIS)
                : ClientThread.withoutPool(
                    i, REQUESTS_PER_CLIENT, factory, randomizer, metrics,
                    MAX_RETRIES, RETRY_BACKOFF_MILLIS);
            threads.add(new Thread(client, "cliente-" + i));
        }

        long startNanos = System.nanoTime();
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void printResult(String title, SimulationResult result) {
        System.out.println("===== " + title + " =====");
        System.out.println("Tiempo de pared: " + result.elapsedMillis + " ms");
        if (result.poolStats != null) {
            System.out.println("Estado final del pool: " + result.poolStats);
        }
        System.out.println(result.metrics.getSummary());
        System.out.println();
    }

    private static final class SimulationResult {
        private final MetricsCollector metrics;
        private final long elapsedMillis;
        private final ConnectionPool.PoolStats poolStats;

        private SimulationResult(MetricsCollector metrics, long elapsedMillis,
                                 ConnectionPool.PoolStats poolStats) {
            this.metrics = metrics;
            this.elapsedMillis = elapsedMillis;
            this.poolStats = poolStats;
        }
    }
}
