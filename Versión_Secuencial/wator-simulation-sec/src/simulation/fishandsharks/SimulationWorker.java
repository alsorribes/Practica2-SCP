package simulation.fishandsharks;

import simulation.fishandsharks.Ocean.Cell;
import simulation.fishandsharks.Ocean.Fish;
import simulation.fishandsharks.Ocean.Shark;

import java.util.HashMap;
import java.util.Map;

public class SimulationWorker extends Thread {
    private final int threadId;
    private final int startRow;
    private final int endRow;
    private final SharkFishModel model;
    private final SynchronizationManager syncManager;
    private volatile boolean running = true;

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

    @Override
    public void run() {
        System.out.println("Hilo " + threadId + " iniciado - filas [" + startRow + ", " + endRow + ")");

        while (running) {
            try {
                // 1. Esperar inicio de nueva generación
                syncManager.waitForGenerationStart(threadId);

                if (!running) break;

                // 2. Simular filas SIN dependencias (de la 2ª en adelante)
                // Estas se pueden hacer inmediatamente
                if (startRow + 1 < endRow) {
                    simulateRows(startRow + 1, endRow);
                }

                // 3. Esperar dependencia de fila frontera del hilo anterior
                if (threadId > 0) {
                    syncManager.waitForBorderRow(threadId - 1);
                }

                // 4. Simular PRIMERA fila (tiene dependencia con fila anterior)
                if (startRow < endRow) {
                    simulateRows(startRow, startRow + 1);
                }

                // 5. Notificar que mi fila frontera está completa
                syncManager.notifyBorderRowComplete(threadId);

                // 6. Calcular estadísticas de mis filas
                StatisticsData stats = calculateLocalStats();
                syncManager.addStatistics(stats);

                // 7. Esperar a que todos terminen la generación
                syncManager.waitForGenerationEnd();

            } catch (InterruptedException e) {
                System.out.println("Hilo " + threadId + " interrumpido");
                break;
            }
        }

        System.out.println("Hilo " + threadId + " finalizado");
    }

    private void simulateRows(int start, int end) {
        Ocean ocean = model.getOcean();
        int generation = model.getGeneration();
        int fishCycle = model.getFishCycle();
        int sharkCycle = model.getSharkCycle();

        for (int y = start; y < end; y++) {
            for (int x = 0; x < ocean.getWidth(); x++) {
                Cell c = ocean.getField(x, y);
                if (c != null && c.isPending(generation)) {
                    c.update(ocean, x, y, generation, fishCycle, sharkCycle);
                }
            }
        }
    }

    private StatisticsData calculateLocalStats() {
        int fish = 0, sharks = 0, empty = 0;
        Map<Integer, int[]> ageMap = new HashMap<>();
        Ocean ocean = model.getOcean();

        for (int y = startRow; y < endRow; y++) {
            for (int x = 0; x < ocean.getWidth(); x++) {
                Cell c = ocean.getField(x, y);

                if (c == null) {
                    empty++;
                } else if (c instanceof Fish) {
                    fish++;
                    updateAgeDistribution(ageMap, c.getAge(), 0);
                } else if (c instanceof Shark) {
                    sharks++;
                    updateAgeDistribution(ageMap, c.getAge(), 1);
                }
            }
        }

        return new StatisticsData(fish, sharks, empty, ageMap);
    }

    private void updateAgeDistribution(Map<Integer, int[]> ageMap, int age, int speciesIndex) {
        int[] counts = ageMap.get(age);
        if (counts == null) {
            counts = new int[2];
            ageMap.put(age, counts);
        }
        counts[speciesIndex]++;
    }

    public void stopWorker() {
        running = false;
        interrupt();
    }
}