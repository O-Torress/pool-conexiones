
package com.proyecto2.query;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class QueryRandomizer {

    // Consultas disponibles para la simulacion
    private final List<String> queries = Collections.unmodifiableList(
        Arrays.asList(
            "SELECT * FROM usuarios",
            "SELECT * FROM productos",
            "SELECT * FROM pedidos",
            "SELECT * FROM clientes",
            "SELECT * FROM ventas"
        )
    );

    // Selecciona una consulta aleatoria
    public String getRandomQuery() {

        int index = ThreadLocalRandom.current()
                                     .nextInt(queries.size());

        return queries.get(index);
    }

    // Devuelve todas las consultas disponibles
    public List<String> getQueries() {
        return queries;
    }

    // Devuelve la cantidad de consultas configuradas
    public int getQueryCount() {
        return queries.size();
    }
}
