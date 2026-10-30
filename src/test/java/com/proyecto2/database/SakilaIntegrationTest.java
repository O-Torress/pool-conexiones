package com.proyecto2.database;

import com.proyecto2.client.ClientThread;
import com.proyecto2.metrics.MetricsCollector;
import com.proyecto2.pool.ConnectionPool;
import com.proyecto2.pool.DatabaseConnection;
import com.proyecto2.query.QueryRandomizer;
import org.junit.Assume;
import org.junit.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Test de integracion real contra Sakila.
 *
 * Se salta automaticamente si no hay una configuracion valida o si PostgreSQL
 * no esta disponible. Para ejecutarlo, crear database.properties o definir
 * SAKILA_DB_URL, SAKILA_DB_USER y SAKILA_DB_PASSWORD.
 */
public class SakilaIntegrationTest {

    @Test
    public void poolRunsRealQueriesAgainstSakila() throws Exception {
        DatabaseConfig config;
        try {
            config = DatabaseConfig.load();
        } catch (RuntimeException e) {
            Assume.assumeNoException("Configuracion de BD invalida", e);
            return;
        }

        PostgresConnectionFactory factory = new PostgresConnectionFactory(config);

        try (DatabaseConnection connection = factory.create()) {
            assertTrue(connection.isValid());
        } catch (SQLException e) {
            Assume.assumeNoException("Sakila no disponible", e);
            return;
        }

        ConnectionPool pool = new ConnectionPool(2, factory, 5000);
        try {
            pool.warmUp();

            MetricsCollector metrics = new MetricsCollector();
            QueryRandomizer randomizer = new QueryRandomizer();
            int clients = 4;
            int requestsPerClient = 10;
            List<Thread> threads = new ArrayList<>();

            for (int i = 1; i <= clients; i++) {
                ClientThread client = ClientThread.withPool(
                    i, requestsPerClient, pool, randomizer, metrics, 1, 10
                );
                Thread thread = new Thread(client, "sakila-cliente-" + i);
                threads.add(thread);
                thread.start();
            }

            for (Thread thread : threads) {
                thread.join(30000);
                assertFalse(
                    "El cliente " + thread.getName() + " no termino",
                    thread.isAlive()
                );
            }

            assertEquals(clients * requestsPerClient, metrics.getTotalRequests());
            assertTrue(
                "Porcentaje de exito demasiado bajo: "
                    + metrics.getSuccessRate(),
                metrics.getSuccessRate() >= 95.0
            );
            assertTrue(metrics.getAverageResponseTime() > 0.0);
            assertEquals(0, pool.getStats().getConnectionsInUse());
        } finally {
            pool.close();
        }
    }
}
