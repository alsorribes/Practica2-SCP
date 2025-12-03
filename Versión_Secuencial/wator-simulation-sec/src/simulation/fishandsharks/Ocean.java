/* ---------------------------------------------------------------
Práctica 2.
Código fuente : Ocean.java
Grado Informática
39942072L Albert Sorribes Torrent.
X9321862P Porosnicu, Valentin Alexandru
--------------------------------------------------------------- */
package simulation.fishandsharks;

import java.awt.Color;
import java.awt.Point;
import java.util.Arrays;

/**
 * Clase que representa el océano toroidal de la simulación Wa-Tor.
 *
 * MODIFICACIONES PARA CONCURRENCIA:
 * - Métodos getField() y setField() declarados como synchronized
 * - Métodos getFreeNeighbours(), getFishNeighbours(), getSharkNeighbours() sincronizados
 * - Corrección en Shark.internalUpdate() para verificar null antes de acceder a celdas
 *
 * Estas modificaciones evitan condiciones de carrera cuando múltiples hilos
 * acceden simultáneamente a las celdas del océano.
 */
public class Ocean {
	// Energía que recibe un tiburón al comer un pez
	static final int DFishEnergy = 2;

	// Máscara de vecinos (Norte, Este, Sur, Oeste)
	public static final Point[] NEIGHBOUR_MASK = {
			new Point(0, -1), new Point(1, 0), new Point(0, 1), new Point(-1, 0)
	};

	// Matriz de celdas del océano
	private Cell[][] ocean;
	private int width, height;

	public Ocean(int width, int height) {
		if (width < 1 || height < 1)
			throw new IllegalArgumentException();

		this.ocean = new Cell[height][width];
		this.width = width;
		this.height = height;

		// Inicializar todas las celdas a null (vacías)
		for (int j = 0; j < ocean.length; j++) {
			for (int i = 0; i < ocean[j].length; i++) {
				ocean[j][i] = null;
			}
		}
	}

	/**
	 * MODIFICACIÓN CONCURRENTE: synchronized
	 * Establece el valor de una celda de forma thread-safe.
	 *
	 * El modificador synchronized evita que dos hilos modifiquen
	 * la misma celda simultáneamente (condición de carrera).
	 *
	 * @param x Coordenada X (se aplica módulo para topología toroidal)
	 * @param y Coordenada Y (se aplica módulo para topología toroidal)
	 * @param value Nueva célula a colocar (puede ser null)
	 * @return La celda que se colocó
	 */
	public synchronized Cell setField(int x, int y, Cell value) {
		return ocean[(height+(y%height))%height][(width+(x%width))%width] = value;
	}

	/**
	 * MODIFICACIÓN CONCURRENTE: synchronized
	 * Obtiene el valor de una celda de forma thread-safe.
	 *
	 * El modificador synchronized evita leer una celda mientras
	 * otro hilo la está modificando.
	 *
	 * @param x Coordenada X
	 * @param y Coordenada Y
	 * @return La celda en esa posición (puede ser null)
	 */
	public synchronized Cell getField(int x, int y) {
		return ocean[(height+(y%height))%height][(width+(x%width))%width];
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	@Override
	public String toString() {
		StringBuffer buf = new StringBuffer();

		for (int j = 0; j < ocean.length; j++) {
			for (int i = 0; i < ocean[j].length; i++)
				buf.append(ocean[j][i] == null ? "." : ocean[j][i]);
			buf.append('\n');
		}

		return buf.toString();
	}

	/**
	 * MODIFICACIÓN CONCURRENTE: synchronized
	 * Obtiene las celdas vecinas libres de forma atómica.
	 *
	 * @param x Coordenada X de la celda origen
	 * @param y Coordenada Y de la celda origen
	 * @return Array de posiciones de celdas vecinas libres
	 */
	public synchronized Point[] getFreeNeighbours(int x, int y) {
		Point[] freeCells = {};
		Cell cell;

		for (int i = 0; i < NEIGHBOUR_MASK.length; i++) {
			Point mask = NEIGHBOUR_MASK[i];
			Point neighbour_pos = new Point(x+mask.x, y+mask.y);
			checkPointBorders(neighbour_pos);
			cell = getField(neighbour_pos.x, neighbour_pos.y);
			if (cell == null) {
				freeCells = Arrays.copyOf(freeCells, freeCells.length+1);
				freeCells[freeCells.length-1] = neighbour_pos;
			}
		}

		return freeCells;
	}

	/**
	 * MODIFICACIÓN CONCURRENTE: synchronized
	 * Obtiene las celdas vecinas ocupadas por tiburones.
	 */
	public synchronized Point[] getSharkNeighbours(int x, int y) {
		Point[] freeCells = {};
		Cell cell;

		for (int i = 0; i < NEIGHBOUR_MASK.length; i++) {
			Point mask = NEIGHBOUR_MASK[i];
			Point neighbour_pos = new Point(x+mask.x, y+mask.y);
			checkPointBorders(neighbour_pos);
			cell = getField(neighbour_pos.x, neighbour_pos.y);
			if (cell instanceof Shark) {
				freeCells = Arrays.copyOf(freeCells, freeCells.length+1);
				freeCells[freeCells.length-1] = neighbour_pos;
			}
		}

		return freeCells;
	}

	/**
	 * MODIFICACIÓN CONCURRENTE: synchronized
	 * Obtiene las celdas vecinas ocupadas por peces.
	 */
	public synchronized Point[] getFishNeighbours(int x, int y) {
		Point[] freeCells = {};
		Cell cell;

		for (int i = 0; i < NEIGHBOUR_MASK.length; i++) {
			Point mask = NEIGHBOUR_MASK[i];
			Point neighbour_pos = new Point(x+mask.x, y+mask.y);
			checkPointBorders(neighbour_pos);
			cell = getField(neighbour_pos.x, neighbour_pos.y);
			if (cell instanceof Fish) {
				freeCells = Arrays.copyOf(freeCells, freeCells.length+1);
				freeCells[freeCells.length-1] = neighbour_pos;
			}
		}

		return freeCells;
	}

	/**
	 * Ajusta las coordenadas para topología toroidal.
	 */
	private void checkPointBorders(Point pos) {
		if (pos.x<0) pos.x = getWidth()-1;
		if (pos.x>=getWidth()) pos.x = 0;
		if (pos.y<0) pos.y = getHeight()-1;
		if (pos.y>=getHeight()) pos.y = 0;
	}

	// Clase base abstracta para celdas del océano
	public static abstract class Cell {

		public static final Color OCEAN_LIGHT = new Color(0xE5F9FF),
				OCEAN_DARK = new Color(0xB3ECFF),
				FISH = new Color(0x3BEB00),
				SHARK = new Color(0xFF571F);

		private int time, age;

		public Cell() {
			time = 0;
			age = 0;
		}

		public void update(Ocean o, int x, int y, int generation,
						   int fishCycle, int sharkCycle) {
			internalUpdate(o, x, y, generation, fishCycle, sharkCycle);
			time++;
			age++;
		}

		public void setGeneration(int time) {
			this.time = time;
		}

		public boolean isPending(int generation) {
			return generation == time;
		}

		public int getAge() {
			return age;
		}

		protected abstract void internalUpdate(Ocean o, int x, int y,
											   int generation, int fishCycle, int sharkCycle);

		public abstract Color getColor();

	}

	// Clase para peces
	public static class Fish extends Cell {

		@Override
		protected void internalUpdate(Ocean o, int x, int y, int generation,
									  int fishCycle, int sharkCycle) {
			Point[] freeNeighbors;

			// Regla 1: Moverse a una celda vecina libre
			freeNeighbors = o.getFreeNeighbours(x, y);
			if (freeNeighbors.length > 0) {
				Point newCell = getRandomly(freeNeighbors);

				o.setField(x, y, null);
				x = newCell.x;
				y = newCell.y;
				o.setField(x, y, this);
			}

			// Regla 2: Reproducirse si se cumple el ciclo
			freeNeighbors = o.getFreeNeighbours(x, y);
			if (freeNeighbors.length > 0 && generation%fishCycle == 0) {
				Point newCell = getRandomly(freeNeighbors);

				o.setField(newCell.x, newCell.y, new Fish()).time =
						super.time + 1;
			}
		}

		@Override
		public Color getColor() {
			return Cell.FISH;
		}

		@Override
		public String toString() {
			return "f";
		}

	}

	// Clase para tiburones
	public static class Shark extends Cell {

		public int lifeIndex = 2;

		/**
		 * MODIFICACIÓN CONCURRENTE: Verificación de null añadida
		 *
		 * Se añade verificación cellAtFish != null antes de acceder a la celda.
		 * Esto previene NullPointerException cuando otro hilo ya movió/comió el pez.
		 */
		@Override
		protected void internalUpdate(Ocean o, int x, int y, int generation,
									  int fishCycle, int sharkCycle) {
			Point[] fishNeighbors;
			Point[] freeNeighbors;

			// Regla 1: Comer peces vecinos
			fishNeighbors = o.getFishNeighbours(x, y);
			for (Point fish : fishNeighbors) {
				// CORRECCIÓN CONCURRENTE: Verificar que la celda todavía contiene un pez
				// Entre getFishNeighbours() y este punto, otro hilo pudo haber
				// movido o comido el pez, dejando la celda en null
				Cell cellAtFish = o.getField(fish.x, fish.y);
				if (cellAtFish != null && cellAtFish instanceof Fish) {
					lifeIndex += DFishEnergy;
					o.setField(fish.x, fish.y, null);
					o.setField(x, y, null);
					x = fish.x;
					y = fish.y;
					o.setField(x, y, this);
					break;
				}
			}

			// Regla 2: Moverse si no hay peces
			freeNeighbors = o.getFreeNeighbours(x, y);
			if (fishNeighbors.length < 1 && freeNeighbors.length > 0) {
				Point newCell = getRandomly(freeNeighbors);

				o.setField(x, y, null);
				x = newCell.x;
				y = newCell.y;
				o.setField(x, y, this);
			}

			// Regla 3: Reproducirse si tiene energía suficiente
			freeNeighbors = o.getFreeNeighbours(x, y);
			if (freeNeighbors.length > 0 && generation%sharkCycle == 0) {
				Point newCell = getRandomly(freeNeighbors);

				o.setField(newCell.x, newCell.y, new Shark()).time =
						super.time + 1;

				lifeIndex--;
			}

			// Regla 4: Morir si no tiene energía
			if (fishNeighbors.length < 1)
				lifeIndex--;
			if (lifeIndex < 1) {
				o.setField(x, y, null);
			}
		}

		@Override
		public Color getColor() {
			return Cell.SHARK;
		}

		@Override
		public String toString() {
			return lifeIndex == 2 ? "S" : (lifeIndex == 1 ? "s" : "-");
		}

	}

	public static <T> T getRandomly(T[] arr) {
		int index = (int) Math.round(Math.random()*Math.random()*2*arr.length)%arr.length;
		return arr[index];
	}
}