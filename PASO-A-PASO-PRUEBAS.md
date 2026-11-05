# Guía Paso a Paso: Cómo Probar el Simulador de Pool de Conexiones

Este documento explica de forma clara y directa cómo verificar y probar cada componente del proyecto desarrollado por los 3 integrantes.

---

## 📋 Requisitos Previos

- **Java 17** instalado (verificar con `java -version`).
- **Terminal:** PowerShell o CMD en Windows.
- **Ubicación:** Estar dentro de la carpeta del proyecto:
  ```powershell
  cd c:\Users\Usuario\Desktop\PoolComparacion\pool-conexiones
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
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 1
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
   - **Tamaño del pool:** `10` · **Timeout de espera:** `30000 ms`
   - **Clientes concurrentes:** `20` (1 petición por cliente)
   - **max_connections BD:** `100` · **Núcleos CPU BD:** `8`
   - **Fallo aleatorio query:** `0.0` (ruido opcional; afecta igual a ambos modos)
   - **Latencia de conexión:** `50 ms` · **Latencia de query:** `10 ms`
3. Presiona el botón verde **"Ejecutar simulación"**.
4. **Observa los resultados:**
   - **Fase 1 (Verde - CON POOL):** Las conexiones se crean al inicio (warm-up) y se reutilizan. "Conexiones abiertas" = 10, "Rechazos de la BD" = 0.
   - **Fase 2 (Rojo - SIN POOL):** Cada petición abre y cierra una conexión nueva (50 ms de CPU del servidor cada vez). "Conexiones abiertas" = 20 y el tiempo promedio es varias veces mayor.
   - **Prueba de sobrecarga:** sube **Clientes** a `200` o `1000`. CON POOL sigue en 100 % de éxito (los clientes esperan su turno); SIN POOL la BD llega a 100/100 conexiones y **rechaza** el resto, por eso cae la tasa de éxito.
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
Simulacion: 200 clientes x 1 peticion = 200 peticiones logicas
Pool: 10 | BD: max_connections=100, CPU=8, abrir=50 ms, query=10 ms | Reintentos maximos: 2

CON POOL -> tiempo total de pared: ~400 ms
SIN POOL -> tiempo total de pared: ~970 ms

===== CON POOL =====
Exitosas: 200 | Fallidas: 0 | Porcentaje de exito: 100,00%
Tiempo promedio (exitosas): ~190 ms
SimulatedDatabase{... pico=10, abiertasTotal=10, rechazadas=0}

===== SIN POOL =====
Exitosas: 100 | Fallidas: 100 | Porcentaje de exito: 50,00%
Tiempo promedio (exitosas): ~860 ms
SimulatedDatabase{... pico=100, abiertasTotal=100, rechazadas=300}

Mejora del tiempo promedio (exitosas) usando pool: ~77%
```
(Los números exactos varían un poco en cada ejecución.)

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

