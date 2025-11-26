package simulation.fishandsharks;

import java.awt.Color;
import java.awt.Point;
import java.util.Arrays;

public class Ocean {
	// The energy received by sharks each time the eats a fish.
	static final int DFishEnergy = 2;

	public static final Point[] NEIGHBOUR_MASK = {
			new Point(0, -1), new Point(1, 0), new Point(0, 1), new Point(-1, 0)
	};

	// Ocean/map cell matrix
	private Cell[][] ocean;
	private int width, height;

	public Ocean(int width, int height) {
		if (width < 1 || height < 1)
			throw new IllegalArgumentException();

		this.ocean = new Cell[height][width];
		this.width = width;
		this.height = height;

		for (int j = 0; j < ocean.length; j++) {
			for (int i = 0; i < ocean[j].length; i++) {
				ocean[j][i] = null;
			}
		}
	}

	// SINCRONIZADO: Evitar condiciones de carrera
	public synchronized Cell setField(int x, int y, Cell value) {
		return ocean[(height+(y%height))%height][(width+(x%width))%width] = value;
	}

	// SINCRONIZADO: Evitar condiciones de carrera
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

	// Obtain the free neighbour cells from x,y position.
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

	// Obtain the shark occupied neighbour cells from x,y position.
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

	// Obtain the fish occupied neighbour cells from x,y position.
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

	private void checkPointBorders(Point pos) {
		if (pos.x<0) pos.x = getWidth()-1;
		if (pos.x>=getWidth()) pos.x = 0;
		if (pos.y<0) pos.y = getHeight()-1;
		if (pos.y>=getHeight()) pos.y = 0;
	}

	// Ocean Cell based class
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

	// Fish cell class
	public static class Fish extends Cell {

		// Implements fish simulation rules
		@Override
		protected void internalUpdate(Ocean o, int x, int y, int generation,
									  int fishCycle, int sharkCycle) {
			Point[] freeNeighbors;

			// Rule 1:
			// A fish moves randomly to a neighbor field if it's free and
			// he has regenerated from the last time
			freeNeighbors = o.getFreeNeighbours(x, y);
			if (freeNeighbors.length > 0) {
				// Get the new cell
				Point newCell = getRandomly(freeNeighbors);

				// Move this object to the new position
				o.setField(x, y, null);
				x = newCell.x;
				y = newCell.y;
				o.setField(x, y, this);
			}

			// Rule 2:
			// On a neighbor field which is empty a new fish is born
			freeNeighbors = o.getFreeNeighbours(x, y);
			if (freeNeighbors.length > 0 && generation%fishCycle == 0) {
				// Get the new cell
				Point newCell = getRandomly(freeNeighbors);

				// Create the new fish
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

	// Shark cell class
	public static class Shark extends Cell {

		public int lifeIndex = 2;

		// Implements shark simulation rules - CORREGIDO
		@Override
		protected void internalUpdate(Ocean o, int x, int y, int generation,
									  int fishCycle, int sharkCycle) {
			Point[] fishNeighbors;
			Point[] freeNeighbors;

			// Rule 1:
			// A shark eats all fishes in neighborhood
			fishNeighbors = o.getFishNeighbours(x, y);
			for (Point fish : fishNeighbors) {
				// CORRECCIÓN: Verificar que la celda sigue siendo un pez
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

			// Rule 2:
			// If the shark eats nothing he moves to a free cell
			freeNeighbors = o.getFreeNeighbours(x, y);
			if (fishNeighbors.length < 1 && freeNeighbors.length > 0) {
				// Get the new cell
				Point newCell = getRandomly(freeNeighbors);

				// Move this object to the new position
				o.setField(x, y, null);
				x = newCell.x;
				y = newCell.y;
				o.setField(x, y, this);
			}

			// Rule 3:
			// On a neighbor field which is empty a new shark is born after
			// regeneration
			freeNeighbors = o.getFreeNeighbours(x, y);
			if (freeNeighbors.length > 0 && generation%sharkCycle == 0) {
				// Get the new cell
				Point newCell = getRandomly(freeNeighbors);

				// Create the new shark
				o.setField(newCell.x, newCell.y, new Shark()).time =
						super.time + 1;

				// Create a children consume energy
				lifeIndex--;
			}

			// Rule 4:
			// A shark dies if he has nothing to eat two times
			if (fishNeighbors.length < 1)
				lifeIndex--;
			if (lifeIndex < 1) {
				// Remove this shark
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