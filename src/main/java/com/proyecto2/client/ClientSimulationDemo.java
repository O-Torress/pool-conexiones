package com.proyecto2.client;

import com.proyecto2.database.SimulatedDatabase;
import com.proyecto2.metrics.MetricsCollector;
import com.proyecto2.pool.ConnectionPool;
import com.proyecto2.query.QueryRandomizer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Demo por consola: compara CON pool vs SIN pool contra un servidor de base
 * de datos simulado (max_connections + CPU finita). No requiere PostgreSQL.
 *
 * Uso opcional: ClientSimulationDemo [clientes] [tamanoPool]
 */
public final class ClientSimulationDemo {

    private static final int DEFAULT_CLIENT_COUNT = 200;
    private static final int REQUESTS_PER_CLIENT = 1;
    private static final int DEFAULT_POOL_SIZE = 10;
    private static final long BORROW_TIMEOUT_MILLIS = 30_000L;
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_BACKOFF_MILLIS = 10L;

    // Servidor simulado (valores tipicos de PostgreSQL en un equipo modesto)
    private static final int DB_MAX_CONNECTIONS = 100;
    private static final int DB_CPU_CORES = 8;
    private static final long CONNECT_LATENCY_MILLIS = 50L;
    private static final long QUERY_LATENCY_MILLIS = 10L;
    private static final double QUERY_FAILURE_RATE = 0.0;

    private ClientSimulationDemo() {
    }

    public static void main(String[] args) throws Exception {
        int clientCount = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_CLIENT_COUNT;
        int poolSize = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_POOL_SIZE;
        QueryRandomizer randomizer = new QueryRandomizer();

        System.out.println("Simulacion: " + clientCount + " clientes x "
            + REQUESTS_PER_CLIENT + " peticion = "
            + (clientCount * REQUESTS_PER_CLIENT) + " peticiones logicas");
        System.out.println("Pool: " + poolSize + " | BD: max_connections="
            + DB_MAX_CONNECTIONS + ", CPU=" + DB_CPU_CORES
            + ", abrir=" + CONNECT_LATENCY_MILLIS + " ms, query="
            + QUERY_LATENCY_MILLIS + " ms | Reintentos maximos: " + MAX_RETRIES);
        System.out.println();

        SimulatedDatabase poolDb = newDatabase();
        MetricsCollector withPool = runWithPool(poolDb, randomizer, clientCount, poolSize);
        SimulatedDatabase noPoolDb = newDatabase();
        MetricsCollector withoutPool = runWithoutPool(noPoolDb, randomizer, clientCount);

        System.out.println();
        System.out.println("===== CON POOL =====");
        System.out.println(withPool.getSummary());
        System.out.println(poolDb);
        System.out.println();
        System.out.println("===== SIN POOL =====");
        System.out.println(withoutPool.getSummary());
        System.out.println(noPoolDb);
        System.out.println();

        double poolAverage = withPool.getAverageSuccessResponseTime();
        double noPoolAverage = withoutPool.getAverageSuccessResponseTime();
        if (noPoolAverage > 0.0) {
            double improvement =
                ((noPoolAverage - poolAverage) / noPoolAverage) * 100.0;
            System.out.printf(
                "Mejora del tiempo promedio (exitosas) usando pool: %.2f%%%n",
                improvement
            );
        }
    }

    private static SimulatedDatabase newDatabase() {
        return new SimulatedDatabase(DB_MAX_CONNECTIONS, DB_CPU_CORES,
            CONNECT_LATENCY_MILLIS, QUERY_LATENCY_MILLIS, QUERY_FAILURE_RATE);
    }

    private static MetricsCollector runWithPool(
            SimulatedDatabase database, QueryRandomizer randomizer,
            int clientCount, int poolSize) throws InterruptedException {
        MetricsCollector metrics = new MetricsCollector();
        ConnectionPool pool = new ConnectionPool(poolSize, database, BORROW_TIMEOUT_MILLIS);
        try {
            pool.warmUp();
            runClients(true, pool, database, randomizer, metrics, clientCount);
            System.out.println("Estadisticas del pool: " + pool.getStats());
            return metrics;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo inicializar el pool", e);
        } finally {
            pool.close();
        }
    }

    private static MetricsCollector runWithoutPool(
            SimulatedDatabase database, QueryRandomizer randomizer,
            int clientCount) throws InterruptedException {
        MetricsCollector metrics = new MetricsCollector();
        runClients(false, null, database, randomizer, metrics, clientCount);
        return metrics;
    }

    private static void runClients(boolean withPool, ConnectionPool pool,
                                   ConnectionPool.ConnectionFactory factory,
                                   QueryRandomizer randomizer,
                                   MetricsCollector metrics, int clientCount)
            throws InterruptedException {
        List<Thread> threads = new ArrayList<>();

        for (int i = 1; i <= clientCount; i++) {
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
