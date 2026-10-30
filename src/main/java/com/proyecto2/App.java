package com.proyecto2;

/**
 * Punto de entrada principal del proyecto.
 *
 * Lanza la interfaz grafica (JavaFX) del simulador de pool de conexiones.
 * Se mantiene esta clase como main para compatibilidad con Maven,
 * delegando a com.proyecto2.ui.App.
 */
public class App {
    public static void main(String[] args) {
        com.proyecto2.ui.App.main(args);
    }
}
