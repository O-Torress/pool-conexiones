package com.proyecto2.metrics;

public class MetricsCollector {

    // Contadores de la simulacion
    private int totalRequests;
    private int successfulRequests;
    private int failedRequests;
    private int totalRetries;

    // Tiempo acumulado en nanosegundos
    private long totalResponseTime;

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

    // Genera un resumen con todas las metricas
    public synchronized String getSummary() {
        return "Peticiones totales: " + totalRequests
            + "\nExitosas: " + successfulRequests
            + "\nFallidas: " + failedRequests
            + "\nPorcentaje de exito: " + getSuccessRate() + "%"
            + "\nPorcentaje de fallos: " + getFailureRate() + "%"
            + "\nReintentos: " + totalRetries
            + "\nTiempo promedio: "
            + getAverageResponseTime() + " ms";
    }

    // Reinicia las metricas
    public synchronized void reset() {
        totalRequests = 0;
        successfulRequests = 0;
        failedRequests = 0;
        totalRetries = 0;
        totalResponseTime = 0;
    }
}
