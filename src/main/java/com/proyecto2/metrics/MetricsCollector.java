package com.proyecto2.metrics;

public class MetricsCollector {

    // Contadores de la simulacion
    private int totalRequests;
    private int successfulRequests;
    private int failedRequests;
    private int totalRetries;

    // Tiempo acumulado en nanosegundos
    private long totalResponseTime;
    // Tiempo acumulado solo de las peticiones exitosas
    private long successResponseTime;

    // Constructor
    public MetricsCollector() {
        reset();
    }

    // Registra el resultado de una peticion
    public synchronized void recordRequest(
            boolean success,
            long responseTimeNanos,
            int retries) {

        if (responseTimeNanos < 0 || retries < 0) {
            throw new IllegalArgumentException(
                "El tiempo y los reintentos no pueden ser negativos"
            );
        }

        totalRequests++;

        if (success) {
            successfulRequests++;
            successResponseTime += responseTimeNanos;
        } else {
            failedRequests++;
        }

        totalResponseTime += responseTimeNanos;
        totalRetries += retries;
    }

    // Total de peticiones
    public synchronized int getTotalRequests() {
        return totalRequests;
    }

    // Peticiones exitosas
    public synchronized int getSuccessfulRequests() {
        return successfulRequests;
    }

    // Peticiones fallidas
    public synchronized int getFailedRequests() {
        return failedRequests;
    }

    // Total de reintentos
    public synchronized int getTotalRetries() {
        return totalRetries;
    }

    // Porcentaje de exito
    public synchronized double getSuccessRate() {
        if (totalRequests == 0) {
            return 0;
        }

        return (successfulRequests * 100.0) / totalRequests;
    }

    // Porcentaje de fallos
    public synchronized double getFailureRate() {
        if (totalRequests == 0) {
            return 0;
        }

        return (failedRequests * 100.0) / totalRequests;
    }

    // Tiempo promedio de respuesta en milisegundos
    public synchronized double getAverageResponseTime() {
        if (totalRequests == 0) {
            return 0;
        }

        double totalMilliseconds =
                totalResponseTime / 1000000.0;

        return totalMilliseconds / totalRequests;
    }

    /**
     * Tiempo promedio de respuesta (ms) contando SOLO las peticiones exitosas.
     *
     * Es la metrica correcta para comparar rendimiento: una peticion que la
     * base de datos rechaza al instante "tarda" muy poco, y si se promedia
     * junto con las exitosas haria parecer mas rapido al modo que mas falla.
     */
    public synchronized double getAverageSuccessResponseTime() {
        if (successfulRequests == 0) {
            return 0;
        }
        return successResponseTime / 1000000.0 / successfulRequests;
    }

    /** Peticiones exitosas por segundo, dado el tiempo de pared de la fase. */
    public synchronized double getThroughput(long wallMillis) {
        if (wallMillis <= 0) {
            return 0;
        }
        return successfulRequests * 1000.0 / wallMillis;
    }

    // Genera un resumen con todas las metricas
    public synchronized String getSummary() {
        return "Peticiones totales: " + totalRequests
            + "\nExitosas: " + successfulRequests
            + "\nFallidas: " + failedRequests
            + String.format("%nPorcentaje de exito: %.2f%%", getSuccessRate())
            + String.format("%nPorcentaje de fallos: %.2f%%", getFailureRate())
            + "\nReintentos: " + totalRetries
            + String.format("%nTiempo promedio (exitosas): %.2f ms",
                getAverageSuccessResponseTime())
            + String.format("%nTiempo promedio (todas): %.2f ms",
                getAverageResponseTime());
    }

    // Reinicia las metricas
    public synchronized void reset() {
        totalRequests = 0;
        successfulRequests = 0;
        failedRequests = 0;
        totalRetries = 0;
        totalResponseTime = 0;
        successResponseTime = 0;
    }
}
