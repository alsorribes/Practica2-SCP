/* ---------------------------------------------------------------
Práctica 2.
Código fuente : SimulationWorker.java
Grado Informática
39942072L Albert Sorribes Torrent.
--------------------------------------------------------------- */
package simulation.fishandsharks;

import simulation.fishandsharks.Ocean.Cell;
import simulation.fishandsharks.Ocean.Fish;
import simulation.fishandsharks.Ocean.Shark;

import java.util.HashMap;
import java.util.Map;

/**
 * Clase que representa un hilo de trabajo para la simulación concurrente Wa-Tor.
 * Cada SimulationWorker es responsable de simular un conjunto de filas del océano.
 *
 * Implementa el patrón de concurrencia donde múltiples hilos trabajan en paralelo
 * sobre diferentes secciones del mapa, sincronizándose para garantizar la coherencia.
 */
public class SimulationWorker extends Thread {
    // Identificador único del hilo
    private final int threadId;

    // Rango de filas que este hilo debe simular [startRow, endRow)
    private final int startRow;
    private final int endRow;

    // Referencia al modelo de simulación principal
    private final SharkFishModel model;

    // Gestor de sincronización compartido entre todos los hilos
    private final SynchronizationManager syncManager;

    // Flag para controlar el ciclo de vida del hilo
    private volatile boolean running = true;

    /**
     * Constructor del worker de simulación.
     *
     * @param id Identificador único del hilo
     * @param start Fila inicial (inclusiva) a simular
     * @param end Fila final (exclusiva) a simular
     * @param model Modelo de simulación compartido
     * @param sync Gestor de sincronización compartido
     */
    public SimulationWorker(int id, int start, int end,
                            SharkFishModel model,
                            SynchronizationManager sync) {
        super("Worker-" + id);
        this.threadId = id;
        this.startRow = start;
        this.endRow = end;
        this.model = model;
        this.syncManager = sync;
    }

    /**
     * Método principal del hilo que ejecuta el ciclo de simulación.
     *
     * Flujo de ejecución por generación:
     * 1. Espera a que se inicie la generación (sincronización con Lock+Condition)
     * 2. Simula filas sin dependencias (de la segunda en adelante)
     * 3. Espera a que el hilo anterior complete su fila frontera (Semaphore)
     * 4. Simula la primera fila (con dependencia)
     * 5. Notifica que su fila frontera está lista (Semaphore)
     * 6. Calcula estadísticas locales y las agrega al total (synchronized)
     * 7. Espera a que todos terminen (CyclicBarrier)
     */
    @Override
    public void run() {
        System.out.println("Hilo " + threadId + " iniciado - filas [" + startRow + ", " + endRow + ")");

        while (running) {
            try {
                // PASO 1: Esperar inicio de nueva generación
                // Usa Lock+Condition para que el hilo principal despierte a todos
                syncManager.waitForGenerationStart(threadId);

                if (!running) break;

                // PASO 2: Simular filas SIN dependencias (de la segunda en adelante)
                // Estas filas no dependen de la fila anterior del hilo previo,
                // por lo que se pueden ejecutar inmediatamente
                if (startRow + 1 < endRow) {
                    simulateRows(startRow + 1, endRow);
                }

                // PASO 3: Esperar dependencia de fila frontera del hilo anterior
                // Solo los hilos > 0 tienen dependencias
                // Usa Semaphore para esperar a que el hilo previo complete su primera fila
                if (threadId > 0) {
                    syncManager.waitForBorderRow(threadId - 1);
                }

                // PASO 4: Simular PRIMERA fila (tiene dependencia con fila anterior)
                // Esta fila depende de la última fila del hilo anterior (topología toroidal)
                if (startRow < endRow) {
                    simulateRows(startRow, startRow + 1);
                }

                // PASO 5: Notificar que mi fila frontera está completa
                // Usa Semaphore.release() para dar permiso al siguiente hilo
                syncManager.notifyBorderRowComplete(threadId);

                // PASO 6: Calcular estadísticas locales de mis filas
                // Cada hilo calcula sus propias estadísticas
                StatisticsData stats = calculateLocalStats();
                // Las agrega de forma thread-safe usando synchronized
                syncManager.addStatistics(stats);

                // PASO 7: Esperar a que todos los hilos terminen la generación
                // Usa CyclicBarrier para sincronizar el final de la generación
                syncManager.waitForGenerationEnd();

            } catch (InterruptedException e) {
                System.out.println("Hilo " + threadId + " interrumpido");
                break;
            }
        }

        System.out.println("Hilo " + threadId + " finalizado");
    }

    /**
     * Simula las reglas de Wa-Tor para un rango de filas.
     *
     * Recorre todas las celdas en el rango [start, end) y actualiza
     * cada entidad (pez o tiburón) según las reglas del modelo.
     *
     * @param start Fila inicial (inclusiva)
     * @param end Fila final (exclusiva)
     */
    private void simulateRows(int start, int end) {
        Ocean ocean = model.getOcean();
        int generation = model.getGeneration();
        int fishCycle = model.getFishCycle();
        int sharkCycle = model.getSharkCycle();

        // Recorrer todas las filas asignadas
        for (int y = start; y < end; y++) {
            for (int x = 0; x < ocean.getWidth(); x++) {
                Cell c = ocean.getField(x, y);
                // Solo actualizar celdas que pertenecen a esta generación
                // (evita procesar celdas recién creadas en esta misma generación)
                if (c != null && c.isPending(generation)) {
                    c.update(ocean, x, y, generation, fishCycle, sharkCycle);
                }
            }
        }
    }

    /**
     * Calcula las estadísticas locales de las filas asignadas a este hilo.
     *
     * Contabiliza:
     * - Número de peces
     * - Número de tiburones
     * - Celdas vacías
     * - Distribución de edad por especie
     *
     * @return Objeto StatisticsData con las estadísticas calculadas
     */
    private StatisticsData calculateLocalStats() {
        int fish = 0, sharks = 0, empty = 0;
        Map<Integer, int[]> ageMap = new HashMap<>();
        Ocean ocean = model.getOcean();

        // Recorrer solo las filas asignadas a este hilo
        for (int y = startRow; y < endRow; y++) {
            for (int x = 0; x < ocean.getWidth(); x++) {
                Cell c = ocean.getField(x, y);

                if (c == null) {
                    empty++;
                } else if (c instanceof Fish) {
                    fish++;
                    // Actualizar distribución de edad (índice 0 = peces)
                    updateAgeDistribution(ageMap, c.getAge(), 0);
                } else if (c instanceof Shark) {
                    sharks++;
                    // Actualizar distribución de edad (índice 1 = tiburones)
                    updateAgeDistribution(ageMap, c.getAge(), 1);
                }
            }
        }

        return new StatisticsData(fish, sharks, empty, ageMap);
    }

    /**
     * Actualiza el mapa de distribución de edad para una especie.
     *
     * @param ageMap Mapa de edad -> [peces, tiburones]
     * @param age Edad de la entidad
     * @param speciesIndex 0 para peces, 1 para tiburones
     */
    private void updateAgeDistribution(Map<Integer, int[]> ageMap, int age, int speciesIndex) {
        int[] counts = ageMap.get(age);
        if (counts == null) {
            counts = new int[2];
            ageMap.put(age, counts);
        }
        counts[speciesIndex]++;
    }

    /**
     * Detiene el hilo de forma segura.
     * Establece la flag de running a false e interrumpe el hilo.
     */
    public void stopWorker() {
        running = false;
        interrupt();
    }
}