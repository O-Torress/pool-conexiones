package com.proyecto2.database;

import com.proyecto2.client.ClientThread;
import com.proyecto2.metrics.MetricsCollector;
import com.proyecto2.pool.ConnectionPool;
import com.proyecto2.pool.DatabaseConnection;
import com.proyecto2.query.QueryRandomizer;
import org.junit.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SimulatedDatabaseTest {

    @Test
    public void rejectsConnectionsAboveMaxConnections() throws Exception {
        SimulatedDatabase db = new SimulatedDatabase(2, 4, 0, 0, 0.0);
        DatabaseConnection a = db.create();
        DatabaseConnection b = db.create();
        try {
            db.create();
            fail("Se esperaba rechazo por max_connections");
        } catch (SQLException e) {
            assertEquals(SimulatedDatabase.SQLSTATE_TOO_MANY_CONNECTIONS, e.getSQLState());
        }
        assertEquals(1, db.getTotalRejected());
        assertEquals(2, db.getOpenConnections());

        // Al cerrar una conexion se libera su cupo en el servidor.
        a.close();
        assertEquals(1, db.getOpenConnections());
        DatabaseConnection c = db.create();
        assertEquals(2, db.getPeakOpenConnections());
        assertEquals(3, db.getTotalOpened());

        b.close();
        c.close();
        assertEquals(0, db.getOpenConnections());
    }

    @Test
    public void poolNeverExceedsServerLimitAndReusesConnections() throws Exception {
        int maxConnections = 20;
        int clients = 100;
        int poolSize = 5;
        SimulatedDatabase db = new SimulatedDatabase(maxConnections, 4, 5, 1, 0.0);
        MetricsCollector metrics = new MetricsCollector();

        ConnectionPool pool = new ConnectionPool(poolSize, db, 10_000);
        try {
            pool.warmUp();
            runClients(clients, i -> ClientThread.withPool(
                i, 1, pool, new QueryRandomizer(), metrics, 0, 0));
        } finally {
            pool.close();
        }

        assertEquals(clients, metrics.getSuccessfulRequests());
        assertEquals(0, metrics.getFailedRequests());
        assertEquals("El pool solo abre poolSize conexiones", poolSize, db.getTotalOpened());
        assertTrue(db.getPeakOpenConnections() <= poolSize);
        assertEquals(0, db.getTotalRejected());
        assertEquals(0, db.getOpenConnections());
    }

    @Test
    public void withoutPoolTheServerRejectsWhenClientsExceedMaxConnections() throws Exception {
        int maxConnections = 20;
        int clients = 100;
        SimulatedDatabase db = new SimulatedDatabase(maxConnections, 4, 5, 1, 0.0);
        MetricsCollector metrics = new MetricsCollector();

        runClients(clients, i -> ClientThread.withoutPool(
            i, 1, db, new QueryRandomizer(), metrics, 0, 0));

        assertEquals(clients, metrics.getTotalRequests());
        assertTrue("Sin pool deben existir rechazos: " + db, db.getTotalRejected() > 0);
        assertTrue("Sin pool deben existir fallos", metrics.getFailedRequests() > 0);
        assertEquals("Cada peticion exitosa abrio su propia conexion",
            metrics.getSuccessfulRequests(), db.getTotalOpened());
        assertTrue(db.getPeakOpenConnections() <= maxConnections);
        assertEquals(0, db.getOpenConnections());
    }

    private interface ClientFactory {
        ClientThread create(int id);
    }

    private static void runClients(int count, ClientFactory factory) throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            threads.add(new Thread(factory.create(i)));
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join(30_000);
        }
    }
}
