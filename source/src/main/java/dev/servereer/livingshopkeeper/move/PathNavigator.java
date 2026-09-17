package dev.servereer.livingshopkeeper.move;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Bounded block A* over {@link NavMeshSampler} nodes. Finds a walkable path of block positions from one
 * standable block to another, or null if none within the node/range caps. Runs on the main thread (block
 * reads must be), but only when re-pathing (a barker reaches a waypoint or gets stuck), never per tick —
 * and the caps keep any single search cheap (≤ ~a couple thousand nodes over loaded chunks).
 */
public final class PathNavigator {

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private record Node(int x, int y, int z) {}

    /** Path of {x,y,z} block positions from start to target (inclusive), or null. */
    public List<int[]> findPath(World w, int sx, int sy, int sz, int tx, int ty, int tz,
                                int maxNodes, int maxRange) {
        Node start = new Node(sx, sy, sz);
        Node goal = new Node(tx, ty, tz);
        Map<Node, Node> came = new HashMap<>();
        Map<Node, Double> g = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        g.put(start, 0.0);
        open.add(new double[]{h(sx, sy, sz, tx, ty, tz), sx, sy, sz});
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            double[] top = open.poll();
            Node cur = new Node((int) top[1], (int) top[2], (int) top[3]);
            if (cur.equals(goal)) return reconstruct(came, cur);
            expanded++;
            double cg = g.getOrDefault(cur, Double.MAX_VALUE);
            if (top[0] - h(cur.x, cur.y, cur.z, tx, ty, tz) > cg + 1e-6) continue; // stale queue entry

            for (int[] d : DIRS) {
                int nx = cur.x + d[0], nz = cur.z + d[1];
                if (Math.abs(nx - sx) > maxRange || Math.abs(nz - sz) > maxRange) continue;
                int ny = NavMeshSampler.neighborY(w, nx, cur.y, nz);
                if (ny == Integer.MIN_VALUE) continue;
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag) {   // no cutting corners through walls
                    if (!NavMeshSampler.passable(w, cur.x + d[0], cur.y, cur.z)
                            || !NavMeshSampler.passable(w, cur.x, cur.y, cur.z + d[1])) continue;
                }
                if (ny > cur.y && !NavMeshSampler.passable(w, cur.x, cur.y + 2, cur.z)) continue; // head room to step up

                Node nb = new Node(nx, ny, nz);
                double cost = (diag ? 1.414 : 1.0) + (ny != cur.y ? 0.5 : 0.0);
                double tentative = cg + cost;
                if (tentative < g.getOrDefault(nb, Double.MAX_VALUE)) {
                    g.put(nb, tentative);
                    came.put(nb, cur);
                    open.add(new double[]{tentative + h(nx, ny, nz, tx, ty, tz), nx, ny, nz});
                }
            }
        }
        return null;
    }

    /** Octile-ish heuristic (admissible for 8-connected). */
    private static double h(int x, int y, int z, int tx, int ty, int tz) {
        int dx = Math.abs(x - tx), dz = Math.abs(z - tz), dy = Math.abs(y - ty);
        int lo = Math.min(dx, dz), hi = Math.max(dx, dz);
        return 1.414 * lo + (hi - lo) + 0.5 * dy;
    }

    private static List<int[]> reconstruct(Map<Node, Node> came, Node goal) {
        List<int[]> path = new ArrayList<>();
        Node n = goal;
        while (n != null) {
            path.add(new int[]{n.x, n.y, n.z});
            n = came.get(n);
        }
        Collections.reverse(path);
        return path;
    }
}
