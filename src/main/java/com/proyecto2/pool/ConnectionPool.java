package com.proyecto2.pool;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pool de conexiones reutilizables, seguro para uso concurrente.
 *
 * Invariantes:
 *  - Todo el estado del pool se protege con {@link #lock}.
 *  - Las conexiones se crean fuera del lock para no bloquear a los demas hilos.
 *  - Una conexion esta exactamente en uno de estos lugares: idle, inUse,
 *    pendiente de creacion, o cerrada.
 */
public class ConnectionPool implements AutoCloseable {

    public static final int DEFAULT_MAX_SIZE = 10;
    public static final long DEFAULT_BORROW_TIMEOUT_MILLIS = 5_000L;

    @FunctionalInterface
    public interface ConnectionFactory {
        DatabaseConnection create() throws SQLException;
    }

    public static final class PoolTimeoutException extends Exception {
        private static final long serialVersionUID = 1L;

        public PoolTimeoutException(String message) {
            super(message);
        }
    }

    public static final class PoolStats {
        private final int maxSize;
        private final int idleConnections;
        private final int connectionsInUse;
        private final int waitingThreads;
        private final long totalCreated;
        private final long totalBorrowed;
        private final long totalReleased;
        private final long totalTimeouts;
        private final boolean closed;

        private PoolStats(int maxSize, int idleConnections, int connectionsInUse,
                          int waitingThreads, long totalCreated, long totalBorrowed,
                          long totalReleased, long totalTimeouts, boolean closed) {
            this.maxSize = maxSize;
            this.idleConnections = idleConnections;
            this.connectionsInUse = connectionsInUse;
            this.waitingThreads = waitingThreads;
            this.totalCreated = totalCreated;
            this.totalBorrowed = totalBorrowed;
            this.totalReleased = totalReleased;
            this.totalTimeouts = totalTimeouts;
            this.closed = closed;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public int getIdleConnections() {
            return idleConnections;
        }

        public int getConnectionsInUse() {
            return connectionsInUse;
        }

        public int getWaitingThreads() {
            return waitingThreads;
        }

        public long getTotalCreated() {
            return totalCreated;
        }

        public long getTotalBorrowed() {
            return totalBorrowed;
        }

        public long getTotalReleased() {
            return totalReleased;
        }

        public long getTotalTimeouts() {
            return totalTimeouts;
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public String toString() {
            return "PoolStats{max=" + maxSize
                + ", idle=" + idleConnections
                + ", inUse=" + connectionsInUse
                + ", waiting=" + waitingThreads
                + ", created=" + totalCreated
                + ", borrowed=" + totalBorrowed
                + ", released=" + totalReleased
                + ", timeouts=" + totalTimeouts
                + ", closed=" + closed + '}';
        }
    }

    private final int maxSize;
    private final ConnectionFactory connectionFactory;
    private final long defaultBorrowTimeoutMillis;

    private final Deque<DatabaseConnection> idleConnections = new ArrayDeque<>();
    private final Set<DatabaseConnection> connectionsInUse =
        Collections.newSetFromMap(new IdentityHashMap<DatabaseConnection, Boolean>());

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition connectionAvailable = lock.newCondition();

    private volatile boolean closed;
    private int pendingCreations;
    private int waitingThreads;
    private long totalCreated;
    private long totalBorrowed;
    private long totalReleased;
    private long totalTimeouts;

    public ConnectionPool(int maxSize) {
        this(maxSize, DatabaseConnection::new, DEFAULT_BORROW_TIMEOUT_MILLIS);
    }

    public ConnectionPool(int maxSize, ConnectionFactory connectionFactory) {
        this(maxSize, connectionFactory, DEFAULT_BORROW_TIMEOUT_MILLIS);
    }

    public ConnectionPool(int maxSize, ConnectionFactory connectionFactory,
                          long defaultBorrowTimeoutMillis) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize debe ser mayor que 0");
        }
        if (connectionFactory == null) {
            throw new IllegalArgumentException("connectionFactory no puede ser null");
        }
        if (defaultBorrowTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                "defaultBorrowTimeoutMillis no puede ser negativo");
        }
        this.maxSize = maxSize;
        this.connectionFactory = connectionFactory;
        this.defaultBorrowTimeoutMillis = defaultBorrowTimeoutMillis;
    }

    /**
     * Envuelve una fabrica para limitar cuantas conexiones pueden abrirse al
     * mismo tiempo.
     *
     * Se usa en el modo SIN POOL: aunque cada peticion cree una conexion
     * nueva, el servidor de base de datos no acepta infinitas conexiones
     * simultaneas. Sin este limite, la simulacion abriria miles de conexiones
     * en paralelo y el modo sin pool pareceria mas rapido que el pool, lo cual
     * no ocurre en un servidor real.
     */
    public static ConnectionFactory limited(
            ConnectionFactory delegate, int maxConcurrentCreations) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate no puede ser null");
        }
        if (maxConcurrentCreations <= 0) {
            throw new IllegalArgumentException(
                "maxConcurrentCreations debe ser mayor que 0");
        }

        Semaphore permits = new Semaphore(maxConcurrentCreations, true);
        return () -> {
            boolean acquired = false;
            try {
                permits.acquire();
                acquired = true;
                return delegate.create();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(
                    "Interrumpido esperando cupo para abrir una conexion", e);
            } finally {
                if (acquired) {
                    permits.release();
                }
            }
        };
    }

    public DatabaseConnection borrow()
            throws SQLException, InterruptedException, PoolTimeoutException {
        return borrow(defaultBorrowTimeoutMillis);
    }

    /**
     * Obtiene una conexion del pool o espera hasta el timeout indicado.
     * El lock se libera mientras se crea una conexion nueva.
     */
    public DatabaseConnection borrow(long timeoutMillis)
            throws SQLException, InterruptedException, PoolTimeoutException {

        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis no puede ser negativo");
        }

        long deadlineNanos = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        lock.lockInterruptibly();
        try {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("El pool de conexiones esta cerrado");
                }

                DatabaseConnection idle = pollValidIdleConnection();
                if (idle != null) {
                    return registerBorrow(idle);
                }

                if (canCreateConnection()) {
                    pendingCreations++;
                    DatabaseConnection created = createReservedConnection();
                    if (closed) {
                        closeQuietly(created);
                        throw new IllegalStateException(
                            "El pool se cerro mientras se creaba una conexion");
                    }
                    totalCreated++;
                    return registerBorrow(created);
                }

                long waitNanos = deadlineNanos - System.nanoTime();
                if (waitNanos <= 0) {
                    totalTimeouts++;
                    throw new PoolTimeoutException(
                        "Timeout de " + timeoutMillis + " ms esperando una conexion. "
                        + snapshotUnsafe());
                }

                waitingThreads++;
                try {
                    connectionAvailable.awaitNanos(waitNanos);
                } finally {
                    waitingThreads--;
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Crea una conexion fuera del lock. El llamador ya reservo el slot
     * incrementando pendingCreations y debe tener el lock tomado.
     * Al volver, el lock vuelve a estar tomado por el hilo actual.
     */
    private DatabaseConnection createReservedConnection() throws SQLException {
        lock.unlock();

        DatabaseConnection created = null;
        SQLException failure = null;
        try {
            created = connectionFactory.create();
        } catch (SQLException e) {
            failure = e;
        } finally {
            lock.lock();
            pendingCreations--;
            connectionAvailable.signal();
        }

        if (failure != null) {
            throw failure;
        }
        if (created == null) {
            throw new SQLException("ConnectionFactory devolvio null");
        }
        if (!created.isValid()) {
            closeQuietly(created);
            throw new SQLException("ConnectionFactory devolvio una conexion cerrada");
        }
        return created;
    }

    private DatabaseConnection pollValidIdleConnection() {
        while (!idleConnections.isEmpty()) {
            DatabaseConnection connection = idleConnections.pollFirst();
            if (connection.isValid()) {
                return connection;
            }
            closeQuietly(connection);
            connectionAvailable.signal();
        }
        return null;
    }

    private boolean canCreateConnection() {
        return idleConnections.size()
            + connectionsInUse.size()
            + pendingCreations < maxSize;
    }

    private DatabaseConnection registerBorrow(DatabaseConnection connection) {
        connectionsInUse.add(connection);
        totalBorrowed++;
        return connection;
    }

    /**
     * Devuelve una conexion al pool. Es obligatorio llamarlo en un finally.
     * Una doble liberacion es un error de programacion y lanza excepcion.
     */
    public void release(DatabaseConnection connection) {
        if (connection == null) {
            return;
        }

        lock.lock();
        try {
            if (!connectionsInUse.remove(connection)) {
                throw new IllegalArgumentException(
                    "La conexion no pertenece a este pool o ya fue liberada");
            }

            totalReleased++;

            if (closed || !connection.isValid()) {
                closeQuietly(connection);
            } else {
                idleConnections.addLast(connection);
            }
            connectionAvailable.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Precrea conexiones hasta maxSize. Conviene llamarlo antes de arrancar
     * los hilos clientes para que la medicion no incluya el costo de apertura.
     */
    public void warmUp() throws SQLException, InterruptedException {
        while (true) {
            lock.lockInterruptibly();
            try {
                if (closed) {
                    throw new IllegalStateException("El pool esta cerrado");
                }

                int live = idleConnections.size()
                    + connectionsInUse.size()
                    + pendingCreations;
                if (live >= maxSize) {
                    return;
                }

                pendingCreations++;
                DatabaseConnection created = createReservedConnection();

                if (closed) {
                    closeQuietly(created);
                    throw new IllegalStateException(
                        "El pool se cerro durante el warm-up");
                }

                idleConnections.addLast(created);
                totalCreated++;
                connectionAvailable.signal();
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * Cierra el pool de forma idempotente. Las conexiones prestadas se cierran
     * cuando el cliente las devuelve. Los hilos esperando son despertados.
     */
    @Override
    public void close() {
        List<DatabaseConnection> idleToClose = new ArrayList<>();

        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            idleToClose.addAll(idleConnections);
            idleConnections.clear();
            connectionAvailable.signalAll();
        } finally {
            lock.unlock();
        }

        for (DatabaseConnection connection : idleToClose) {
            closeQuietly(connection);
        }
    }

    public PoolStats getStats() {
        lock.lock();
        try {
            return snapshotUnsafe();
        } finally {
            lock.unlock();
        }
    }

    private PoolStats snapshotUnsafe() {
        return new PoolStats(
            maxSize,
            idleConnections.size(),
            connectionsInUse.size(),
            waitingThreads,
            totalCreated,
            totalBorrowed,
            totalReleased,
            totalTimeouts,
            closed
        );
    }

    public int getMaxSize() {
        return maxSize;
    }

    public long getDefaultBorrowTimeoutMillis() {
        return defaultBorrowTimeoutMillis;
    }

    public boolean isClosed() {
        return closed;
    }

    private static void closeQuietly(DatabaseConnection connection) {
        try {
            connection.close();
        } catch (RuntimeException ignored) {
            // El cierre de una conexion simulada no deberia fallar.
        }
    }
}
