package com.proyecto2.ui;

import com.proyecto2.client.ClientThread;
import com.proyecto2.database.SimulatedDatabase;
import com.proyecto2.metrics.MetricsCollector;
import com.proyecto2.pool.ConnectionPool;
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
import javafx.scene.control.ScrollPane;
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
    private Spinner<Integer> spnBorrowTimeout;
    private Spinner<Integer> spnDbMaxConnections;
    private Spinner<Integer> spnDbCores;

    // Controles de ejecucion
    private Button btnRun;
    private Button btnClear;
    private ProgressBar progressBar;
    private Label lblProgress;

    // Paneles de metricas
    private Label lblPoolTotal, lblPoolSuccess, lblPoolFailed;
    private Label lblPoolRetries, lblPoolAvgTime, lblPoolSuccessRate;
    private Label lblPoolWallTime;
    private Label lblPoolOpened, lblPoolPeak, lblPoolRejected;

    private Label lblNoPoolTotal, lblNoPoolSuccess, lblNoPoolFailed;
    private Label lblNoPoolRetries, lblNoPoolAvgTime, lblNoPoolSuccessRate;
    private Label lblNoPoolWallTime;
    private Label lblNoPoolOpened, lblNoPoolPeak, lblNoPoolRejected;

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
        ScrollPane configScroll = new ScrollPane(createConfigPanel());
        configScroll.setFitToWidth(true);
        configScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        root.setLeft(configScroll);

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

        spnPoolSize = intSpinner(1, 200, 10,
            "Conexiones que el pool mantiene abiertas y reutiliza");
        spnBorrowTimeout = intSpinner(100, 120_000, 30_000,
            "Tiempo maximo que un cliente espera una conexion libre "
            + "(HikariCP usa 30000 ms por defecto)");
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
        Label lblSimSection = new Label("Servidor de BD (simulado)");
        lblSimSection.setFont(Font.font("System", FontWeight.SEMI_BOLD, 13));
        lblSimSection.setTextFill(Color.web("#2980b9"));

        spnDbMaxConnections = intSpinner(1, 10_000, 100,
            "max_connections del servidor (PostgreSQL trae 100 por defecto). "
            + "Las conexiones por encima de este limite se RECHAZAN.");
        spnDbCores = intSpinner(1, 128, 8,
            "Nucleos de CPU del servidor: cuantas operaciones (abrir conexion "
            + "o ejecutar query) puede atender a la vez");
        spnFailureRate = doubleSpinner(0.0, 1.0, 0.0, 0.01,
            "Fallo aleatorio de cada query (ruido de red). Afecta igual a "
            + "ambos modos; dejar en 0 para ver solo el efecto del pool.");
        spnConnectLatency = intSpinner(0, 1000, 50,
            "Costo de abrir una conexion: crear el backend + autenticacion (ms)");
        spnQueryLatency = intSpinner(0, 500, 10, "Costo de ejecutar una query (ms)");

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
            labeledControl("Timeout de espera (ms):", spnBorrowTimeout),
            labeledControl("Clientes:", spnClients),
            labeledControl("Peticiones/cliente (fijo):", spnRequestsPerClient),
            new Separator(),
            lblRetrySection,
            labeledControl("Max reintentos:", spnMaxRetries),
            labeledControl("Backoff (ms):", spnRetryBackoff),
            new Separator(),
            lblSimSection,
            labeledControl("max_connections BD:", spnDbMaxConnections),
            labeledControl("Nucleos CPU BD:", spnDbCores),
            labeledControl("Fallo aleatorio query:", spnFailureRate),
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
        addMetricRow(poolGrid, 4, "Tiempo prom. (exitosas):", lblPoolAvgTime);
        addMetricRow(poolGrid, 5, "Tasa de exito:", lblPoolSuccessRate);
        addMetricRow(poolGrid, 6, "Tiempo total:", lblPoolWallTime);
        lblPoolOpened = metricValue("0");
        lblPoolPeak = metricValue("0");
        lblPoolRejected = metricValue("0");
        addMetricRow(poolGrid, 7, "Conexiones abiertas:", lblPoolOpened);
        addMetricRow(poolGrid, 8, "Pico simultaneo en BD:", lblPoolPeak);
        addMetricRow(poolGrid, 9, "Rechazos de la BD:", lblPoolRejected);
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
        addMetricRow(noPoolGrid, 4, "Tiempo prom. (exitosas):", lblNoPoolAvgTime);
        addMetricRow(noPoolGrid, 5, "Tasa de exito:", lblNoPoolSuccessRate);
        addMetricRow(noPoolGrid, 6, "Tiempo total:", lblNoPoolWallTime);
        lblNoPoolOpened = metricValue("0");
        lblNoPoolPeak = metricValue("0");
        lblNoPoolRejected = metricValue("0");
        addMetricRow(noPoolGrid, 7, "Conexiones abiertas:", lblNoPoolOpened);
        addMetricRow(noPoolGrid, 8, "Pico simultaneo en BD:", lblNoPoolPeak);
        addMetricRow(noPoolGrid, 9, "Rechazos de la BD:", lblNoPoolRejected);
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

        // Leer parametros desde los spinners. Las peticiones por cliente son
        // fijas en 1 por diseno de la practica.
        final int poolSize = spnPoolSize.getValue();
        final long borrowTimeout = spnBorrowTimeout.getValue();
        final int clientCount = spnClients.getValue();
        final int requestsPerClient = 1;
        final int maxRetries = spnMaxRetries.getValue();
        final long retryBackoff = spnRetryBackoff.getValue();
        final int dbMaxConnections = spnDbMaxConnections.getValue();
        final int dbCores = spnDbCores.getValue();
        final double failureRate = spnFailureRate.getValue();
        final long connectLatency = spnConnectLatency.getValue();
        final long queryLatency = spnQueryLatency.getValue();

        if (poolSize > dbMaxConnections) {
            appendLog(String.format(
                "[ERROR] El pool (%d) no puede ser mayor que max_connections de la BD (%d): "
                + "el servidor rechazaria las conexiones del propio pool.",
                poolSize, dbMaxConnections));
            return;
        }

        running.set(true);
        btnRun.setDisable(true);
        btnRun.setText("Ejecutando...");
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        lblProgress.setText("Preparando simulacion...");

        appendLog("==================================================");
        appendLog("  NUEVA SIMULACION");
        appendLog("==================================================");
        appendLog(String.format(
            "Parametros: %d clientes x %d peticion = %d peticiones totales",
            clientCount, requestsPerClient, clientCount * requestsPerClient));
        appendLog(String.format(
            "Pool: %d conexiones | Timeout de espera: %d ms | Reintentos: %d | Backoff: %d ms",
            poolSize, borrowTimeout, maxRetries, retryBackoff));
        appendLog(String.format(
            "Servidor BD: max_connections=%d | CPU=%d nucleos | abrir conexion=%d ms | query=%d ms",
            dbMaxConnections, dbCores, connectLatency, queryLatency));
        appendLog(String.format(
            "Fallo aleatorio de query: %.0f%% (igual en ambos modos)", failureRate * 100));
        if (clientCount > dbMaxConnections) {
            appendLog(String.format(
                "  Nota: %d clientes > max_connections (%d). SIN pool se esperan "
                + "rechazos de la BD; CON pool los clientes esperan en cola.",
                clientCount, dbMaxConnections));
        }
        appendLog("");

        QueryRandomizer randomizer = new QueryRandomizer();

        // Ejecutar en hilo de fondo para no bloquear la UI
        Thread simulationThread = new Thread(() -> {
            try {
                // FASE 1: Con Pool. Cada fase usa un servidor nuevo para que
                // las estadisticas de conexiones no se mezclen.
                Platform.runLater(() ->
                    lblProgress.setText("Ejecutando simulacion CON POOL..."));
                appendLog("[CON POOL] Iniciando...");

                SimulatedDatabase poolDb = new SimulatedDatabase(
                    dbMaxConnections, dbCores, connectLatency, queryLatency, failureRate);
                MetricsCollector poolMetrics = new MetricsCollector();
                PhaseResult poolResult = runSimulation(
                    true, poolSize, borrowTimeout, clientCount, requestsPerClient,
                    maxRetries, retryBackoff, poolDb, randomizer, poolMetrics);

                appendLog(String.format(
                    "[CON POOL] Completado en %d ms | %s",
                    poolResult.wallMillis, describeDb(poolDb)));
                appendLog(String.format(
                    "[CON POOL] Timeouts esperando conexion: %d", poolResult.poolTimeouts));
                Platform.runLater(() ->
                    updatePoolMetrics(poolMetrics, poolResult.wallMillis, poolDb));

                // FASE 2: Sin Pool
                Platform.runLater(() -> {
                    lblProgress.setText("Ejecutando simulacion SIN POOL...");
                    progressBar.setProgress(0.5);
                });
                appendLog("[SIN POOL] Iniciando...");

                SimulatedDatabase noPoolDb = new SimulatedDatabase(
                    dbMaxConnections, dbCores, connectLatency, queryLatency, failureRate);
                MetricsCollector noPoolMetrics = new MetricsCollector();
                PhaseResult noPoolResult = runSimulation(
                    false, poolSize, borrowTimeout, clientCount, requestsPerClient,
                    maxRetries, retryBackoff, noPoolDb, randomizer, noPoolMetrics);

                appendLog(String.format(
                    "[SIN POOL] Completado en %d ms | %s",
                    noPoolResult.wallMillis, describeDb(noPoolDb)));
                Platform.runLater(() ->
                    updateNoPoolMetrics(noPoolMetrics, noPoolResult.wallMillis, noPoolDb));

                // FASE 3: Comparacion
                long poolWallTime = poolResult.wallMillis;
                long noPoolWallTime = noPoolResult.wallMillis;
                double poolAvg = poolMetrics.getAverageSuccessResponseTime();
                double noPoolAvg = noPoolMetrics.getAverageSuccessResponseTime();
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
                    "  RESULTADO: mejora en tiempo promedio (exitosas) = %.2f%%",
                    avgImprovement));
                appendLog(String.format(
                    "  Throughput CON POOL: %.1f exitosas/s | SIN POOL: %.1f exitosas/s",
                    poolMetrics.getThroughput(poolWallTime),
                    noPoolMetrics.getThroughput(noPoolWallTime)));
                appendLog(String.format(
                    "  Tasa de exito CON POOL: %.2f%% | SIN POOL: %.2f%%",
                    poolMetrics.getSuccessRate(), noPoolMetrics.getSuccessRate()));
                appendLog(String.format(
                    "  Conexiones abiertas CON POOL: %d | SIN POOL: %d",
                    poolDb.getTotalOpened(), noPoolDb.getTotalOpened()));
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

    /** Resultado de una fase de la simulacion. */
    private static final class PhaseResult {
        final long wallMillis;
        final long poolTimeouts;

        PhaseResult(long wallMillis, long poolTimeouts) {
            this.wallMillis = wallMillis;
            this.poolTimeouts = poolTimeouts;
        }
    }

    private static String describeDb(SimulatedDatabase db) {
        return String.format(
            "BD: %d conexiones abiertas, pico simultaneo %d/%d, %d rechazadas",
            db.getTotalOpened(), db.getPeakOpenConnections(),
            db.getMaxConnections(), db.getTotalRejected());
    }

    /**
     * Ejecuta una fase con o sin pool contra el servidor simulado.
     *
     * CON POOL: se abren poolSize conexiones al inicio (warm-up) y los
     * clientes las piden prestadas y las devuelven. Si no hay libres esperan
     * en cola hasta borrowTimeout.
     *
     * SIN POOL: cada intento abre una conexion nueva directamente contra el
     * servidor y la cierra al terminar. No hay cola: si el servidor esta en
     * max_connections, la conexion se rechaza (como en PostgreSQL real).
     */
    private PhaseResult runSimulation(boolean withPool, int poolSize, long borrowTimeout,
                                      int clientCount, int requestsPerClient,
                                      int maxRetries, long retryBackoff,
                                      SimulatedDatabase database,
                                      QueryRandomizer randomizer,
                                      MetricsCollector metrics) {

        ConnectionPool pool = null;
        try {
            if (withPool) {
                pool = new ConnectionPool(poolSize, database, borrowTimeout);
                long warmStart = System.nanoTime();
                pool.warmUp();
                appendLog(String.format(
                    "[CON POOL] Warm-up: %d conexiones abiertas en %d ms "
                    + "(costo unico, fuera de la medicion)",
                    poolSize,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - warmStart)));
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
                        i, requestsPerClient, database, randomizer, metrics,
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
            long timeouts = pool != null ? pool.getStats().getTotalTimeouts() : 0;
            return new PhaseResult(wallMillis, timeouts);

        } catch (SQLException e) {
            appendLog("[ERROR] SQL: " + e.getMessage());
            return new PhaseResult(0, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            appendLog("[ERROR] Interrumpido: " + e.getMessage());
            return new PhaseResult(0, 0);
        } finally {
            if (pool != null) {
                pool.close();
            }
        }
    }

    // ==========================================================
    //  ACTUALIZACION DE UI
    // ==========================================================

    private void updatePoolMetrics(MetricsCollector m, long wallTime, SimulatedDatabase db) {
        lblPoolTotal.setText(String.valueOf(m.getTotalRequests()));
        lblPoolSuccess.setText(String.valueOf(m.getSuccessfulRequests()));
        lblPoolFailed.setText(String.valueOf(m.getFailedRequests()));
        lblPoolRetries.setText(String.valueOf(m.getTotalRetries()));
        lblPoolAvgTime.setText(String.format("%.2f ms", m.getAverageSuccessResponseTime()));
        lblPoolSuccessRate.setText(String.format("%.2f%%", m.getSuccessRate()));
        lblPoolWallTime.setText(wallTime + " ms");
        lblPoolOpened.setText(String.valueOf(db.getTotalOpened()));
        lblPoolPeak.setText(db.getPeakOpenConnections() + " / " + db.getMaxConnections());
        lblPoolRejected.setText(String.valueOf(db.getTotalRejected()));
    }

    private void updateNoPoolMetrics(MetricsCollector m, long wallTime, SimulatedDatabase db) {
        lblNoPoolTotal.setText(String.valueOf(m.getTotalRequests()));
        lblNoPoolSuccess.setText(String.valueOf(m.getSuccessfulRequests()));
        lblNoPoolFailed.setText(String.valueOf(m.getFailedRequests()));
        lblNoPoolRetries.setText(String.valueOf(m.getTotalRetries()));
        lblNoPoolAvgTime.setText(String.format("%.2f ms", m.getAverageSuccessResponseTime()));
        lblNoPoolSuccessRate.setText(String.format("%.2f%%", m.getSuccessRate()));
        lblNoPoolWallTime.setText(wallTime + " ms");
        lblNoPoolOpened.setText(String.valueOf(db.getTotalOpened()));
        lblNoPoolPeak.setText(db.getPeakOpenConnections() + " / " + db.getMaxConnections());
        lblNoPoolRejected.setText(String.valueOf(db.getTotalRejected()));
    }

    @SuppressWarnings("unchecked")
    private void updateChart(MetricsCollector poolM, MetricsCollector noPoolM,
                             long poolWall, long noPoolWall) {
        chart.getData().clear();

        XYChart.Series<String, Number> poolSeries = new XYChart.Series<>();
        poolSeries.setName("Con Pool");
        poolSeries.getData().add(new XYChart.Data<>(
            "Tiempo promedio exitosas (ms)", poolM.getAverageSuccessResponseTime()));
        poolSeries.getData().add(new XYChart.Data<>(
            "Tiempo de pared (ms)", poolWall));
        poolSeries.getData().add(new XYChart.Data<>(
            "Tasa de exito (%)", poolM.getSuccessRate()));
        poolSeries.getData().add(new XYChart.Data<>(
            "Reintentos", poolM.getTotalRetries()));

        XYChart.Series<String, Number> noPoolSeries = new XYChart.Series<>();
        noPoolSeries.setName("Sin Pool");
        noPoolSeries.getData().add(new XYChart.Data<>(
            "Tiempo promedio exitosas (ms)", noPoolM.getAverageSuccessResponseTime()));
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
        lblPoolOpened.setText("0");
        lblPoolPeak.setText("0");
        lblPoolRejected.setText("0");

        lblNoPoolTotal.setText("0");
        lblNoPoolSuccess.setText("0");
        lblNoPoolFailed.setText("0");
        lblNoPoolRetries.setText("0");
        lblNoPoolAvgTime.setText("0.00 ms");
        lblNoPoolSuccessRate.setText("0.00%");
        lblNoPoolWallTime.setText("0 ms");
        lblNoPoolOpened.setText("0");
        lblNoPoolPeak.setText("0");
        lblNoPoolRejected.setText("0");

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
