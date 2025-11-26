package simulation.fishandsharks;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import javax.swing.JComponent;

import simulation.fishandsharks.Ocean.Cell;
import simulation.fishandsharks.Ocean.Fish;
import simulation.fishandsharks.Ocean.Shark;

public class SharkFishModel extends JComponent implements MouseListener {

	private static final long serialVersionUID = 1L;

	private static final int BOX_SIZE = 10;

	private Ocean ocean;
	private int generation;
	private int fishCnt, sharkCnt, emptyCnt;
	private int fishRebornCycle, sharkRebornCycle;
	private int x0, y0;
	private Class<? extends Cell> newType;
	private Map<Integer, int[]> ageDistribution;

	// ===== NUEVOS CAMPOS PARA CONCURRENCIA =====
	private SimulationWorker[] workers;
	private SynchronizationManager syncManager;
	private static final int NUM_THREADS = 4; // Configurable
	private boolean concurrentMode = true; // true = concurrente, false = secuencial

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

		// Inicializar hilos
		if (concurrentMode) {
			initializeThreads();
		}
	}

	// ===== INICIALIZACIÓN DE HILOS =====
	private void initializeThreads() {
		System.out.println("=== Inicializando modo CONCURRENTE con " + NUM_THREADS + " hilos ===");

		syncManager = new SynchronizationManager(NUM_THREADS);
		workers = new SimulationWorker[NUM_THREADS];

		int rowsPerThread = ocean.getHeight() / NUM_THREADS;

		for (int i = 0; i < NUM_THREADS; i++) {
			int startRow = i * rowsPerThread;
			int endRow = (i == NUM_THREADS - 1) ?
					ocean.getHeight() : (i + 1) * rowsPerThread;

			workers[i] = new SimulationWorker(i, startRow, endRow, this, syncManager);
			workers[i].start();
		}
	}

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

	// ===== MÉTODO STEP MODIFICADO =====
	public void step() {
		long start = System.nanoTime();

		if (concurrentMode && workers != null) {
			// MODO CONCURRENTE
			stepConcurrent();
		} else {
			// MODO SECUENCIAL (original)
			stepSequential();
		}

		generation++;

		long elapsed = System.nanoTime() - start;
		System.out.println("Step " + generation + ": " + (elapsed*1e-9) + " s (" +
				(concurrentMode ? "CONCURRENTE" : "SECUENCIAL") + ")");

		repaint();
	}

	/**
	 * Versión CONCURRENTE con hilos
	 */
	private void stepConcurrent() {
		try {
			// 1. Iniciar nueva generación (los hilos arrancan)
			syncManager.startNewGeneration();

			// 2. Esperar a que todos calculen estadísticas
			syncManager.waitForStatistics();

			// 3. Obtener estadísticas globales
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
	 * Versión SECUENCIAL (original)
	 */
	private void stepSequential() {
		for (int y = 0; y < ocean.getHeight(); y++) {
			for (int x = 0; x < ocean.getWidth(); x++) {
				Cell c = ocean.getField(x, y);
				if (c != null && c.isPending(generation))
					c.update(ocean, x, y, generation,
							fishRebornCycle, sharkRebornCycle);
			}
		}

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

	// ===== MÉTODOS DE ACCESO PARA LOS WORKERS =====
	public Ocean getOcean() {
		return ocean;
	}

	public int getFishCycle() {
		return fishRebornCycle;
	}

	public int getSharkCycle() {
		return sharkRebornCycle;
	}

	// ===== SHUTDOWN (llamar al cerrar) =====
	public void shutdown() {
		if (workers != null) {
			System.out.println("Finalizando hilos...");
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