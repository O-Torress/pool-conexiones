package com.proyecto2.database;

import com.proyecto2.pool.ConnectionPool;
import com.proyecto2.pool.DatabaseConnection;

import java.sql.SQLException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servidor de base de datos simulado, con los mismos limites que uno real
 * (por ejemplo PostgreSQL):
 *
 *  - max_connections: el servidor RECHAZA (no encola) las conexiones que
 *    superan el limite, con el error "too many clients already"
 *    (SQLState 53300). Una conexion ocupa su cupo hasta que se cierra.
 *  - CPU finita: el servidor tiene {@code cpuCores} nucleos. Tanto abrir una
 *    conexion (crear el proceso backend + autenticacion) como ejecutar una
 *    consulta ocupan un nucleo mientras duran. Si hay mas trabajo que
 *    nucleos, el trabajo espera: el servidor se satura.
 *
 * Gracias a estos dos limites la comparacion es honesta:
 *  - SIN pool, cada peticion paga la apertura (gasta CPU del servidor) y,
 *    con muchos clientes a la vez, se supera max_connections y hay rechazos.
 *  - CON pool, se abren solo {@code poolSize} conexiones al inicio y luego se
 *    reutilizan: el servidor solo gasta CPU en consultas y nunca se supera
 *    max_connections.
 *
 * Implementa ConnectionFactory, asi que lo usan igual el pool y el modo
 * sin pool. Crear una instancia nueva por fase para que las estadisticas
 * no se mezclen.
 */
public final class SimulatedDatabase implements ConnectionPool.ConnectionFactory {

    public static final String SQLSTATE_TOO_MANY_CONNECTIONS = "53300";

    private final int maxConnections;
    private final int cpuCores;
    private final long connectLatencyMillis;
    private final long queryLatencyMillis;
    private final double queryFailureRate;

    private final Semaphore cpu;
    private final AtomicInteger openConnections = new AtomicInteger();
    private final AtomicInteger peakOpenConnections = new AtomicInteger();
    private final AtomicLong totalOpened = new AtomicLong();
    private final AtomicLong totalRejected = new AtomicLong();

    public SimulatedDatabase(int maxConnections, int cpuCores,
                             long connectLatencyMillis, long queryLatencyMillis,
                             double queryFailureRate) {
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("maxConnections debe ser mayor que 0");
        }
        if (cpuCores <= 0) {
            throw new IllegalArgumentException("cpuCores debe ser mayor que 0");
        }
        if (connectLatencyMillis < 0 || queryLatencyMillis < 0) {
            throw new IllegalArgumentException("Las latencias no pueden ser negativas");
        }
        if (queryFailureRate < 0.0 || queryFailureRate > 1.0) {
            throw new IllegalArgumentException(
                "queryFailureRate debe estar entre 0.0 y 1.0");
        }
        this.maxConnections = maxConnections;
        this.cpuCores = cpuCores;
        this.connectLatencyMillis = connectLatencyMillis;
        this.queryLatencyMillis = queryLatencyMillis;
        this.queryFailureRate = queryFailureRate;
        this.cpu = new Semaphore(cpuCores, true);
    }

    /**
     * Abre una conexion nueva contra el servidor, igual que
     * DriverManager.getConnection(...).
     */
    @Override
    public DatabaseConnection create() throws SQLException {
        // 1. Control de admision: el servidor rechaza al superar max_connections.
        int open = openConnections.incrementAndGet();
        if (open > maxConnections) {
            openConnections.decrementAndGet();
            totalRejected.incrementAndGet();
            throw new SQLException(
                "FATAL: sorry, too many clients already (max_connections="
                    + maxConnections + ")",
                SQLSTATE_TOO_MANY_CONNECTIONS);
        }
        peakOpenConnections.accumulateAndGet(open, Math::max);

        // 2. Handshake: crear el backend y autenticar consume CPU del servidor.
        DatabaseConnection connection;
        try {
            execute(connectLatencyMillis);
            connection = new DatabaseConnection(
                queryFailureRate, queryLatencyMillis, this::execute);
        } catch (SQLException | RuntimeException e) {
            openConnections.decrementAndGet();
            throw e;
        }

        // 3. Al cerrar la conexion se libera su cupo en el servidor.
        connection.setOnClose(openConnections::decrementAndGet);
        totalOpened.incrementAndGet();
        return connection;
    }

    /** Ocupa un nucleo del servidor durante {@code workMillis}. */
    private void execute(long workMillis) throws SQLException {
        if (workMillis <= 0) {
            return;
        }
        boolean acquired = false;
        try {
            cpu.acquire();
            acquired = true;
            Thread.sleep(workMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Trabajo interrumpido en el servidor", e);
        } finally {
            if (acquired) {
                cpu.release();
            }
        }
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public int getCpuCores() {
        return cpuCores;
    }

    /** Conexiones abiertas en este momento. */
    public int getOpenConnections() {
        return openConnections.get();
    }

    /** Maximo de conexiones abiertas a la vez durante la fase. */
    public int getPeakOpenConnections() {
        return peakOpenConnections.get();
    }

    /** Conexiones abiertas con exito en total (cada una pago el handshake). */
    public long getTotalOpened() {
        return totalOpened.get();
    }

    /** Intentos de conexion rechazados por superar max_connections. */
    public long getTotalRejected() {
        return totalRejected.get();
    }

    @Override
    public String toString() {
        return "SimulatedDatabase{max_connections=" + maxConnections
            + ", cpu=" + cpuCores
            + ", abiertasAhora=" + getOpenConnections()
            + ", pico=" + getPeakOpenConnections()
            + ", abiertasTotal=" + getTotalOpened()
            + ", rechazadas=" + getTotalRejected() + '}';
    }
}
