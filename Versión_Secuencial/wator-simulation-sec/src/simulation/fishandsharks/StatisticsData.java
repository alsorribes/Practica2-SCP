/* ---------------------------------------------------------------
Práctica 2.
Código fuente : StatisticsData.java
Grado Informática
39942072L Albert Sorribes Torrent.
--------------------------------------------------------------- */
package simulation.fishandsharks;

import java.util.Map;

/**
 * Clase de datos que encapsula las estadísticas calculadas por un hilo worker.
 *
 * Esta clase es un simple contenedor inmutable (todos los campos son final)
 * que facilita el paso de estadísticas entre hilos.
 *
 * Las estadísticas incluyen:
 * - Conteo de peces en las filas del hilo
 * - Conteo de tiburones en las filas del hilo
 * - Conteo de celdas vacías en las filas del hilo
 * - Distribución de edad por especie
 */
public class StatisticsData {
    // Número de peces encontrados
    public final int fish;

    // Número de tiburones encontrados
    public final int sharks;

    // Número de celdas vacías
    public final int empty;

    // Mapa de edad -> [contador_peces, contador_tiburones]
    // Permite analizar la distribución de edad de cada especie
    public final Map<Integer, int[]> ageDistribution;

    /**
     * Constructor que inicializa todos los campos.
     *
     * @param fish Número de peces
     * @param sharks Número de tiburones
     * @param empty Número de celdas vacías
     * @param ageDistribution Mapa de distribución de edad
     */
    public StatisticsData(int fish, int sharks, int empty, Map<Integer, int[]> ageDistribution) {
        this.fish = fish;
        this.sharks = sharks;
        this.empty = empty;
        this.ageDistribution = ageDistribution;
    }
}