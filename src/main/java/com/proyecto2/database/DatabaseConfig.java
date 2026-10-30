package com.proyecto2.database;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuracion de conexion a PostgreSQL/Sakila.
 *
 * Prioridad:
 *  1. Propiedad del sistema: -Dsakila.db.url=..., -Dsakila.db.user=...,
 *     -Dsakila.db.password=...
 *  2. Variable de entorno: SAKILA_DB_URL, SAKILA_DB_USER, SAKILA_DB_PASSWORD
 *     (tambien se aceptan DB_URL, DB_USER y DB_PASSWORD).
 *  3. Archivo src/main/resources/database.properties.
 *  4. Valores por defecto: localhost:5432/sakila, usuario postgres.
 *
 * El archivo database.properties NO debe subirse al repositorio.
 */
public final class DatabaseConfig {

    public static final String DEFAULT_URL =
        "jdbc:postgresql://localhost:5432/sakila";
    public static final String DEFAULT_USER = "postgres";

    private final String url;
    private final String user;
    private final String password;

    private DatabaseConfig(String url, String user, String password) {
        this.url = requireNonBlank(url, "url");
        this.user = requireNonBlank(user, "user");
        this.password = password == null ? "" : password;
    }

    public static DatabaseConfig of(String url, String user, String password) {
        return new DatabaseConfig(url, user, password);
    }

    public static DatabaseConfig load() {
        Properties properties = loadPropertiesFile();

        String url = firstNonBlank(
            System.getProperty("sakila.db.url"),
            System.getenv("SAKILA_DB_URL"),
            System.getenv("DB_URL"),
            System.getenv("DATABASE_URL"),
            properties.getProperty("db.url"),
            DEFAULT_URL
        );

        String user = firstNonBlank(
            System.getProperty("sakila.db.user"),
            System.getenv("SAKILA_DB_USER"),
            System.getenv("DB_USER"),
            System.getenv("DATABASE_USER"),
            properties.getProperty("db.user"),
            DEFAULT_USER
        );

        String password = firstNonNull(
            System.getProperty("sakila.db.password"),
            System.getenv("SAKILA_DB_PASSWORD"),
            System.getenv("DB_PASSWORD"),
            System.getenv("DATABASE_PASSWORD"),
            System.getenv("PGPASSWORD"),
            properties.getProperty("db.password"),
            ""
        );

        return new DatabaseConfig(url, user, password);
    }

    public String getUrl() {
        return url;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    /**
     * Descripcion segura para logs: no incluye la contrasena.
     */
    public String getSafeDescription() {
        return url + " (usuario: " + user + ")";
    }

    @Override
    public String toString() {
        return getSafeDescription();
    }

    private static Properties loadPropertiesFile() {
        Properties properties = new Properties();
        try (InputStream input =
                 DatabaseConfig.class.getResourceAsStream("/database.properties")) {
            if (input != null) {
                properties.load(input);
            }
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException(
                "No se pudo leer src/main/resources/database.properties", e);
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " no puede estar vacio");
        }
        return value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return "";
    }
}
