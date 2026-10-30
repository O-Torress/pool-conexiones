package com.proyecto2.ui;

import com.proyecto2.client.ClientThread;
import com.proyecto2.metrics.MetricsCollector;
import com.proyecto2.pool.ConnectionPool;
import com.proyecto2.pool.DatabaseConnection;
import com.proyecto2.query.QueryRandomizer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interfaz grafica JavaFX para el simulador de Pool de Conexiones.
 *
 * Orquesta los componentes del proyecto:
 *  - ConnectionPool y DatabaseConnection (pool/)
 *  - ClientThread (client/)
 *  - QueryRandomizer (query/)
 *  - MetricsCollector (metrics/)
 *
 * Permite configurar parametros, ejecutar la simulacion en modo
 * "Con Pool" vs "Sin Pool", y visualizar metricas y comparaciones.
 */
public class App extends Application {

    // Controles de configuracion
    private Spinner<Integer> spnPoolSize;
    private Spinner<Integer> spnClients;
    private Spinner<Integer> spnRequestsPerClient;
    private Spinner<Integer> spnMaxRetries;
    private Spinner<Double> spnFailureRate;
    private Spinner<Integer> spnConnectLatency;
    private Spinner<Integer> spnQueryLatency;
    private Spinner<Integer> spnRetryBackoff;

    // Controles de ejecucion
    private Button btnRun;
    private Button btnClear;
    private ProgressBar progressBar;
    private Label lblProgress;

    // Paneles de metricas
    private Label lblPoolTotal, lblPoolSuccess, lblPoolFailed;
    private Label lblPoolRetries, lblPoolAvgTime, lblPoolSuccessRate;
    private Label lblPoolWallTime;

    private Label lblNoPoolTotal, lblNoPoolSuccess, lblNoPoolFailed;
    private Label lblNoPoolRetries, lblNoPoolAvgTime, lblNoPoolSuccessRate;
    private Label lblNoPoolWallTime;

    // Grafico comparativo
    private BarChart<String, Number> chart;

    // Log en vivo
    private TextArea txtLog;

    // Estado de la simulacion
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Simulador de Pool de Conexiones");

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #f5f5f5;");

        // Header
        root.setTop(createHeader());

        // Centro: Metricas + Grafico
        VBox center = new VBox(10);
        center.getChildren().addAll(createMetricsSection(), createChartSection());
        root.setCenter(center);

        // Izquierda: Configuracion
        root.setLeft(createConfigPanel());

        // Inferior: Log
        root.setBottom(createLogSection());

        Scene scene = new Scene(root, 1200, 850);
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(1000);
        primaryStage.setMinHeight(700);
        primaryStage.setOnCloseRequest(e -> Platform.exit());
        primaryStage.show();
    }

    // ==========================================================
    //  HEADER
    // ==========================================================

    private VBox createHeader() {
        VBox header = new VBox(5);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(10, 0, 15, 0));

        Label title = new Label("Simulador de Pool de Conexiones");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setTextFill(Color.web("#2c3e50"));

        Label subtitle = new Label(
            "Compara el rendimiento de usar un pool reutilizable vs abrir conexiones nuevas");
        subtitle.setFont(Font.font("System", 13));
        subtitle.setTextFill(Color.web("#7f8c8d"));

        header.getChildren().addAll(title, subtitle);
        return header;
    }

    // ==========================================================
    //  PANEL DE CONFIGURACION (izquierda)
    // ==========================================================

    private VBox createConfigPanel() {
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(10, 15, 10, 5));
        panel.setMinWidth(280);
        panel.setStyle("-fx-background-color: white; -fx-background-radius: 8; "
            + "-fx-border-color: #ddd; -fx-border-radius: 8;");

        Label header = sectionLabel("Configuracion");

        // Pool
        Label lblPoolSection = new Label("Pool");
        lblPoolSection.setFont(Font.font("System", FontWeight.SEMI_BOLD, 13));
        lblPoolSection.setTextFill(Color.web("#2980b9"));

        spnPoolSize = intSpinner(1, 50, 10, "Tamano del pool");
        spnClients = intSpinner(1, 10000, 20,
            "Numero de clientes concurrentes (hasta 10000)");
        spnRequestsPerClient = intSpinner(1, 1, 1,
            "Fijo en 1 peticion por cliente");
        spnRequestsPerClient.setDisable(true);

        // Reintentos
        Label lblRetrySection = new Label("Reintentos");
        lblRetrySection.setFont(Font.font("System", FontWeight.SEMI_BOLD, 13));
        lblRetrySection.setTextFill(Color.web("#2980b9"));

        spnMaxRetries = intSpinner(0, 10, 2, "Reintentos maximos");
        spnRetryBackoff = intSpinner(0, 500, 10, "Backoff (ms)");

        // Simulacion
        Label lblSimSection = new Label("Conexion simulada");
        lblSimSection.setFont(Font.font("System", FontWeight.SEMI_BOLD, 13));
        lblSimSection.setTextFill(Color.web("#2980b9"));

        spnFailureRate = doubleSpinner(0.0, 1.0, 0.10, 0.05, "Tasa de fallo");
        spnConnectLatency = intSpinner(0, 1000, 50, "Latencia conexion (ms)");
        spnQueryLatency = intSpinner(0, 500, 10, "Latencia query (ms)");

        // Botones
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        btnRun = new Button("Ejecutar simulacion");
        btnRun.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; "
            + "-fx-font-weight: bold; -fx-padding: 8 20; -fx-background-radius: 5;");
        btnRun.setMaxWidth(Double.MAX_VALUE);
        btnRun.setOnAction(e -> onRunSimulation());

        btnClear = new Button("Limpiar");
        btnClear.setStyle("-fx-background-color: #95a5a6; -fx-text-fill: white; "
            + "-fx-padding: 8 15; -fx-background-radius: 5;");
        btnClear.setOnAction(e -> onClear());

        buttons.getChildren().addAll(btnRun, btnClear);
        HBox.setHgrow(btnRun, Priority.ALWAYS);

        // Progreso
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        lblProgress = new Label("");
        lblProgress.setFont(Font.font("System", 11));
        lblProgress.setTextFill(Color.web("#7f8c8d"));

        panel.getChildren().addAll(
            header,
            new Separator(),
            lblPoolSection,
            labeledControl("Tamano del pool:", spnPoolSize),
            labeledControl("Clientes:", spnClients),
            labeledControl("Peticiones/cliente (fijo):", spnRequestsPerClient),
            new Separator(),
            lblRetrySection,
            labeledControl("Max reintentos:", spnMaxRetries),
            labeledControl("Backoff (ms):", spnRetryBackoff),
            new Separator(),
            lblSimSection,
            labeledControl("Tasa de fallo:", spnFailureRate),
            labeledControl("Lat. conexion (ms):", spnConnectLatency),
            labeledControl("Lat. query (ms):", spnQueryLatency),
            new Separator(),
            buttons,
            progressBar,
            lblProgress
        );

        return panel;
    }

    // ==========================================================
    //  SECCION DE METRICAS (centro superior)
    // ==========================================================

    private HBox createMetricsSection() {
        HBox section = new HBox(15);
        section.setPadding(new Insets(5, 10, 5, 10));

        // Panel CON POOL
        VBox poolPanel = createMetricPanel("CON POOL", "#27ae60");
        lblPoolTotal = metricValue("0");
        lblPoolSuccess = metricValue("0");
        lblPoolFailed = metricValue("0");
        lblPoolRetries = metricValue("0");
        lblPoolAvgTime = metricValue("0.00 ms");
        lblPoolSuccessRate = metricValue("0.00%");
        lblPoolWallTime = metricValue("0 ms");

        GridPane poolGrid = new GridPane();
        poolGrid.setHgap(10);
        poolGrid.setVgap(5);
        poolGrid.setPadding(new Insets(5));
        addMetricRow(poolGrid, 0, "Peticiones totales:", lblPoolTotal);
        addMetricRow(poolGrid, 1, "Exitosas:", lblPoolSuccess);
        addMetricRow(poolGrid, 2, "Fallidas:", lblPoolFailed);
        addMetricRow(poolGrid, 3, "Reintentos:", lblPoolRetries);
        addMetricRow(poolGrid, 4, "Tiempo promedio:", lblPoolAvgTime);
        addMetricRow(poolGrid, 5, "Tasa de exito:", lblPoolSuccessRate);
        addMetricRow(poolGrid, 6, "Tiempo total:", lblPoolWallTime);
        poolPanel.getChildren().add(poolGrid);

        // Panel SIN POOL
        VBox noPoolPanel = createMetricPanel("SIN POOL", "#e74c3c");
        lblNoPoolTotal = metricValue("0");
        lblNoPoolSuccess = metricValue("0");
        lblNoPoolFailed = metricValue("0");
        lblNoPoolRetries = metricValue("0");
        lblNoPoolAvgTime = metricValue("0.00 ms");
        lblNoPoolSuccessRate = metricValue("0.00%");
        lblNoPoolWallTime = metricValue("0 ms");

        GridPane noPoolGrid = new GridPane();
        noPoolGrid.setHgap(10);
        noPoolGrid.setVgap(5);
        noPoolGrid.setPadding(new Insets(5));
        addMetricRow(noPoolGrid, 0, "Peticiones totales:", lblNoPoolTotal);
        addMetricRow(noPoolGrid, 1, "Exitosas:", lblNoPoolSuccess);
        addMetricRow(noPoolGrid, 2, "Fallidas:", lblNoPoolFailed);
        addMetricRow(noPoolGrid, 3, "Reintentos:", lblNoPoolRetries);
        addMetricRow(noPoolGrid, 4, "Tiempo promedio:", lblNoPoolAvgTime);
        addMetricRow(noPoolGrid, 5, "Tasa de exito:", lblNoPoolSuccessRate);
        addMetricRow(noPoolGrid, 6, "Tiempo total:", lblNoPoolWallTime);
        noPoolPanel.getChildren().add(noPoolGrid);

        section.getChildren().addAll(poolPanel, noPoolPanel);
        HBox.setHgrow(poolPanel, Priority.ALWAYS);
        HBox.setHgrow(noPoolPanel, Priority.ALWAYS);

        return section;
    }

    // ==========================================================
    //  GRAFICO COMPARATIVO (centro inferior)
    // ==========================================================

    private VBox createChartSection() {
        VBox section = new VBox(5);
        section.setPadding(new Insets(5, 10, 5, 10));

        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("Metrica");

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Valor");

        chart = new BarChart<>(xAxis, yAxis);
        chart.setTitle("Comparacion: Con Pool vs Sin Pool");
        chart.setAnimated(true);
        chart.setPrefHeight(250);
        chart.setLegendVisible(true);

        section.getChildren().add(chart);
        VBox.setVgrow(chart, Priority.ALWAYS);

        return section;
    }

    // ==========================================================
    //  LOG EN VIVO (inferior)
    // ==========================================================

    private VBox createLogSection() {
        VBox section = new VBox(5);
        section.setPadding(new Insets(10, 10, 5, 10));

        Label header = sectionLabel("Log de actividad");

        txtLog = new TextArea();
        txtLog.setEditable(false);
        txtLog.setPrefRowCount(6);
        txtLog.setFont(Font.font("Consolas", 12));
        txtLog.setStyle("-fx-control-inner-background: #2c3e50; -fx-text-fill: #ecf0f1;");

        section.getChildren().addAll(header, txtLog);
        return section;
    }

    // ==========================================================
    //  LOGICA DE SIMULACION
    // ==========================================================

    private void onRunSimulation() {
        if (running.get()) {
            return;
        }
        running.set(true);
        btnRun.setDisable(true);
        btnRun.setText("Ejecutando...");
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        lblProgress.setText("Preparando simulacion...");

        // Leer parametros desde los spinners. Las peticiones por cliente son
        // fijas en 1 por diseno de la practica.
        final int poolSize = spnPoolSize.getValue();
        final int clientCount = spnClients.getValue();
        final int requestsPerClient = 1;
        final int maxRetries = spnMaxRetries.getValue();
        final long retryBackoff = spnRetryBackoff.getValue();
        final double failureRate = spnFailureRate.getValue();
        final long connectLatency = spnConnectLatency.getValue();
        final long queryLatency = spnQueryLatency.getValue();

        appendLog("==================================================");
        appendLog("  NUEVA SIMULACION");
        appendLog("==================================================");
        appendLog(String.format(
            "Parametros: %d clientes x %d peticion = %d peticiones totales",
            clientCount, requestsPerClient, clientCount * requestsPerClient));
        appendLog(String.format(
            "Pool: %d | Fallo por intento: %.0f%% | Reintentos: %d | Backoff: %d ms",
            poolSize, failureRate * 100, maxRetries, retryBackoff));
        appendLog(String.format(
            "Latencia conexion: %d ms | Latencia query: %d ms",
            connectLatency, queryLatency));

        // Explicacion del modelo de fallos: los reintentos ocultan fallos.
        double finalFailureRate = Math.pow(failureRate, maxRetries + 1);
        appendLog(String.format(
            "Modelo de fallos: %d intento(s) por peticion (1 + %d reintentos).",
            1 + maxRetries, maxRetries));
        appendLog(String.format(
            "  Fallo definitivo esperado: %.4f%% (~%.1f de %d peticiones).",
            finalFailureRate * 100.0,
            finalFailureRate * clientCount,
            clientCount));
        appendLog(String.format(
            "Sin pool: la base acepta como maximo %d conexiones simultaneas "
            + "(mismo limite que el pool).", poolSize));
        if (maxRetries == 0) {
            appendLog("  AVISO: sin reintentos, cada fallo de query es definitivo.");
        }
        if (failureRate == 0.0) {
            appendLog("  AVISO: tasa de fallo 0%; no se simularan fallos de query.");
        }
        appendLog("");

        ConnectionPool.ConnectionFactory factory = () ->
            new DatabaseConnection(failureRate, connectLatency, queryLatency);

        QueryRandomizer randomizer = new QueryRandomizer();

        // Ejecutar en hilo de fondo para no bloquear la UI
        Thread simulationThread = new Thread(() -> {
            try {
                // FASE 1: Con Pool
                Platform.runLater(() ->
                    lblProgress.setText("Ejecutando simulacion CON POOL..."));
                appendLog("[CON POOL] Iniciando...");

                MetricsCollector poolMetrics = new MetricsCollector();
                long poolWallTime = runSimulation(
                    true, poolSize, clientCount, requestsPerClient,
                    maxRetries, retryBackoff, queryLatency,
                    factory, randomizer, poolMetrics);

                appendLog(String.format(
                    "[CON POOL] Completado en %d ms", poolWallTime));
                Platform.runLater(() -> updatePoolMetrics(poolMetrics, poolWallTime));

                // FASE 2: Sin Pool
                Platform.runLater(() -> {
                    lblProgress.setText("Ejecutando simulacion SIN POOL...");
                    progressBar.setProgress(0.5);
                });
                appendLog("[SIN POOL] Iniciando...");

                MetricsCollector noPoolMetrics = new MetricsCollector();
                long noPoolWallTime = runSimulation(
                    false, poolSize, clientCount, requestsPerClient,
                    maxRetries, retryBackoff, queryLatency,
                    factory, randomizer, noPoolMetrics);

                appendLog(String.format(
                    "[SIN POOL] Completado en %d ms", noPoolWallTime));
                Platform.runLater(() ->
                    updateNoPoolMetrics(noPoolMetrics, noPoolWallTime));

                // FASE 3: Comparacion
                double poolAvg = poolMetrics.getAverageResponseTime();
                double noPoolAvg = noPoolMetrics.getAverageResponseTime();
                double wallImprovement = noPoolWallTime > 0
                    ? ((noPoolWallTime - (double) poolWallTime) / noPoolWallTime) * 100.0
                    : 0.0;
                double avgImprovement = noPoolAvg > 0
                    ? ((noPoolAvg - poolAvg) / noPoolAvg) * 100.0
                    : 0.0;
                long poolAttempts = poolMetrics.getTotalRequests()
                    + poolMetrics.getTotalRetries();
                long noPoolAttempts = noPoolMetrics.getTotalRequests()
                    + noPoolMetrics.getTotalRetries();

                appendLog("");
                appendLog("==================================================");
                appendLog(String.format(
                    "  RESULTADO: mejora en tiempo de pared = %.2f%%",
                    wallImprovement));
                appendLog(String.format(
                    "  RESULTADO: mejora en tiempo promedio = %.2f%%",
                    avgImprovement));
                appendLog(String.format(
                    "  Tiempo de pared CON POOL:  %d ms", poolWallTime));
                appendLog(String.format(
                    "  Tiempo de pared SIN POOL: %d ms", noPoolWallTime));
                appendLog(String.format(
                    "  Tiempo promedio CON POOL:  %.2f ms", poolAvg));
                appendLog(String.format(
                    "  Tiempo promedio SIN POOL: %.2f ms", noPoolAvg));
                appendLog(String.format(
                    "  Intentos CON POOL:  %d (%d reintentos, %d fallos definitivos)",
                    poolAttempts, poolMetrics.getTotalRetries(),
                    poolMetrics.getFailedRequests()));
                appendLog(String.format(
                    "  Intentos SIN POOL: %d (%d reintentos, %d fallos definitivos)",
                    noPoolAttempts, noPoolMetrics.getTotalRetries(),
                    noPoolMetrics.getFailedRequests()));
                appendLog("==================================================");

                Platform.runLater(() ->
                    updateChart(poolMetrics, noPoolMetrics,
                        poolWallTime, noPoolWallTime));

            } catch (Exception ex) {
                appendLog("[ERROR] " + ex.getMessage());
            } finally {
                Platform.runLater(() -> {
                    running.set(false);
                    btnRun.setDisable(false);
                    btnRun.setText("Ejecutar simulacion");
                    progressBar.setProgress(1.0);
                    lblProgress.setText("Simulacion completada.");
                });
            }
        }, "simulacion-thread");

        simulationThread.setDaemon(true);
        simulationThread.start();
    }

    /**
     * Ejecuta la simulacion con o sin pool y devuelve el tiempo de pared.
     */
    private long runSimulation(boolean withPool, int poolSize,
                               int clientCount, int requestsPerClient,
                               int maxRetries, long retryBackoff,
                               long queryLatencyMillis,
                               ConnectionPool.ConnectionFactory factory,
                               QueryRandomizer randomizer,
                               MetricsCollector metrics) {

        ConnectionPool pool = null;
        try {
            // Sin pool cada peticion abre una conexion nueva, pero el servidor
            // de base de datos no acepta infinitas conexiones simultaneas.
            // Se usa el tamano del pool como capacidad de la base para que la
            // comparacion sea realista y no "sin pool" parezca mas rapido.
            ConnectionPool.ConnectionFactory effectiveFactory = withPool
                ? factory
                : ConnectionPool.limited(factory, poolSize);

            if (withPool) {
                // Con miles de clientes, el timeout fijo de 5 s es demasiado
                // corto: los clientes que esperan su turno empiezan a fallar
                // aunque el pool este funcionando bien. Se calcula un timeout
                // acorde al peor caso de la corrida.
                long borrowTimeout = computePoolBorrowTimeout(
                    poolSize, clientCount, requestsPerClient, queryLatencyMillis);
                appendLog(String.format(
                    "[CON POOL] Timeout de espera: %d ms", borrowTimeout));
                pool = new ConnectionPool(poolSize, factory, borrowTimeout);
                pool.warmUp();
            }

            List<Thread> threads = new ArrayList<>();
            for (int i = 1; i <= clientCount; i++) {
                ClientThread client;
                if (withPool) {
                    client = ClientThread.withPool(
                        i, requestsPerClient, pool, randomizer, metrics,
                        maxRetries, retryBackoff);
                } else {
                    client = ClientThread.withoutPool(
                        i, requestsPerClient, effectiveFactory, randomizer, metrics,
                        maxRetries, retryBackoff);
                }
                threads.add(new Thread(client, "cliente-" + i));
            }

            long startNanos = System.nanoTime();
            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join();
            }
            long wallMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startNanos);

            int expectedRequests = clientCount * requestsPerClient;
            if (metrics.getTotalRequests() != expectedRequests) {
                appendLog(String.format(
                    "[AVISO] Peticiones registradas: %d de %d",
                    metrics.getTotalRequests(), expectedRequests));
            }
            return wallMillis;

        } catch (SQLException e) {
            appendLog("[ERROR] SQL: " + e.getMessage());
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            appendLog("[ERROR] Interrumpido: " + e.getMessage());
            return 0;
        } finally {
            if (pool != null) {
                pool.close();
            }
        }
    }

    /**
     * Calcula un timeout de espera del pool acorde al peor caso de la corrida.
     *
     * El valor por defecto (5 s) sirve para cargas pequenas, pero con miles de
     * clientes el ultimo de la cola puede esperar mas que eso aunque el pool
     * este funcionando correctamente. Se estima el tiempo total de la fase y
     * se deja un margen.
     */
    private static long computePoolBorrowTimeout(int poolSize, int clientCount,
                                                 int requestsPerClient,
                                                 long queryLatencyMillis) {
        long effectiveQueryMillis = Math.max(queryLatencyMillis, 1L);
        long estimatedWallMillis = (long) Math.ceil(
            (double) clientCount * requestsPerClient
            * effectiveQueryMillis / poolSize);
        long timeout = estimatedWallMillis * 2L + 5_000L;
        return Math.max(ConnectionPool.DEFAULT_BORROW_TIMEOUT_MILLIS,
            Math.min(timeout, 120_000L));
    }

    // ==========================================================
    //  ACTUALIZACION DE UI
    // ==========================================================

    private void updatePoolMetrics(MetricsCollector m, long wallTime) {
        lblPoolTotal.setText(String.valueOf(m.getTotalRequests()));
        lblPoolSuccess.setText(String.valueOf(m.getSuccessfulRequests()));
        lblPoolFailed.setText(String.valueOf(m.getFailedRequests()));
        lblPoolRetries.setText(String.valueOf(m.getTotalRetries()));
        lblPoolAvgTime.setText(String.format("%.2f ms", m.getAverageResponseTime()));
        lblPoolSuccessRate.setText(String.format("%.2f%%", m.getSuccessRate()));
        lblPoolWallTime.setText(wallTime + " ms");
    }

    private void updateNoPoolMetrics(MetricsCollector m, long wallTime) {
        lblNoPoolTotal.setText(String.valueOf(m.getTotalRequests()));
        lblNoPoolSuccess.setText(String.valueOf(m.getSuccessfulRequests()));
        lblNoPoolFailed.setText(String.valueOf(m.getFailedRequests()));
        lblNoPoolRetries.setText(String.valueOf(m.getTotalRetries()));
        lblNoPoolAvgTime.setText(String.format("%.2f ms", m.getAverageResponseTime()));
        lblNoPoolSuccessRate.setText(String.format("%.2f%%", m.getSuccessRate()));
        lblNoPoolWallTime.setText(wallTime + " ms");
    }

    @SuppressWarnings("unchecked")
    private void updateChart(MetricsCollector poolM, MetricsCollector noPoolM,
                             long poolWall, long noPoolWall) {
        chart.getData().clear();

        XYChart.Series<String, Number> poolSeries = new XYChart.Series<>();
        poolSeries.setName("Con Pool");
        poolSeries.getData().add(new XYChart.Data<>(
            "Tiempo promedio (ms)", poolM.getAverageResponseTime()));
        poolSeries.getData().add(new XYChart.Data<>(
            "Tiempo de pared (ms)", poolWall));
        poolSeries.getData().add(new XYChart.Data<>(
            "Tasa de exito (%)", poolM.getSuccessRate()));
        poolSeries.getData().add(new XYChart.Data<>(
            "Reintentos", poolM.getTotalRetries()));

        XYChart.Series<String, Number> noPoolSeries = new XYChart.Series<>();
        noPoolSeries.setName("Sin Pool");
        noPoolSeries.getData().add(new XYChart.Data<>(
            "Tiempo promedio (ms)", noPoolM.getAverageResponseTime()));
        noPoolSeries.getData().add(new XYChart.Data<>(
            "Tiempo de pared (ms)", noPoolWall));
        noPoolSeries.getData().add(new XYChart.Data<>(
            "Tasa de exito (%)", noPoolM.getSuccessRate()));
        noPoolSeries.getData().add(new XYChart.Data<>(
            "Reintentos", noPoolM.getTotalRetries()));

        chart.getData().addAll(poolSeries, noPoolSeries);
    }

    private void onClear() {
        lblPoolTotal.setText("0");
        lblPoolSuccess.setText("0");
        lblPoolFailed.setText("0");
        lblPoolRetries.setText("0");
        lblPoolAvgTime.setText("0.00 ms");
        lblPoolSuccessRate.setText("0.00%");
        lblPoolWallTime.setText("0 ms");

        lblNoPoolTotal.setText("0");
        lblNoPoolSuccess.setText("0");
        lblNoPoolFailed.setText("0");
        lblNoPoolRetries.setText("0");
        lblNoPoolAvgTime.setText("0.00 ms");
        lblNoPoolSuccessRate.setText("0.00%");
        lblNoPoolWallTime.setText("0 ms");

        chart.getData().clear();
        txtLog.clear();
        progressBar.setProgress(0);
        progressBar.setVisible(false);
        lblProgress.setText("");
    }

    // ==========================================================
    //  UTILIDADES DE UI
    // ==========================================================

    private void appendLog(String message) {
        Platform.runLater(() -> {
            txtLog.appendText(message + "\n");
            txtLog.setScrollTop(Double.MAX_VALUE);
        });
    }

    private Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setFont(Font.font("System", FontWeight.BOLD, 15));
        label.setTextFill(Color.web("#2c3e50"));
        return label;
    }

    private Label metricValue(String initial) {
        Label label = new Label(initial);
        label.setFont(Font.font("Consolas", FontWeight.BOLD, 14));
        label.setTextFill(Color.web("#2c3e50"));
        return label;
    }

    private VBox createMetricPanel(String title, String color) {
        VBox panel = new VBox(5);
        panel.setPadding(new Insets(10));
        panel.setStyle("-fx-background-color: white; -fx-background-radius: 8; "
            + "-fx-border-color: " + color + "; -fx-border-radius: 8; "
            + "-fx-border-width: 2;");

        Label header = new Label(title);
        header.setFont(Font.font("System", FontWeight.BOLD, 16));
        header.setTextFill(Color.web(color));
        header.setAlignment(Pos.CENTER);
        header.setMaxWidth(Double.MAX_VALUE);

        panel.getChildren().add(header);
        return panel;
    }

    private void addMetricRow(GridPane grid, int row, String label, Label value) {
        Label lbl = new Label(label);
        lbl.setFont(Font.font("System", 12));
        lbl.setTextFill(Color.web("#555"));
        grid.add(lbl, 0, row);
        grid.add(value, 1, row);
    }

    private VBox labeledControl(String text, Control control) {
        Label label = new Label(text);
        label.setFont(Font.font("System", 11));
        label.setTextFill(Color.web("#555"));
        control.setMaxWidth(Double.MAX_VALUE);
        return new VBox(2, label, control);
    }

    private Spinner<Integer> intSpinner(int min, int max, int initial,
                                         String tooltip) {
        Spinner<Integer> spinner = new Spinner<>(min, max, initial);
        spinner.setEditable(true);
        spinner.setTooltip(new Tooltip(tooltip));
        spinner.setPrefWidth(120);
        return spinner;
    }

    private Spinner<Double> doubleSpinner(double min, double max,
                                           double initial, double step,
                                           String tooltip) {
        Spinner<Double> spinner = new Spinner<>(min, max, initial, step);
        spinner.setEditable(true);
        spinner.setTooltip(new Tooltip(tooltip));
        spinner.setPrefWidth(120);
        return spinner;
    }

    // ==========================================================
    //  PUNTO DE ENTRADA
    // ==========================================================

    public static void main(String[] args) {
        launch(args);
    }
}
