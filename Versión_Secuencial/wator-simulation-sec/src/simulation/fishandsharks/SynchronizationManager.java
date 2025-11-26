package simulation.fishandsharks;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class SynchronizationManager {
    private final int numThreads;

    // BARRIER: Sincronizar fin de generación
    private final CyclicBarrier generationBarrier;

    // SEMAPHORES: Gestionar dependencias de filas frontera
    private final Semaphore[] borderSemaphores;

    // LOCK + CONDITION: Esperar inicio de generación
    private final ReentrantLock startLock = new ReentrantLock();
    private final Condition startCondition = startLock.newCondition();
    private volatile boolean generationStarted = false;

    // SYNCHRONIZED: Estadísticas compartidas
    private int totalFish = 0;
    private int totalSharks = 0;
    private int totalEmpty = 0;
    private Map<Integer, int[]> globalAgeDistribution;

    // COUNTDOWN LATCH: Esperar estadísticas
    private CountDownLatch statsLatch;

    public SynchronizationManager(int numThreads) {
        this.numThreads = numThreads;

        // Barrera cíclica - se resetea automáticamente
        this.generationBarrier = new CyclicBarrier(numThreads, () -> {
            System.out.println("✓ Todos los hilos completaron la generación");
        });

        // Semáforos para dependencias (inicializados a 0 = bloqueados)
        this.borderSemaphores = new Semaphore[numThreads];
        for (int i = 0; i < numThreads; i++) {
            borderSemaphores[i] = new Semaphore(0);
        }

        this.globalAgeDistribution = new TreeMap<>();
        this.statsLatch = new CountDownLatch(numThreads);
    }

    /**
     * LOCK + CONDITION: Los hilos esperan aquí hasta que se inicie generación
     */
    public void waitForGenerationStart(int threadId) throws InterruptedException {
        startLock.lock();
        try {
            while (!generationStarted) {
                startCondition.await(); // Espera con CONDITION
            }
        } finally {
            startLock.unlock();
        }
    }

    /**
     * LOCK + CONDITION: El hilo principal inicia nueva generación
     */
    public void startNewGeneration() {
        startLock.lock();
        try {
            // Resetear estadísticas (con synchronized en el método)
            resetStatistics();

            // Resetear semáforos de fronteras
            for (Semaphore sem : borderSemaphores) {
                sem.drainPermits(); // Limpiar permisos anteriores
            }

            generationStarted = true;
            startCondition.signalAll(); // Despertar TODOS los hilos

            System.out.println(">>> Generación iniciada <<<");
        } finally {
            startLock.unlock();
        }
    }

    /**
     * SEMAPHORE: Esperar a que hilo anterior complete su fila frontera
     */
    public void waitForBorderRow(int previousThreadId) throws InterruptedException {
        borderSemaphores[previousThreadId].acquire(); // Bloquea hasta tener permiso
    }

    /**
     * SEMAPHORE: Notificar que mi fila frontera está completa
     */
    public void notifyBorderRowComplete(int threadId) {
        borderSemaphores[threadId].release(); // Da permiso al siguiente hilo
    }

    /**
     * SYNCHRONIZED: Agregar estadísticas de forma thread-safe
     */
    public void addStatistics(StatisticsData localStats) {
        synchronized (this) {
            totalFish += localStats.fish;
            totalSharks += localStats.sharks;
            totalEmpty += localStats.empty;

            // Merge age distribution
            mergeAgeDistributionInternal(localStats.ageDistribution);
        }

        statsLatch.countDown(); // Indicar que este hilo terminó sus stats
    }

    /**
     * SYNCHRONIZED: Merge interno de distribución de edad
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

            globalCounts[0] += localCounts[0]; // peces
            globalCounts[1] += localCounts[1]; // tiburones
        }
    }

    /**
     * SYNCHRONIZED: Resetear estadísticas
     */
    private synchronized void resetStatistics() {
        totalFish = 0;
        totalSharks = 0;
        totalEmpty = 0;
        globalAgeDistribution.clear();
        statsLatch = new CountDownLatch(numThreads);
    }

    /**
     * COUNTDOWN LATCH: Esperar a que todos calculen estadísticas
     */
    public void waitForStatistics() throws InterruptedException {
        statsLatch.await(); // Bloquea hasta que llegue a 0
    }

    /**
     * SYNCHRONIZED: Obtener estadísticas de forma segura
     */
    public synchronized int[] getStatistics() {
        return new int[]{totalFish, totalSharks, totalEmpty};
    }

    /**
     * SYNCHRONIZED: Obtener distribución de edad
     */
    public synchronized Map<Integer, int[]> getAgeDistribution() {
        return new TreeMap<>(globalAgeDistribution);
    }

    /**
     * BARRIER: Esperar a que todos terminen la generación
     */
    public void waitForGenerationEnd() throws InterruptedException {
        try {
            generationBarrier.await(); // Todos esperan aquí

            // Después de la barrera, marcar generación como no iniciada
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