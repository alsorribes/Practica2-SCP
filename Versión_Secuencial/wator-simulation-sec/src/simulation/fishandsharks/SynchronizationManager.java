/* ---------------------------------------------------------------
Práctica 2.
Código fuente : SynchronizationManager.java
Grado Informática
39942072L Albert Sorribes Torrent.
X9321862P Porosnicu, Valentin Alexandru
--------------------------------------------------------------- */
package simulation.fishandsharks;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Gestor centralizado de sincronización para la simulación concurrente Wa-Tor.
 *
 * Esta clase coordina todos los mecanismos de sincronización requeridos:
 * - ReentrantLock + Condition: Para inicio de generación
 * - Semaphore: Para dependencias de filas frontera
 * - CyclicBarrier: Para sincronizar fin de generación
 * - CountDownLatch: Para esperar cálculo de estadísticas
 * - synchronized: Para agregación de estadísticas
 *
 * Garantiza que:
 * 1. Todos los hilos comienzan simultáneamente cada generación
 * 2. Las dependencias entre filas se respetan (topología toroidal)
 * 3. Ningún hilo comienza la siguiente generación hasta que todos terminen
 * 4. Las estadísticas se agregan sin condiciones de carrera
 */
public class SynchronizationManager {
    private final int numThreads;

    // MECANISMO 1: CyclicBarrier
    // Sincroniza el fin de cada generación
    // Todos los hilos esperan aquí antes de comenzar la siguiente generación
    private final CyclicBarrier generationBarrier;

    // MECANISMO 2: Semaphore[]
    // Gestiona las dependencias de filas frontera
    // borderSemaphores[i] controla si el hilo i+1 puede procesar su primera fila
    private final Semaphore[] borderSemaphores;

    // MECANISMO 3: ReentrantLock + Condition
    // Controla el inicio de cada generación
    // El hilo principal adquiere el lock, cambia el estado, y despierta a todos
    private final ReentrantLock startLock = new ReentrantLock();
    private final Condition startCondition = startLock.newCondition();
    private volatile boolean generationStarted = false;

    // MECANISMO 4: synchronized
    // Protege las estadísticas compartidas contra condiciones de carrera
    private int totalFish = 0;
    private int totalSharks = 0;
    private int totalEmpty = 0;
    private Map<Integer, int[]> globalAgeDistribution;

    // MECANISMO 5: CountDownLatch
    // Asegura que el hilo principal espere a que todos calculen estadísticas
    private CountDownLatch statsLatch;

    /**
     * Constructor del gestor de sincronización.
     *
     * @param numThreads Número de hilos worker que participan en la simulación
     */
    public SynchronizationManager(int numThreads) {
        this.numThreads = numThreads;

        // Inicializar CyclicBarrier
        // La acción se ejecuta cuando todos los hilos llegan a la barrera
        this.generationBarrier = new CyclicBarrier(numThreads, () -> {
            System.out.println("Todos los hilos completaron la generación");
        });

        // Inicializar Semaphores para dependencias de filas
        // Inicializados a 0 = bloqueados (no hay permisos disponibles)
        this.borderSemaphores = new Semaphore[numThreads];
        for (int i = 0; i < numThreads; i++) {
            borderSemaphores[i] = new Semaphore(0);
        }

        this.globalAgeDistribution = new TreeMap<>();
        this.statsLatch = new CountDownLatch(numThreads);
    }

    /**
     * LOCK + CONDITION: Los hilos esperan aquí hasta que se inicie la generación.
     *
     * Los hilos worker llaman a este método y se bloquean en la condición
     * hasta que el hilo principal llame a startNewGeneration().
     *
     * @param threadId Identificador del hilo (para logging)
     * @throws InterruptedException Si el hilo es interrumpido mientras espera
     */
    public void waitForGenerationStart(int threadId) throws InterruptedException {
        startLock.lock();
        try {
            // Esperar mientras la generación no haya comenzado
            // await() libera el lock y espera a ser notificado
            while (!generationStarted) {
                startCondition.await();
            }
        } finally {
            startLock.unlock();
        }
    }

    /**
     * LOCK + CONDITION: El hilo principal inicia una nueva generación.
     *
     * Este método:
     * 1. Resetea las estadísticas
     * 2. Resetea los semáforos de fronteras
     * 3. Marca la generación como iniciada
     * 4. Despierta a TODOS los hilos worker
     */
    public void startNewGeneration() {
        startLock.lock();
        try {
            // Resetear estadísticas para la nueva generación
            resetStatistics();

            // Limpiar permisos de semáforos de la generación anterior
            for (Semaphore sem : borderSemaphores) {
                sem.drainPermits();
            }

            // Marcar generación como iniciada
            generationStarted = true;

            // Despertar a TODOS los hilos en espera
            // signalAll() notifica a todos los hilos esperando en la condición
            startCondition.signalAll();

            System.out.println(">>> Generación iniciada <<<");
        } finally {
            startLock.unlock();
        }
    }

    /**
     * SEMAPHORE: Espera a que el hilo anterior complete su fila frontera.
     *
     * Este método implementa la dependencia entre filas adyacentes.
     * El hilo N espera a que el hilo N-1 termine su primera fila antes
     * de procesar su propia primera fila.
     *
     * @param previousThreadId ID del hilo cuya fila frontera esperamos
     * @throws InterruptedException Si el hilo es interrumpido
     */
    public void waitForBorderRow(int previousThreadId) throws InterruptedException {
        // acquire() bloquea hasta que haya un permiso disponible
        // El permiso se libera cuando el hilo anterior llama a notifyBorderRowComplete()
        borderSemaphores[previousThreadId].acquire();
    }

    /**
     * SEMAPHORE: Notifica que la fila frontera de este hilo está completa.
     *
     * Libera un permiso en el semáforo, permitiendo que el siguiente hilo
     * procese su primera fila.
     *
     * @param threadId ID del hilo que completó su fila frontera
     */
    public void notifyBorderRowComplete(int threadId) {
        // release() añade un permiso al semáforo
        // Esto desbloquea al hilo que está esperando en waitForBorderRow()
        borderSemaphores[threadId].release();
    }

    /**
     * SYNCHRONIZED: Agrega estadísticas locales al total global de forma thread-safe.
     *
     * El bloque synchronized asegura que solo un hilo a la vez pueda modificar
     * las estadísticas globales, evitando condiciones de carrera.
     *
     * @param localStats Estadísticas calculadas por un hilo worker
     */
    public void addStatistics(StatisticsData localStats) {
        // synchronized(this) crea una sección crítica
        // Solo un hilo puede ejecutar este bloque a la vez
        synchronized (this) {
            totalFish += localStats.fish;
            totalSharks += localStats.sharks;
            totalEmpty += localStats.empty;

            // Combinar distribución de edad local con la global
            mergeAgeDistributionInternal(localStats.ageDistribution);
        }

        // countDown() decrementa el contador del latch
        // Cuando llega a 0, los hilos esperando en await() se desbloquean
        statsLatch.countDown();
    }

    /**
     * SYNCHRONIZED: Combina la distribución de edad local con la global.
     *
     * Método privado auxiliar que también está sincronizado para
     * garantizar consistencia de los datos.
     *
     * @param localAgeMap Distribución de edad local de un hilo
     */
    private synchronized void mergeAgeDistributionInternal(Map<Integer, int[]> localAgeMap) {
        for (Map.Entry<Integer, int[]> entry : localAgeMap.entrySet()) {
            int age = entry.getKey();
            int[] localCounts = entry.getValue();

            int[] globalCounts = globalAgeDistribution.get(age);
            if (globalCounts == null) {
                globalCounts = new int[2];
                globalAgeDistribution.put(age, globalCounts);
            }

            // globalCounts[0] = peces, globalCounts[1] = tiburones
            globalCounts[0] += localCounts[0];
            globalCounts[1] += localCounts[1];
        }
    }

    /**
     * SYNCHRONIZED: Resetea las estadísticas para una nueva generación.
     *
     * Método privado que prepara el gestor para una nueva generación.
     */
    private synchronized void resetStatistics() {
        totalFish = 0;
        totalSharks = 0;
        totalEmpty = 0;
        globalAgeDistribution.clear();
        // Crear nuevo latch para la nueva generación
        statsLatch = new CountDownLatch(numThreads);
    }

    /**
     * COUNTDOWN LATCH: Espera a que todos los hilos calculen sus estadísticas.
     *
     * El hilo principal llama a este método para bloquear hasta que
     * todos los hilos worker hayan llamado a addStatistics().
     *
     * @throws InterruptedException Si el hilo es interrumpido
     */
    public void waitForStatistics() throws InterruptedException {
        // await() bloquea hasta que el contador llegue a 0
        // Cada hilo decrementa el contador llamando a addStatistics()
        statsLatch.await();
    }

    /**
     * SYNCHRONIZED: Obtiene las estadísticas globales de forma segura.
     *
     * @return Array con [peces, tiburones, celdas_vacías]
     */
    public synchronized int[] getStatistics() {
        return new int[]{totalFish, totalSharks, totalEmpty};
    }

    /**
     * SYNCHRONIZED: Obtiene la distribución de edad de forma segura.
     *
     * @return Copia del mapa de distribución de edad
     */
    public synchronized Map<Integer, int[]> getAgeDistribution() {
        return new TreeMap<>(globalAgeDistribution);
    }

    /**
     * BARRIER: Espera a que todos los hilos terminen la generación actual.
     *
     * Todos los hilos worker llaman a este método al final de cada generación.
     * La barrera se resetea automáticamente (CyclicBarrier) para la siguiente.
     *
     * @throws InterruptedException Si el hilo es interrumpido
     */
    public void waitForGenerationEnd() throws InterruptedException {
        try {
            // await() bloquea hasta que TODOS los hilos llamen a este método
            // Cuando el último hilo llega, todos se liberan simultáneamente
            generationBarrier.await();

            // Después de que todos pasen la barrera, marcar generación como no iniciada
            // Esto prepara el sistema para la siguiente generación
            startLock.lock();
            try {
                generationStarted = false;
            } finally {
                startLock.unlock();
            }
        } catch (BrokenBarrierException e) {
            throw new InterruptedException("Barrera rota: " + e.getMessage());
        }
    }
}