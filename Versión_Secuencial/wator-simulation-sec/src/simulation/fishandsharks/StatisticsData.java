package simulation.fishandsharks;

import java.util.Map;

public class StatisticsData {
    public final int fish;
    public final int sharks;
    public final int empty;
    public final Map<Integer, int[]> ageDistribution;

    public StatisticsData(int fish, int sharks, int empty, Map<Integer, int[]> ageDistribution) {
        this.fish = fish;
        this.sharks = sharks;
        this.empty = empty;
        this.ageDistribution = ageDistribution;
    }
}