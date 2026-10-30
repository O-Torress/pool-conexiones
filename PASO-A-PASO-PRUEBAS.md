# Guía Paso a Paso: Cómo Probar el Simulador de Pool de Conexiones

Este documento explica de forma clara y directa cómo verificar y probar cada componente del proyecto desarrollado por los 3 integrantes.

---

## 📋 Requisitos Previos

- **Java 17** instalado (verificar con `java -version`).
- **Terminal:** PowerShell o CMD en Windows.
- **Ubicación:** Estar dentro de la carpeta del proyecto:
  ```powershell
  cd c:\Users\Usuario\Desktop\POOL\pool-conexiones
  ```
> **Nota:** No necesitas instalar Maven manualmente. El proyecto incluye el ejecutable `.\mvnw.cmd` (Maven Wrapper) que gestiona todo de forma automática.

---

## 🧪 Paso 1: Ejecutar los Tests Unitarios (Verificación de Lógica)

Este paso valida automáticamente que el pool de conexiones (`ConnectionPool`), el control de concurrencia (`ClientThread`), y el recolector de métricas (`MetricsCollector`) funcionen sin fallos ni condiciones de carrera (*race conditions*).

### Comando:
```powershell
.\mvnw.cmd test
```

### ¿Qué deberías ver?
```text
[INFO] Running com.proyecto2.AppTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.proyecto2.pool.ConnectionPoolTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] Results:
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

> **¿Por qué 1 test dice "Skipped"?**  
> El test `SakilaIntegrationTest` se omite automáticamente si no tienes PostgreSQL local encendido con la base de datos Sakila. Es el comportamiento esperado.

---

## 🖥️ Paso 2: Probar la Interfaz Gráfica (Tu Parte — Integrante 3)

La interfaz gráfica permite manipular todos los parámetros de la simulación, ejecutar la comparación visual y ver gráficos en tiempo real.

### Comando:
```powershell
.\mvnw.cmd javafx:run
```

### ¿Cómo interactuar con la aplicación?
1. Se abrirá una ventana titulada **"Simulador de Pool de Conexiones"**.
2. En el panel izquierdo de **Configuración**, deja los valores por defecto o personalízalos:
   - **Tamaño del pool:** `10`
   - **Clientes concurrentes:** `20`
   - **Peticiones por cliente:** `50` (total 1,000 consultas)
   - **Tasa de fallo:** `0.10` (10%)
   - **Latencia de conexión:** `50 ms`
   - **Latencia de query:** `10 ms`
3. Presiona el botón verde **"Ejecutar simulación"**.
4. **Observa los resultados:**
   - **Fase 1 (Verde - CON POOL):** Las conexiones se crean al inicio (warm-up) y se reutilizan entre clientes. El tiempo promedio por consulta rondará los **10 - 20 ms**.
   - **Fase 2 (Rojo - SIN POOL):** Cada petición abre y cierra una conexión nueva (pagando los 50 ms de latencia cada vez). El tiempo promedio subirá a **60 - 80 ms**.
   - **Gráfico de Barras:** Muestra la comparación visual directa entre ambos escenarios.
   - **Log de Actividad:** Muestra el porcentaje exacto de mejora obtenido al usar el pool (típicamente entre **60% y 80% de mejora**).
5. Puedes presionar **"Limpiar"** para reiniciar los tableros y probar con otros valores.

---

## ⚡ Paso 3: Probar la Simulación en Consola (Sin Interfaz)

Si quieres hacer una prueba rápida en terminal sin abrir ventanas:

### Comando:
```powershell
.\mvnw.cmd exec:java -Dexec.mainClass="com.proyecto2.client.ClientSimulationDemo"
```

### ¿Qué esperar en la salida?
```text
Simulacion: 20 clientes x 50 peticiones = 1000 peticiones logicas
Pool size: 10 | Fallo por query: 10.0% | Reintentos maximos: 2

CON POOL -> tiempo total de pared: 1120 ms
SIN POOL -> tiempo total de pared: 3540 ms

===== CON POOL =====
Peticiones totales: 1000
Exitosas: 894
Fallidas: 106
Porcentaje de exito: 89.4%
Tiempo promedio: ~15.2 ms

===== SIN POOL =====
Peticiones totales: 1000
Exitosas: 898
Fallidas: 102
Porcentaje de exito: 89.8%
Tiempo promedio: ~64.8 ms

Mejora del tiempo promedio usando pool: 76.54%
```

---

## 🗄️ Paso 4: Probar con PostgreSQL Real (Opcional)

Si en el laboratorio o tu máquina tienes PostgreSQL corriendo con la base Sakila:

1. Crea el archivo `src/main/resources/database.properties` (puedes basarte en `database.properties.example`):
   ```properties
   db.url=jdbc:postgresql://localhost:5432/sakila
   db.user=postgres
   db.password=tu_contraseña_aqui
   ```
2. Ejecuta la demo real:
   ```powershell
   .\mvnw.cmd exec:java -Dexec.mainClass="com.proyecto2.client.SakilaSimulationDemo"
   ```

---

## ❓ Preguntas Frecuentes y Diagnóstico

### ¿Por qué el Pool es mucho más rápido?
En una base de datos real (y en la simulación), abrir una conexión TCP con autenticación y negociación SSL toma tiempo (latencia de conexión). El Pool mantiene conexiones ya abiertas e inactivas (idle); cuando un hilo cliente la pide, la usa de inmediato y la devuelve a la cola sin destruirla.

### ¿Qué pasa si un cliente pide conexión y el pool está lleno?
El hilo espera ordenadamente (`ReentrantLock` con `Condition.awaitNanos`) hasta que otro cliente termine y libere su conexión. Si transcurre el tiempo límite de espera sin obtener una, se lanza `PoolTimeoutException`.

