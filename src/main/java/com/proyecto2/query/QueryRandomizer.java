package com.proyecto2.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Selecciona consultas aleatorias contra la base de datos Sakila.
 *
 * Las consultas tienen LIMIT para que el resultado sea pequeno y la medicion
 * refleje principalmente el costo de conexion/ejecucion, no el de transferir
 * toda la tabla.
 */
public class QueryRandomizer {

    private static final List<String> DEFAULT_QUERIES =
        Collections.unmodifiableList(Arrays.asList(
            "SELECT actor_id, first_name, last_name "
                + "FROM actor ORDER BY actor_id LIMIT 10",
            "SELECT film_id, title, release_year, rental_rate "
                + "FROM film ORDER BY film_id LIMIT 10",
            "SELECT customer_id, first_name, last_name, email "
                + "FROM customer ORDER BY customer_id LIMIT 10",
            "SELECT rental_id, rental_date, return_date "
                + "FROM rental ORDER BY rental_id DESC LIMIT 10",
            "SELECT payment_id, customer_id, amount, payment_date "
                + "FROM payment ORDER BY payment_id DESC LIMIT 10",
            "SELECT f.title, c.name AS category "
                + "FROM film f "
                + "JOIN film_category fc ON fc.film_id = f.film_id "
                + "JOIN category c ON c.category_id = fc.category_id "
                + "ORDER BY f.title LIMIT 10",
            "SELECT i.inventory_id, f.title, i.store_id "
                + "FROM inventory i "
                + "JOIN film f ON f.film_id = i.film_id "
                + "ORDER BY i.inventory_id LIMIT 10"
        ));

    private final List<String> queries;

    public QueryRandomizer() {
        this(DEFAULT_QUERIES);
    }

    /**
     * Permite usar un conjunto propio de consultas sin modificar esta clase.
     */
    public QueryRandomizer(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            throw new IllegalArgumentException(
                "La lista de queries no puede estar vacia");
        }

        List<String> copy = new ArrayList<>(queries.size());
        for (String query : queries) {
            if (query == null || query.trim().isEmpty()) {
                throw new IllegalArgumentException(
                    "Las queries no pueden ser null ni estar vacias");
            }
            copy.add(query);
        }
        this.queries = Collections.unmodifiableList(copy);
    }

    /**
     * Selecciona una consulta aleatoria.
     */
    public String getRandomQuery() {
        int index = ThreadLocalRandom.current().nextInt(queries.size());
        return queries.get(index);
    }

    /**
     * Devuelve todas las consultas disponibles.
     */
    public List<String> getQueries() {
        return queries;
    }

    /**
     * Devuelve la cantidad de consultas configuradas.
     */
    public int getQueryCount() {
        return queries.size();
    }
}
