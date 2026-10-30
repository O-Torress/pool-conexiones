package com.proyecto2.client;

import com.proyecto2.metrics.MetricsCollector;
import com.proyecto2.pool.ConnectionPool;
import com.proyecto2.pool.DatabaseConnection;
import com.proyecto2.query.QueryRandomizer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ClientSimulationDemo {

    private static final int CLIENT_COUNT = 20;
    private static final int REQUESTS_PER_CLIENT = 50;
    private static final int POOL_SIZE = 10;
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_BACKOFF_MILLIS = 10L;
    private static final double QUERY_FAILURE_RATE = 0.10;
    private static final long CONNECT_LATENCY_MILLIS = 50L;
    private static final long QUERY_LATENCY_MILLIS = 10L;

    private ClientSimulationDemo() {
    }

    public static void main(String[] args) throws Exception {
        ConnectionPool.ConnectionFactory factory = () ->
            new DatabaseConnection(
                QUERY_FAILURE_RATE,
                CONNECT_LATENCY_MILLIS,
                QUERY_LATENCY_MILLIS
            );
        QueryRandomizer randomizer = new QueryRandomizer();

        System.out.println("Simulacion: " + CLIENT_COUNT + " clientes x "
            + REQUESTS_PER_CLIENT + " peticiones = "
            + (CLIENT_COUNT * REQUESTS_PER_CLIENT) + " peticiones logicas");
        System.out.println("Pool size: " + POOL_SIZE
            + " | Fallo por query: " + (QUERY_FAILURE_RATE * 100.0) + "%"
            + " | Reintentos maximos: " + MAX_RETRIES);
        System.out.println();

        MetricsCollector withPool = runWithPool(factory, randomizer);
        MetricsCollector withoutPool = runWithoutPool(factory, randomizer);

        System.out.println("===== CON POOL =====");
        System.out.println(withPool.getSummary());
        System.out.println();
        System.out.println("===== SIN POOL =====");
        System.out.println(withoutPool.getSummary());
        System.out.println();

        double poolAverage = withPool.getAverageResponseTime();
        double noPoolAverage = withoutPool.getAverageResponseTime();
        if (noPoolAverage > 0.0) {
            double improvement =
                ((noPoolAverage - poolAverage) / noPoolAverage) * 100.0;
            System.out.printf(
                "Mejora del tiempo promedio usando pool: %.2f%%%n",
                improvement
            );
        }
    }

    private static MetricsCollector runWithPool(
            ConnectionPool.ConnectionFactory factory,
            QueryRandomizer randomizer) throws InterruptedException {
        MetricsCollector metrics = new MetricsCollector();
        ConnectionPool pool = new ConnectionPool(POOL_SIZE, factory);
        try {
            pool.warmUp();
            runClients(true, pool, factory, randomizer, metrics);
            return metrics;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo inicializar el pool", e);
        } finally {
            pool.close();
        }
    }

    private static MetricsCollector runWithoutPool(
            ConnectionPool.ConnectionFactory factory,
            QueryRandomizer randomizer) throws InterruptedException {
        MetricsCollector metrics = new MetricsCollector();
        runClients(false, null, factory, randomizer, metrics);
        return metrics;
    }

    private static void runClients(boolean withPool, ConnectionPool pool,
                                   ConnectionPool.ConnectionFactory factory,
                                   QueryRandomizer randomizer,
                                   MetricsCollector metrics)
            throws InterruptedException {
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
        long elapsedMillis =
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        System.out.printf(
            "%s -> tiempo total de pared: %d ms%n",
            withPool ? "CON POOL" : "SIN POOL",
            elapsedMillis
        );
    }
}
