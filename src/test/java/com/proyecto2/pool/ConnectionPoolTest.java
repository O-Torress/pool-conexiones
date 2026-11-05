package com.proyecto2.pool;

import com.proyecto2.client.ClientThread;
import com.proyecto2.metrics.MetricsCollector;
import com.proyecto2.query.QueryRandomizer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConnectionPoolTest {

    private static final ConnectionPool.ConnectionFactory HEALTHY_FACTORY =
        () -> new DatabaseConnection(0.0, 0, 0);

    @Test
    public void reusesConnectionsAndNeverExceedsMaxSize() throws Exception {
        int maxSize = 3;
        ConnectionPool pool = new ConnectionPool(maxSize, HEALTHY_FACTORY, 2000);

        try {
            pool.warmUp();

            int threadCount = 20;
            int borrowsPerThread = 100;
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            Set<Integer> usedIds = ConcurrentHashMap.newKeySet();
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> workers = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < borrowsPerThread; j++) {
                            DatabaseConnection connection = pool.borrow();
                            try {
                                usedIds.add(connection.getId());
                                int now = active.incrementAndGet();
                                maxActive.accumulateAndGet(now, Math::max);
                                connection.executeQuery("SELECT 1");
                            } finally {
                                active.decrementAndGet();
                                pool.release(connection);
                            }
                        }
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    }
                });
                workers.add(worker);
                worker.start();
            }

            start.countDown();
            for (Thread worker : workers) {
                worker.join(5000);
            }

            assertNull(error.get());
            assertTrue(
                "maxActive=" + maxActive.get(),
                maxActive.get() <= maxSize
            );
            assertEquals(maxSize, pool.getStats().getTotalCreated());
            assertEquals(maxSize, pool.getStats().getIdleConnections());
            assertEquals(0, pool.getStats().getConnectionsInUse());
            assertTrue(
                "Se usaron mas conexiones de las creadas: " + usedIds.size(),
                usedIds.size() <= maxSize
            );
        } finally {
            pool.close();
        }
    }

    @Test
    public void borrowTimesOutWhenAllConnectionsAreInUse() throws Exception {
        ConnectionPool pool = new ConnectionPool(1, HEALTHY_FACTORY, 2000);

        try {
            DatabaseConnection connection = pool.borrow(100);
            try {
                pool.borrow(100);
                fail("Se esperaba PoolTimeoutException");
            } catch (ConnectionPool.PoolTimeoutException expected) {
                // comportamiento esperado
            } finally {
                pool.release(connection);
            }

            assertEquals(1, pool.getStats().getTotalTimeouts());
        } finally {
            pool.close();
        }
    }

    @Test
    public void releaseRejectsDoubleRelease() throws Exception {
        ConnectionPool pool = new ConnectionPool(1, HEALTHY_FACTORY);

        try {
            DatabaseConnection connection = pool.borrow();
            pool.release(connection);

            try {
                pool.release(connection);
                fail("Se esperaba IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                // comportamiento esperado
            }
        } finally {
            pool.close();
        }
    }

    @Test
    public void closeRejectsNewBorrowsAndClosesIdleConnections() throws Exception {
        ConnectionPool pool = new ConnectionPool(2, HEALTHY_FACTORY);
        pool.warmUp();
        pool.close();

        assertTrue(pool.isClosed());
        assertEquals(0, pool.getStats().getIdleConnections());

        try {
            pool.borrow();
            fail("Se esperaba IllegalStateException");
        } catch (IllegalStateException expected) {
            // comportamiento esperado
        }
    }

    @Test
    public void clientThreadsRecordAllRequests() throws Exception {
        ConnectionPool pool = new ConnectionPool(4, HEALTHY_FACTORY, 2000);
        MetricsCollector metrics = new MetricsCollector();
        QueryRandomizer randomizer = new QueryRandomizer();

        try {
            pool.warmUp();

            int clients = 8;
            int requestsPerClient = 25;
            List<Thread> threads = new ArrayList<>();

            for (int i = 1; i <= clients; i++) {
                ClientThread client = ClientThread.withPool(
                    i, requestsPerClient, pool, randomizer, metrics
                );
                Thread thread = new Thread(client, "cliente-" + i);
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join(5000);
            }

            assertEquals(clients * requestsPerClient, metrics.getTotalRequests());
            assertEquals(clients * requestsPerClient, metrics.getSuccessfulRequests());
            assertEquals(0, metrics.getFailedRequests());
            assertEquals(0, metrics.getTotalRetries());
            assertEquals(0, pool.getStats().getConnectionsInUse());
        } finally {
            pool.close();
        }
    }

    @Test
    public void countsRetriesWhenEveryQueryFails() throws Exception {
        ConnectionPool.ConnectionFactory failingFactory =
            () -> new DatabaseConnection(1.0, 0, 0);
        ConnectionPool pool = new ConnectionPool(1, failingFactory, 2000);
        MetricsCollector metrics = new MetricsCollector();
        QueryRandomizer randomizer = new QueryRandomizer();

        try {
            int requestCount = 10;
            ClientThread client = ClientThread.withPool(
                1, requestCount, pool, randomizer, metrics, 2, 0
            );
            Thread thread = new Thread(client, "cliente-fallido");
            thread.start();
            thread.join(5000);

            assertEquals(requestCount, metrics.getTotalRequests());
            assertEquals(requestCount, metrics.getFailedRequests());
            assertEquals(requestCount * 2, metrics.getTotalRetries());
        } finally {
            pool.close();
        }
    }

    @Test
    public void poolCanBeClosedMoreThanOnce() throws Exception {
        ConnectionPool pool = new ConnectionPool(1, HEALTHY_FACTORY);
        pool.warmUp();
        pool.close();
        pool.close();

        assertTrue(pool.isClosed());
        assertEquals(0, pool.getStats().getConnectionsInUse());
    }

    @Test
    public void borrowWithZeroTimeoutFailsWhenPoolIsExhausted() throws Exception {
        ConnectionPool pool = new ConnectionPool(1, HEALTHY_FACTORY);

        try {
            DatabaseConnection connection = pool.borrow();
            try {
                pool.borrow(0);
                fail("Se esperaba PoolTimeoutException");
            } catch (ConnectionPool.PoolTimeoutException expected) {
                // comportamiento esperado
            } finally {
                pool.release(connection);
            }
        } finally {
            pool.close();
        }
    }

    @Test
    public void statsAreConsistentAfterWarmUp() throws Exception {
        ConnectionPool pool = new ConnectionPool(3, HEALTHY_FACTORY);

        try {
            pool.warmUp();
            ConnectionPool.PoolStats stats = pool.getStats();

            assertEquals(3, stats.getMaxSize());
            assertEquals(3, stats.getIdleConnections());
            assertEquals(0, stats.getConnectionsInUse());
            assertEquals(3, stats.getTotalCreated());
            assertEquals(0, stats.getTotalBorrowed());
            assertEquals(0, stats.getTotalReleased());
            assertFalse(stats.isClosed());
        } finally {
            pool.close();
        }
    }
}
