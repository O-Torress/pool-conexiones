package com.proyecto2.database;

import com.proyecto2.pool.ConnectionPool;
import com.proyecto2.pool.DatabaseConnection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class PostgresConnectionFactory
        implements ConnectionPool.ConnectionFactory {

    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_SOCKET_TIMEOUT_SECONDS = 30;

    private final DatabaseConfig config;
    private final int connectTimeoutSeconds;
    private final int socketTimeoutSeconds;

    public PostgresConnectionFactory(DatabaseConfig config) {
        this(
            config,
            DEFAULT_CONNECT_TIMEOUT_SECONDS,
            DEFAULT_SOCKET_TIMEOUT_SECONDS
        );
    }

    public PostgresConnectionFactory(DatabaseConfig config,
                                     int connectTimeoutSeconds,
                                     int socketTimeoutSeconds) {
        if (config == null) {
            throw new IllegalArgumentException("config no puede ser null");
        }
        if (connectTimeoutSeconds <= 0 || socketTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("Los timeouts deben ser mayores que 0");
        }
        this.config = config;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.socketTimeoutSeconds = socketTimeoutSeconds;
    }

    public static PostgresConnectionFactory fromEnvironment() {
        return new PostgresConnectionFactory(DatabaseConfig.load());
    }

    @Override
    public DatabaseConnection create() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", config.getUser());
        properties.setProperty("password", config.getPassword());
        properties.setProperty(
            "connectTimeout", String.valueOf(connectTimeoutSeconds));
        properties.setProperty(
            "socketTimeout", String.valueOf(socketTimeoutSeconds));
        properties.setProperty("ApplicationName", "pool-conexiones-sakila");

        Connection jdbcConnection =
            DriverManager.getConnection(config.getUrl(), properties);

        try {
            jdbcConnection.setAutoCommit(true);
            return new DatabaseConnection(jdbcConnection);
        } catch (SQLException e) {
            try {
                jdbcConnection.close();
            } catch (SQLException ignored) {
                // Se propaga el error original.
            }
            throw e;
        }
    }

    public DatabaseConfig getConfig() {
        return config;
    }
}
