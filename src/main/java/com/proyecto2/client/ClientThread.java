package com.proyecto2.client;

import com.proyecto2.metrics.MetricsCollector;
import com.proyecto2.pool.ConnectionPool;
import com.proyecto2.pool.ConnectionPool.ConnectionFactory;
import com.proyecto2.pool.ConnectionPool.PoolTimeoutException;
import com.proyecto2.pool.DatabaseConnection;
import com.proyecto2.query.QueryRandomizer;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cliente concurrente que ejecuta un conjunto de peticiones.
 *
 * Puede trabajar de dos formas:
 *  - WITH_POOL: pide una conexion al pool y la devuelve al terminar.
 *  - WITHOUT_POOL: abre una conexion nueva por cada intento.
 *
 * En ambos casos se registra UNA metrica por peticion logica. El tiempo de
 * respuesta incluye la espera por conexion, todos los intentos y el backoff.
 * Los reintentos se informan aparte.
 */
public class ClientThread implements Runnable {

    public enum Mode {
        WITH_POOL,
        WITHOUT_POOL
    }

    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final long DEFAULT_RETRY_BACKOFF_MILLIS = 20L;

    private final int clientId;
    private final int requestCount;
    private final Mode mode;
    private final ConnectionPool pool;
    private final ConnectionFactory connectionFactory;
    private final QueryRandomizer queryRandomizer;
    private final MetricsCollector metricsCollector;
    private final int maxRetries;
    private final long retryBackoffMillis;

    private final AtomicInteger completedRequests = new AtomicInteger();
    private volatile boolean stopRequested;
    private volatile boolean running;

    public ClientThread(int clientId, int requestCount, Mode mode,
                        ConnectionPool pool, ConnectionFactory connectionFactory,
                        QueryRandomizer queryRandomizer,
                        MetricsCollector metricsCollector,
                        int maxRetries, long retryBackoffMillis) {
        if (clientId <= 0) {
            throw new IllegalArgumentException("clientId debe ser mayor que 0");
        }
        if (requestCount < 0) {
            throw new IllegalArgumentException("requestCount no puede ser negativo");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode no puede ser null");
        }
        if (mode == Mode.WITH_POOL && pool == null) {
            throw new IllegalArgumentException("El modo WITH_POOL requiere un pool");
        }
        if (mode == Mode.WITHOUT_POOL && connectionFactory == null) {
            throw new IllegalArgumentException(
                "El modo WITHOUT_POOL requiere una ConnectionFactory");
        }
        if (queryRandomizer == null) {
            throw new IllegalArgumentException("queryRandomizer no puede ser null");
        }
        if (metricsCollector == null) {
            throw new IllegalArgumentException("metricsCollector no puede ser null");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries no puede ser negativo");
        }
        if (retryBackoffMillis < 0) {
            throw new IllegalArgumentException("retryBackoffMillis no puede ser negativo");
        }

        this.clientId = clientId;
        this.requestCount = requestCount;
        this.mode = mode;
        this.pool = pool;
        this.connectionFactory = connectionFactory;
        this.queryRandomizer = queryRandomizer;
        this.metricsCollector = metricsCollector;
        this.maxRetries = maxRetries;
        this.retryBackoffMillis = retryBackoffMillis;
    }

    public static ClientThread withPool(
            int clientId, int requestCount, ConnectionPool pool,
            QueryRandomizer queryRandomizer, MetricsCollector metricsCollector) {
        return withPool(
            clientId, requestCount, pool, queryRandomizer, metricsCollector,
            DEFAULT_MAX_RETRIES, DEFAULT_RETRY_BACKOFF_MILLIS
        );
    }

    public static ClientThread withPool(
            int clientId, int requestCount, ConnectionPool pool,
            QueryRandomizer queryRandomizer, MetricsCollector metricsCollector,
            int maxRetries, long retryBackoffMillis) {
        return new ClientThread(
            clientId, requestCount, Mode.WITH_POOL, pool, null,
            queryRandomizer, metricsCollector, maxRetries, retryBackoffMillis
        );
    }

    public static ClientThread withoutPool(
            int clientId, int requestCount, ConnectionFactory connectionFactory,
            QueryRandomizer queryRandomizer, MetricsCollector metricsCollector) {
        return withoutPool(
            clientId, requestCount, connectionFactory, queryRandomizer,
            metricsCollector, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_BACKOFF_MILLIS
        );
    }

    public static ClientThread withoutPool(
            int clientId, int requestCount, ConnectionFactory connectionFactory,
            QueryRandomizer queryRandomizer, MetricsCollector metricsCollector,
            int maxRetries, long retryBackoffMillis) {
        return new ClientThread(
            clientId, requestCount, Mode.WITHOUT_POOL, null, connectionFactory,
            queryRandomizer, metricsCollector, maxRetries, retryBackoffMillis
        );
    }

    @Override
    public void run() {
        running = true;
        try {
            for (int i = 0; i < requestCount; i++) {
                if (stopRequested || Thread.currentThread().isInterrupted()) {
                    break;
                }
                executeRequest();
                completedRequests.incrementAndGet();
            }
        } finally {
            running = false;
        }
    }

    private void executeRequest() {
        String query = queryRandomizer.getRandomQuery();
        long startNanos = System.nanoTime();
        int retries = 0;
        boolean success = false;
        boolean cancelled = false;

        try {
            while (true) {
                DatabaseConnection connection = null;
                boolean attemptSucceeded = false;
                boolean interrupted = false;

                try {
                    connection = acquireConnection();
                    connection.executeQuery(query);
                    attemptSucceeded = true;
                } catch (SQLException e) {
                    // Fallo de query o de apertura: se evalua el reintento abajo.
                } catch (PoolTimeoutException e) {
                    // No habia conexion disponible dentro del timeout.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                } finally {
                    // Se libera ANTES del backoff para no retener una conexion.
                    releaseConnection(connection);
                }

                if (attemptSucceeded) {
                    success = true;
                    break;
                }
                if (interrupted || Thread.currentThread().isInterrupted()) {
                    cancelled = true;
                    break;
                }
                if (retries >= maxRetries) {
                    break;
                }
                retries++;
                if (!sleepBeforeRetry(retries)) {
                    cancelled = true;
                    break;
                }
            }
        } catch (RuntimeException e) {
            success = false;
            if (e instanceof IllegalStateException) {
                // Pool cerrado: no tiene sentido seguir con las demas peticiones.
                stopRequested = true;
            }
        } finally {
            if (!cancelled) {
                metricsCollector.recordRequest(
                    success,
                    System.nanoTime() - startNanos,
                    retries
                );
            }
        }
    }

    private DatabaseConnection acquireConnection()
            throws SQLException, InterruptedException, PoolTimeoutException {
        if (mode == Mode.WITH_POOL) {
            return pool.borrow();
        }
        return connectionFactory.create();
    }

    private void releaseConnection(DatabaseConnection connection) {
        if (connection == null) {
            return;
        }
        if (mode == Mode.WITH_POOL) {
            pool.release(connection);
        } else {
            connection.close();
        }
    }

    private boolean sleepBeforeRetry(int retryNumber) {
        if (retryBackoffMillis <= 0) {
            return true;
        }
        try {
            Thread.sleep(retryBackoffMillis * retryNumber);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Solicita una parada cooperativa. La peticion en curso termina su intento
     * actual; las siguientes ya no se ejecutan.
     */
    public void requestStop() {
        stopRequested = true;
    }

    public int getClientId() {
        return clientId;
    }

    public int getRequestCount() {
        return requestCount;
    }

    public int getCompletedRequests() {
        return completedRequests.get();
    }

    public Mode getMode() {
        return mode;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isStopRequested() {
        return stopRequested;
    }
}
