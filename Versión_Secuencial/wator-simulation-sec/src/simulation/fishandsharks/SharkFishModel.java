/* ---------------------------------------------------------------
Práctica 2.
Código fuente : SharkFishModel.java
Grado Informática
39942072L Albert Sorribes Torrent.
X9321862P Porosnicu, Valentin Alexandru
--------------------------------------------------------------- */
package simulation.fishandsharks;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import javax.swing.JComponent;
import javax.swing.JOptionPane;

import simulation.fishandsharks.Ocean.Cell;
import simulation.fishandsharks.Ocean.Fish;
import simulation.fishandsharks.Ocean.Shark;

/**
 * Modelo principal de la simulación Wa-Tor.
 *
 * MODIFICACIONES PARA VERSIÓN CONCURRENTE:
 * 1. Añadidos campos para gestión de hilos:
 *    - workers: Array de hilos SimulationWorker
 *    - syncManager: Gestor de sincronización
 *    - NUM_THREADS: Número de hilos (configurable)
 *
 * 2. Método step() modificado:
 *    - Versión concurrente: stepConcurrent()
 *    - Versión secuencial: stepSequential() (conservada para comparación)
 *
 * 3. Control de extinción añadido:
 *    - simulationActive: Flag para detener simulación
 *    - checkExtinction(): Verifica si todas las especies se extinguieron
 *    - ExtinctionListener: Interfaz para notificar extinción a la GUI
 *
 * 4. Gestión del ciclo de vida de hilos:
 *    - initializeThreads(): Crea e inicia los hilos worker
 *    - shutdown(): Finaliza los hilos de forma segura
 */
public class SharkFishModel extends JComponent implements MouseListener {

	private static final long serialVersionUID = 1L;
	private static final int BOX_SIZE = 10;

	// Modelo del océano
	private Ocean ocean;

	// Estado de la simulación
	private int generation;
	private int fishCnt, sharkCnt, emptyCnt;
	private int fishRebornCycle, sharkRebornCycle;

	// Variables para rendering
	private int x0, y0;
	private Class<? extends Cell> newType;
	private Map<Integer, int[]> ageDistribution;

	// ===== CAMPOS NUEVOS PARA CONCURRENCIA =====

	// Array de hilos worker que ejecutan la simulación
	private SimulationWorker[] workers;

	// Gestor centralizado de sincronización
	private SynchronizationManager syncManager;

	// Número de hilos worker (configurable)
	private static final int NUM_THREADS = 4;

	// Flag para activar/desactivar modo concurrente
	private boolean concurrentMode = true;

	// ===== CAMPOS PARA CONTROL DE EXTINCIÓN =====

	// Flag que indica si la simulación sigue activa
	private boolean simulationActive = true;

	// Listener para notificar a la GUI cuando ocurre extinción
	private ExtinctionListener extinctionListener;

	/**
	 * Constructor del modelo.
	 * Inicializa el océano y los hilos worker si está en modo concurrente.
	 */
	public SharkFishModel(int width, int height) {
		ocean = new Ocean(width, height);
		generation = fishCnt = sharkCnt = 0;
		emptyCnt = width*height;
		setPreferredSize(new Dimension(BOX_SIZE*width, BOX_SIZE*height));
		setMinimumSize(getPreferredSize());
		setMaximumSize(getPreferredSize());
		addMouseListener(this);

		fishRebornCycle = 2;
		sharkRebornCycle = 3;

		ageDistribution = new TreeMap<Integer, int[]>();

		// Inicializar hilos worker si está en modo concurrente
		if (concurrentMode) {
			initializeThreads();
		}
	}

	/**
	 * Inicializa los hilos worker para simulación concurrente.
	 *
	 * Distribución de trabajo:
	 * - Divide el mapa en NUM_THREADS secciones de filas
	 * - Cada hilo recibe aproximadamente height/NUM_THREADS filas
	 * - El último hilo recibe las filas restantes (para manejar divisiones inexactas)
	 */
	private void initializeThreads() {
		System.out.println("=== Inicializando modo CONCURRENTE con " + NUM_THREADS + " hilos ===");

		// Crear gestor de sincronización compartido
		syncManager = new SynchronizationManager(NUM_THREADS);
		workers = new SimulationWorker[NUM_THREADS];

		// Calcular filas por hilo
		int rowsPerThread = ocean.getHeight() / NUM_THREADS;

		// Crear e iniciar cada hilo worker
		for (int i = 0; i < NUM_THREADS; i++) {
			int startRow = i * rowsPerThread;
			// El último hilo toma todas las filas restantes
			int endRow = (i == NUM_THREADS - 1) ?
					ocean.getHeight() : (i + 1) * rowsPerThread;

			workers[i] = new SimulationWorker(i, startRow, endRow, this, syncManager);
			workers[i].start();
		}
	}

	// Getters
	public int getGeneration() {
		return generation;
	}

	public int getFishCount() {
		return fishCnt;
	}

	public int getSharkCount() {
		return sharkCnt;
	}

	public int getEmptyCount() {
		return emptyCnt;
	}

	public void place(int x, int y, Cell c) {
		ocean.setField(x, y, c);
	}

	public Map<Integer, int[]> getAgeDistribution() {
		return ageDistribution;
	}

	public void notifyPlaceModeChanged(Class<? extends Cell> type) {
		this.newType = type;
	}

	public void notifyRecycleChanged(int fishCycle, int sharkCycle) {
		this.fishRebornCycle = fishCycle;
		this.sharkRebornCycle = sharkCycle;
	}

	/**
	 * Rellena el océano aleatoriamente con peces y tiburones.
	 * Reinicia el estado de la simulación.
	 */
	public void fillOceanRandomly(double fishes, double sharks) {
		Class<? extends Cell> b4 = newType;
		Random r = new Random();
		int w = ocean.getWidth(), h = ocean.getHeight();

		newType = Fish.class;
		for (int i = 0; i < Math.round(w * h * fishes); i++)
			ocean.setField(r.nextInt(w), r.nextInt(h), getNewCellInstance());

		newType = Shark.class;
		for (int i = 0; i < Math.round(w * h * sharks); i++)
			ocean.setField(r.nextInt(w), r.nextInt(h), getNewCellInstance());

		newType = b4;

		// Reiniciar estado de simulación
		simulationActive = true;
		generation = 0;

		repaint();
	}

	private Cell getNewCellInstance() {
		if (newType == null)
			return null;
		else
			try {
				Cell c = newType.getDeclaredConstructor().newInstance();
				c.setGeneration(generation);
				return c;
			} catch (Exception e1) {
				System.out.println("Failed to place new cell: " + e1);
			}
		return null;
	}

	/**
	 * Ejecuta un paso de simulación.
	 *
	 * MODIFICACIÓN PRINCIPAL:
	 * - Verifica si la simulación está activa (no extinta)
	 * - Delega a stepConcurrent() o stepSequential() según modo
	 * - Verifica extinción después de cada paso
	 */
	public void step() {
		// Verificar si la simulación está activa
		if (!simulationActive) {
			System.out.println("Simulación detenida: todas las especies extintas");
			return;
		}

		long start = System.nanoTime();

		// Ejecutar versión concurrente o secuencial
		if (concurrentMode && workers != null) {
			stepConcurrent();
		} else {
			stepSequential();
		}

		generation++;

		long elapsed = System.nanoTime() - start;
		System.out.println("Step " + generation + ": " + (elapsed*1e-9) + " s (" +
				(concurrentMode ? "CONCURRENTE" : "SECUENCIAL") + ")");

		// Verificar si ocurrió extinción
		checkExtinction();

		repaint();
	}

	/**
	 * Versión CONCURRENTE del paso de simulación.
	 *
	 * Flujo:
	 * 1. Inicia nueva generación (despierta a todos los hilos)
	 * 2. Espera a que todos calculen estadísticas
	 * 3. Obtiene estadísticas globales agregadas
	 */
	private void stepConcurrent() {
		try {
			// Iniciar nueva generación (Lock+Condition)
			syncManager.startNewGeneration();

			// Esperar a que todos calculen estadísticas (CountDownLatch)
			syncManager.waitForStatistics();

			// Obtener estadísticas globales (synchronized)
			int[] stats = syncManager.getStatistics();
			fishCnt = stats[0];
			sharkCnt = stats[1];
			emptyCnt = stats[2];
			ageDistribution = syncManager.getAgeDistribution();

		} catch (InterruptedException e) {
			System.err.println("Error en simulación concurrente: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Versión SECUENCIAL del paso de simulación.
	 * Conservada para comparación y testing.
	 */
	private void stepSequential() {
		// Actualizar todas las celdas
		for (int y = 0; y < ocean.getHeight(); y++) {
			for (int x = 0; x < ocean.getWidth(); x++) {
				Cell c = ocean.getField(x, y);
				if (c != null && c.isPending(generation))
					c.update(ocean, x, y, generation,
							fishRebornCycle, sharkRebornCycle);
			}
		}

		// Calcular estadísticas
		emptyCnt = fishCnt = sharkCnt = 0;
		ageDistribution.clear();

		for (int y = 0; y < ocean.getHeight(); y++) {
			for (int x = 0; x < ocean.getWidth(); x++) {
				Cell c = ocean.getField(x, y);

				if (c == null)
					emptyCnt++;
				else if (c instanceof Fish)
					fishCnt++;
				else if (c instanceof Shark)
					sharkCnt++;

				if (c != null) {
					int[] spAge = ageDistribution.get(c.getAge());

					if (spAge == null)
						spAge = new int[2];

					if (c instanceof Fish)
						spAge[0]++;
					else
						spAge[1]++;

					ageDistribution.put(c.getAge(), spAge);
				}
			}
		}
	}

	/**
	 * Verifica si todas las especies se han extinguido y detiene la simulación.
	 *
	 * Casos:
	 * - Peces y tiburones = 0: Extinción total, detener simulación
	 * - Solo peces = 0: Avisar, simulación continúa (tiburones morirán)
	 * - Solo tiburones = 0: Avisar, simulación continúa (peces prosperarán)
	 */
	private void checkExtinction() {
		if (fishCnt == 0 && sharkCnt == 0) {
			simulationActive = false;

			System.out.println("\n" + "=".repeat(60));
			System.out.println("EXTINCION TOTAL - Generación " + generation);
			System.out.println("=".repeat(60));
			System.out.println("Estadísticas finales:");
			System.out.println("   - Peces: " + fishCnt);
			System.out.println("   - Tiburones: " + sharkCnt);
			System.out.println("   - Celdas vacías: " + emptyCnt);
			System.out.println("   - Generaciones totales: " + generation);
			System.out.println("=".repeat(60) + "\n");

			// Notificar a la GUI
			if (extinctionListener != null) {
				extinctionListener.onExtinction(generation);
			}
		} else if (fishCnt == 0) {
			System.out.println("Peces extintos en generación " + generation +
					" (quedan " + sharkCnt + " tiburones)");
		} else if (sharkCnt == 0) {
			System.out.println("Tiburones extintos en generación " + generation +
					" (quedan " + fishCnt + " peces)");
		}
	}

	/**
	 * Interfaz para notificar eventos de extinción a la GUI.
	 */
	public interface ExtinctionListener {
		void onExtinction(int finalGeneration);
	}

	public void setExtinctionListener(ExtinctionListener listener) {
		this.extinctionListener = listener;
	}

	public boolean isSimulationActive() {
		return simulationActive;
	}

	// Métodos de acceso para los workers
	public Ocean getOcean() {
		return ocean;
	}

	public int getFishCycle() {
		return fishRebornCycle;
	}

	public int getSharkCycle() {
		return sharkRebornCycle;
	}

	/**
	 * Finaliza todos los hilos worker de forma segura.
	 * Se debe llamar al cerrar la aplicación.
	 */
	public void shutdown() {
		if (workers != null) {
			System.out.println("Finalizando hilos...");
			simulationActive = false;
			for (SimulationWorker worker : workers) {
				worker.stopWorker();
			}
			try {
				for (SimulationWorker worker : workers) {
					worker.join(1000);
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public String toString() {
		return ocean.toString();
	}

	@Override
	public void paint(Graphics g) {
		x0 = (getWidth()-ocean.getWidth()*BOX_SIZE)/2;
		y0 = (getHeight()-ocean.getHeight()*BOX_SIZE)/2;
		x0 = x0 < 0 ? 0 : x0;
		y0 = y0 < 0 ? 0 : y0;

		g.setColor(Cell.OCEAN_DARK);
		g.fillRect(0, 0, getWidth(), getHeight());

		for (int y = 0; y < ocean.getHeight(); y++) {
			for (int x = 0; x < ocean.getWidth(); x++) {
				Cell c = ocean.getField(x, y);

				if (c == null) {
					g.setColor(Cell.OCEAN_LIGHT);
					g.fillRect(x0+x*BOX_SIZE+2, y0+y*BOX_SIZE+2,
							BOX_SIZE-4, BOX_SIZE-4);
				} else {
					g.setColor(c.getColor());

					if (c instanceof Shark && ((Shark) c).lifeIndex > 1)
						g.fillRect(x0+x*BOX_SIZE+1, y0+y*BOX_SIZE+1,
								BOX_SIZE-2, BOX_SIZE-2);
					else
						g.fillRect(x0+x*BOX_SIZE+2, y0+y*BOX_SIZE+2,
								BOX_SIZE-4, BOX_SIZE-4);
				}
			}
		}
	}

	@Override
	public void mouseClicked(MouseEvent e) {
		if (!isEnabled())
			return;

		int x = (e.getX()-x0)/BOX_SIZE, y = (e.getY()-y0)/BOX_SIZE;
		if (x < 0 || y < 0 || x >= ocean.getWidth() || y >= ocean.getHeight())
			return;

		place(x, y, getNewCellInstance());
		repaint();
	}

	@Override
	public void mousePressed(MouseEvent e) { }

	@Override
	public void mouseReleased(MouseEvent e) { }

	@Override
	public void mouseEntered(MouseEvent e) { }

	@Override
	public void mouseExited(MouseEvent e) { }
}