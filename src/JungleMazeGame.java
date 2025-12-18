/* JungleMazeGame.java
 * Jungle Maze — full program ready to copy-paste
 *
 * Added features:
 *  - Play backsound01.wav continuously in background (loop) while idle
 *  - When Start Solve clicked: stop backsound, start bubble.wav fast loop
 *  - When solver finishes: stop bubble loop and resume backsound
 *
 * Usage:
 *  javac JungleMazeGame.java
 *  java JungleMazeGame
 */

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.*;
import javax.swing.Timer;

public class JungleMazeGame extends JFrame {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JungleMazeGame mg = new JungleMazeGame();
            mg.setVisible(true);
        });
    }

    private final int BLOCK_SIZE = 22;
    private final int DEFAULT_MAZE_SIZE = 21; // odd recommended

    private Maze maze;
    private MazePanel mazePanel;
    private ControlPanel controlPanel;

    // SOUND: backsound + bubble loop + confetti clip
    private Clip backsoundClip = null;
    private Clip bubbleClip = null;
    private Clip confettiClip = null;
    private Timer bubbleLoopTimer = null; // timer to retrigger bubble clip fast

    public JungleMazeGame() {
        super("Jungle Maze Adventure - Generator & Solver");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        maze = new Maze(DEFAULT_MAZE_SIZE);
        mazePanel = new MazePanel(maze, BLOCK_SIZE);
        controlPanel = new ControlPanel();

        add(mazePanel, BorderLayout.CENTER);
        add(controlPanel, BorderLayout.EAST);
        pack();
        setLocationRelativeTo(null);
        setResizable(false);

        // preload the SFX (non-blocking, errors printed to console)
        backsoundClip = loadClipFlexible("backsound01.wav");
        bubbleClip = loadClipFlexible("bubble.wav");
        confettiClip = loadClipFlexible("confetti.wav");

        // start backsound loop (if available)
        startBacksoundLoop();

        // ensure resources closed on exit
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopBubbleLoop();
                stopBacksoundLoop();
                if (bubbleClip != null) { bubbleClip.stop(); bubbleClip.close(); }
                if (confettiClip != null) { confettiClip.stop(); confettiClip.close(); }
                if (backsoundClip != null) { backsoundClip.stop(); backsoundClip.close(); }
            }
        });
    }

    // Try loading clip from working dir first, then from classpath resource.
    private Clip loadClipFlexible(String filename) {
        try {
            AudioInputStream ais = null;
            File f = new File(filename);
            if (f.exists()) {
                ais = AudioSystem.getAudioInputStream(f);
            } else {
                InputStream is = getClass().getResourceAsStream("/" + filename);
                if (is != null) {
                    // wrap so AudioSystem can mark/reset
                    ais = AudioSystem.getAudioInputStream(new BufferedInputStream(is));
                }
            }
            if (ais == null) {
                System.out.println("Sound not found: " + filename);
                return null;
            }
            AudioFormat baseFormat = ais.getFormat();
            AudioFormat decodedFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(),
                    16,
                    baseFormat.getChannels(),
                    baseFormat.getChannels() * 2,
                    baseFormat.getSampleRate(),
                    false
            );
            AudioInputStream dais = AudioSystem.getAudioInputStream(decodedFormat, ais);
            Clip clip = AudioSystem.getClip();
            clip.open(dais);
            return clip;
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException ex) {
            System.err.println("Failed to load sound " + filename + " : " + ex.getMessage());
            return null;
        }
    }

    // Start backsound using Clip.loop if available. Does nothing if clip missing.
    void startBacksoundLoop() {
        if (backsoundClip == null) return;
        try {
            if (backsoundClip.isRunning()) return; // already playing
            backsoundClip.setFramePosition(0);
            backsoundClip.loop(Clip.LOOP_CONTINUOUSLY);
        } catch (Exception ex) {
            // fallback: try Timer retrigger (rare)
            try {
                if (bubbleLoopTimer != null) bubbleLoopTimer.stop();
                bubbleLoopTimer = new Timer(1000, e -> {
                    try {
                        if (backsoundClip.isRunning()) backsoundClip.stop();
                        backsoundClip.setFramePosition(0);
                        backsoundClip.start();
                    } catch (Exception ignored) {}
                });
                bubbleLoopTimer.setInitialDelay(0);
                bubbleLoopTimer.start();
            } catch (Exception ignored) {}
        }
    }

    void stopBacksoundLoop() {
        if (backsoundClip == null) return;
        try {
            if (backsoundClip.isRunning()) backsoundClip.stop();
            backsoundClip.setFramePosition(0);
        } catch (Exception ignored) {}
    }

    // Start the fast bubble loop: repeatedly restart bubbleClip every interval millis.
    // If clip missing, this does nothing.
    void startBubbleLoop(int intervalMillis) {
        stopBubbleLoop(); // ensure previous stopped
        if (bubbleClip == null) return;
        bubbleLoopTimer = new Timer(intervalMillis, e -> {
            try {
                if (bubbleClip.isRunning()) bubbleClip.stop();
                bubbleClip.setFramePosition(0);
                bubbleClip.start();
            } catch (Exception ex) {
                // ignore
            }
        });
        bubbleLoopTimer.setInitialDelay(0);
        bubbleLoopTimer.start();
    }

    void stopBubbleLoop() {
        if (bubbleLoopTimer != null) {
            bubbleLoopTimer.stop();
            bubbleLoopTimer = null;
        }
        if (bubbleClip != null) {
            try { bubbleClip.stop(); bubbleClip.setFramePosition(0); } catch (Exception ignored) {}
        }
    }

    void playConfettiOnce() {
        if (confettiClip == null) return;
        try {
            if (confettiClip.isRunning()) confettiClip.stop();
            confettiClip.setFramePosition(0);
            confettiClip.start();
        } catch (Exception ignored) {}
    }

    /* ===========================
       Maze data & generation
       =========================== */
    static class Maze {
        final int size;                 // grid is size x size (odd preferred)
        int[][] state;                  // WALL, PATH, EXPLORED, SOLUTION, START, EXIT
        int[][] terrain;                // TERR_DEFAULT, TERR_GRASS, TERR_MUD, TERR_WATER (only for non-WALL)
        int startR, startC, exitR, exitC;
        Random rnd = new Random();

        // state codes
        static final int WALL = 1;
        static final int PATH = 0;
        static final int EXPLORED = 2;
        static final int SOLUTION = 3;
        static final int START = 4;
        static final int EXIT = 5;

        // terrain codes
        static final int TERR_DEFAULT = 0; // cream, weight 1 (integer)
        static final int TERR_GRASS = 1;   // weight 1
        static final int TERR_MUD = 2;     // weight 5
        static final int TERR_WATER = 3;   // weight 10

        // terrain weights (integers)
        static double terrainWeight(int t) {
            switch (t) {
                case TERR_GRASS: return 1.0;
                case TERR_MUD: return 5.0;
                case TERR_WATER: return 10.0;
                case TERR_DEFAULT:
                default: return 1.0; // DEFAULT = 1 (integer)
            }
        }

        // minimal positive weight (for heuristic scaling)
        static double minPositiveWeight() {
            return 1.0; // minimal positive weight is 1
        }

        // neighbor offsets (for maze using odd-cell algorithm we step by 2)
        static final int[][] DIRS = {{-2,0},{0,2},{2,0},{0,-2}};
        static final int[][] DIRS4 = {{-1,0},{1,0},{0,-1},{0,1}};

        Maze(int size) {
            this.size = size % 2 == 1 ? size : size + 1;
            initGrid();
        }

        void initGrid() {
            state = new int[size][size];
            terrain = new int[size][size];
            for (int r=0;r<size;r++) {
                Arrays.fill(state[r], WALL);
                Arrays.fill(terrain[r], TERR_DEFAULT);
            }
            startR = startC = exitR = exitC = -1;
        }

        void clearMarks() {
            for (int r=0;r<size;r++) {
                for (int c=0;c<size;c++) {
                    if (state[r][c] == EXPLORED || state[r][c] == SOLUTION) {
                        state[r][c] = PATH;
                    }
                }
            }
            if (startR>=0) state[startR][startC] = START;
            if (exitR>=0) state[exitR][exitC] = EXIT;
        }

        boolean inBounds(int r, int c) {
            return r >= 0 && r < size && c >= 0 && c < size;
        }

        /* ---------- Generators ---------- */

        void generatePrim() {
            initGrid();
            int sr = 1, sc = 1;
            state[sr][sc] = PATH;
            List<int[]> walls = new ArrayList<>();
            addWalls(sr, sc, walls);
            while (!walls.isEmpty()) {
                int idx = rnd.nextInt(walls.size());
                int[] w = walls.remove(idx);
                int wr = w[0], wc = w[1];

                int pathCount = 0;
                int pr=-1, pc=-1;
                for (int[] d : DIRS4) {
                    int nr = wr + d[0], nc = wc + d[1];
                    if (inBounds(nr,nc) && state[nr][nc] == PATH) {
                        pathCount++; pr = nr; pc = nc;
                    }
                }
                if (pathCount == 1) {
                    state[wr][wc] = PATH;
                    int beyondR = wr + (wr - pr);
                    int beyondC = wc + (wc - pc);
                    if (inBounds(beyondR,beyondC) && state[beyondR][beyondC] == WALL) {
                        state[beyondR][beyondC] = PATH;
                        addWalls(beyondR, beyondC, walls);
                    }
                }
            }
            assignTerrains();
        }

        void addWalls(int r, int c, List<int[]> walls) {
            for (int[] d : DIRS4) {
                int wr = r + d[0], wc = c + d[1];
                if (inBounds(wr,wc) && state[wr][wc] == WALL) {
                    boolean already=false;
                    for (int[] ex : walls) if (ex[0]==wr && ex[1]==wc) { already=true; break; }
                    if (!already) walls.add(new int[]{wr,wc});
                }
            }
        }

        void generateKruskal() {
            initGrid();
            for (int r=1;r<size; r+=2) for (int c=1;c<size; c+=2) state[r][c] = PATH;
            List<Wall> walls = new ArrayList<>();
            for (int r=1;r<size;r+=2) for (int c=1;c<size;c+=2) {
                if (c+2 < size) walls.add(new Wall(r,c,r,c+2,r,c+1));
                if (r+2 < size) walls.add(new Wall(r,c,r+2,c,r+1,c));
            }
            Collections.shuffle(walls, rnd);
            UnionFind uf = new UnionFind(size*size);
            for (Wall w : walls) {
                int id1 = w.r1*size + w.c1;
                int id2 = w.r2*size + w.c2;
                if (!uf.same(id1,id2)) {
                    uf.union(id1,id2);
                    state[w.wr][w.wc] = PATH;
                }
            }
            assignTerrains();
        }

        static class Wall { int r1,c1,r2,c2,wr,wc; Wall(int a,int b,int c,int d,int e,int f){r1=a;c1=b;r2=c;c2=d;wr=e;wc=f;} }
        static class UnionFind {
            int[] p;
            UnionFind(int n){ p = new int[n]; for (int i=0;i<n;i++) p[i]=i; }
            int find(int a){ return p[a]==a ? a : (p[a]=find(p[a])); }
            boolean same(int a,int b){ return find(a)==find(b); }
            void union(int a,int b){ a=find(a); b=find(b); if (a!=b) p[b]=a; }
        }

        void placeStartAndExit() {
            List<int[]> edges = new ArrayList<>();
            for (int c=1; c<size-1; c+=2) {
                if (state[1][c] == PATH) edges.add(new int[]{0,c});
                if (state[size-2][c] == PATH) edges.add(new int[]{size-1,c});
            }
            for (int r=1; r<size-1; r+=2) {
                if (state[r][1] == PATH) edges.add(new int[]{r,0});
                if (state[r][size-2] == PATH) edges.add(new int[]{r,size-1});
            }
            if (edges.size() < 2) {
                startR = 1; startC = 1;
                exitR = size-2; exitC = size-2;
            } else {
                Collections.shuffle(edges, rnd);
                int[] s = edges.get(0);
                int[] e = edges.get(edges.size()-1);
                startR = s[0]; startC = s[1];
                exitR = e[0]; exitC = e[1];
            }
            state[startR][startC] = START;
            state[exitR][exitC] = EXIT;
            if (terrain[startR][startC] == TERR_DEFAULT) terrain[startR][startC] = TERR_GRASS;
            if (terrain[exitR][exitC] == TERR_DEFAULT) terrain[exitR][exitC] = TERR_GRASS;
        }

        void openRandomWalls(double fraction) {
            List<int[]> candidates = new ArrayList<>();
            for (int r=1;r<size-1;r++) for (int c=1;c<size-1;c++) if (state[r][c] == WALL) candidates.add(new int[]{r,c});
            Collections.shuffle(candidates, rnd);
            int toOpen = (int)(candidates.size() * fraction);
            for (int i=0;i<toOpen;i++) {
                int[] p = candidates.get(i);
                state[p[0]][p[1]] = PATH;
                terrain[p[0]][p[1]] = randomTerrain();
            }
        }

        void assignTerrains() {
            for (int r=0;r<size;r++) for (int c=0;c<size;c++) {
                if (state[r][c] != WALL) terrain[r][c] = randomTerrain();
                else terrain[r][c] = TERR_DEFAULT;
            }
        }

        // current probabilities (you can change if needed)
        // DEFAULT 70%, GRASS 15%, MUD 9%, WATER 6%
        int randomTerrain() {
            double v = rnd.nextDouble();
            if (v < 0.70) return TERR_DEFAULT;    // 70% default (cream)
            if (v < 0.85) return TERR_GRASS;      // next 15% -> grass
            if (v < 0.94) return TERR_MUD;        // next 9% -> mud
            return TERR_WATER;                    // last 6% -> water
        }

        /**
         * Create up to `count` extra ways by opening wall cells that directly separate two PATH cells
         * on opposite sides (vertical or horizontal). Each opened wall becomes PATH and assigned terrain.
         * This tends to create cycles and alternate routes.
         */
        void createExtraWays(int count) {
            if (count <= 0) return;
            // collect candidate walls which sit between two PATH cells on opposite sides
            List<int[]> candidates = new ArrayList<>();
            for (int r = 1; r < size-1; r++) {
                for (int c = 1; c < size-1; c++) {
                    if (state[r][c] != WALL) continue;
                    // vertical pair?
                    if (inBounds(r-1,c) && inBounds(r+1,c) && state[r-1][c] == PATH && state[r+1][c] == PATH) {
                        candidates.add(new int[]{r,c});
                        continue;
                    }
                    // horizontal pair?
                    if (inBounds(r,c-1) && inBounds(r,c+1) && state[r][c-1] == PATH && state[r][c+1] == PATH) {
                        candidates.add(new int[]{r,c});
                        continue;
                    }
                }
            }
            // shuffle so openings are random
            Collections.shuffle(candidates, rnd);
            int opened = 0;
            for (int i = 0; i < candidates.size() && opened < count; i++) {
                int[] p = candidates.get(i);
                int r = p[0], c = p[1];
                // safety: don't open cell if it is the immediate neighbor of start/exit, to avoid replacing start/exit
                if ((Math.abs(r - startR) + Math.abs(c - startC)) == 1) continue;
                if ((Math.abs(r - exitR) + Math.abs(c - exitC)) == 1) continue;
                // open it
                state[r][c] = PATH;
                terrain[r][c] = randomTerrain();
                opened++;
            }
            // If couldn't open desired count (not enough clear candidates), try a second pass:
            if (opened < count) {
                // find any wall adjacent to at least one PATH and open (less ideal, but increases connectivity)
                List<int[]> secondary = new ArrayList<>();
                for (int r = 1; r < size-1; r++) {
                    for (int c = 1; c < size-1; c++) {
                        if (state[r][c] != WALL) continue;
                        boolean adjPath = false;
                        for (int[] d : DIRS4) {
                            int nr = r + d[0], nc = c + d[1];
                            if (inBounds(nr,nc) && state[nr][nc] == PATH) { adjPath = true; break; }
                        }
                        if (adjPath) secondary.add(new int[]{r,c});
                    }
                }
                Collections.shuffle(secondary, rnd);
                for (int i=0; i<secondary.size() && opened < count; i++) {
                    int[] p = secondary.get(i);
                    int r = p[0], c = p[1];
                    if ((Math.abs(r - startR) + Math.abs(c - startC)) == 1) continue;
                    if ((Math.abs(r - exitR) + Math.abs(c - exitC)) == 1) continue;
                    state[r][c] = PATH;
                    terrain[r][c] = randomTerrain();
                    opened++;
                }
            }
            // done (opened might be < count if map too small)
        }
    }

    /* ===========================
       Maze visual panel (with confetti)
       =========================== */
    class MazePanel extends JPanel {
        Maze m;
        int blockSize;
        int padding = 20;

        // optional external door image
        BufferedImage doorImg = null;

        // CONFETTI fields
        boolean confettiActive = false;
        int confettiCount = 140;
        int[] confX, confY, confSpeed, confW, confH;
        Timer confettiTimer;
        Random confRng = new Random();

        MazePanel(Maze m, int blockSize) {
            this.m = m;
            this.blockSize = blockSize;
            int w = m.size * blockSize + padding * 2;
            int h = m.size * blockSize + padding * 2;
            setPreferredSize(new Dimension(w, h));
            setBackground(new Color(16, 48, 20));

            // Try loading door.png or door.jpg from working directory first, then classpath
            try {
                File f = new File("door.png");
                if (!f.exists()) f = new File("door.jpg");
                if (f.exists()) {
                    doorImg = ImageIO.read(f);
                } else {
                    // try classpath resource
                    URL u = getClass().getResource("/door.png");
                    if (u == null) u = getClass().getResource("/door.jpg");
                    if (u != null) {
                        doorImg = ImageIO.read(u);
                    }
                }
            } catch (IOException ex) {
                doorImg = null;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D)g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawBackgroundTexture(g2);
            drawCells(g2);

            if (confettiActive) drawConfetti(g2);
        }

        void drawBackgroundTexture(Graphics2D g2) {
            for (int y=0; y<getHeight(); y+=40) {
                g2.setColor(new Color(12 + (y%60)/6, 30 + (y%40)/5, 10 + (y%40)/8, 25));
                g2.fillRect(0,y,getWidth(),40);
            }
        }

        void drawCells(Graphics2D g2) {
            for (int r=0;r<m.size;r++) {
                for (int c=0;c<m.size;c++) {
                    int x = padding + c*blockSize;
                    int y = padding + r*blockSize;
                    int s = blockSize;
                    int st = m.state[r][c];
                    if (st == Maze.WALL) {
                        drawRockBlock(g2, x, y, s); // rock walls
                    } else {
                        drawTerrain(g2, x, y, s, m.terrain[r][c]);
                        if (st == Maze.EXPLORED) drawExploredOverlay(g2, x, y, s);
                        else if (st == Maze.SOLUTION) drawSolutionOverlay(g2, x, y, s);
                        else if (st == Maze.START) drawStartIcon(g2, x, y, s);
                        else if (st == Maze.EXIT) drawDoorIcon(g2, x, y, s); // door icon (image or fallback)
                    }
                    g2.setColor(new Color(0,0,0,40));
                    g2.drawRect(x, y, s-1, s-1);
                }
            }
        }

        void drawRockBlock(Graphics2D g2, int x, int y, int s) {
            // darker rock look
            GradientPaint gp = new GradientPaint(
                    x, y, new Color(53, 53, 53),
                    x + s, y + s, new Color(105, 105, 105)
            );
            g2.setPaint(gp);

            RoundRectangle2D rr = new RoundRectangle2D.Double(
                    x + 1, y + 1, s - 2, s - 2,
                    s / 5.0, s / 5.0
            );
            g2.fill(rr);

            // rock texture spots
            g2.setColor(new Color(221, 221, 221, 180));
            for (int i = 0; i < 3; i++) {
                g2.fillOval(x + 4 + i * 6, y + 3 + (i % 2) * 4, 6, 5);
            }

            // inner outline (gray)
            g2.setColor(new Color(127, 125, 125, 200));
            g2.setStroke(new BasicStroke(2f));
            g2.draw(rr);

            // === BLACK BORDER OUTLINE (kotak persegi) ===
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(2f));
            g2.drawRect(x, y, s - 1, s - 1);
        }


        void drawTerrain(Graphics2D g2, int x, int y, int s, int terrainType) {
            switch (terrainType) {
                case Maze.TERR_GRASS: {
                    GradientPaint gp = new GradientPaint(x, y, new Color(110,180,80), x+s, y+s, new Color(70,140,60));
                    g2.setPaint(gp);
                    g2.fillRect(x, y, s, s);
                    g2.setColor(new Color(40,110,40,60));
                    for (int i=0;i<4;i++) g2.fillRect(x + 3 + i*5, y + 3 + (i%2==0?1:3), 2, 6);
                    break;
                }
                case Maze.TERR_MUD: {
                    g2.setColor(new Color(85, 75, 45)); // swampy dark brown-green
                    g2.fillRect(x, y, s, s);
                    g2.setColor(new Color(65,45,28));
                    for (int i=0;i<3;i++) g2.fillOval(x + 3 + i*6, y + 4 + (i%2)*3, 6, 4);
                    g2.setColor(new Color(50,80,40,40));
                    for (int i=0;i<2;i++) g2.fillOval(x+4+i*6, y+6, 4,3);
                    break;
                }
                case Maze.TERR_WATER: {
                    GradientPaint wp = new GradientPaint(x, y, new Color(80,170,220), x+s, y+s, new Color(30,110,180));
                    g2.setPaint(wp);
                    g2.fillRect(x, y, s, s);
                    g2.setColor(new Color(255,255,255,60));
                    for (int i=0;i<2;i++) g2.drawLine(x+4, y+6+i*4, x+s-6, y+6+i*4);
                    break;
                }
                case Maze.TERR_DEFAULT:
                default: {
                    // cream/light stone-like (krem muda)
                    GradientPaint sp = new GradientPaint(x, y, new Color(245,240,225), x+s, y+s, new Color(225,215,195));
                    g2.setPaint(sp);
                    g2.fillRect(x, y, s, s);
                    g2.setColor(new Color(215,205,185));
                    for (int i=0;i<3;i++) g2.fillRect(x+3 + i*6, y+6, 4, 4);
                    break;
                }
            }
        }

        void drawExploredOverlay(Graphics2D g2, int x, int y, int s) {
            g2.setColor(new Color(246, 61, 61, 126));
            g2.fillRect(x+2, y+2, s-4, s-4);
        }

        void drawSolutionOverlay(Graphics2D g2, int x, int y, int s) {
            // bright blue final path
            g2.setColor(new Color(255, 30, 30,220)); // DodgerBlue
            g2.fillRect(x+2, y+2, s-4, s-4);
        }

        void drawStartIcon(Graphics2D g2, int x, int y, int s) {
            int cx = x + s/2, cy = y + s/2;
            Ellipse2D.Double e = new Ellipse2D.Double(cx - s*0.28, cy - s*0.28, s*0.56, s*0.56);
            g2.setColor(new Color(30,140,60));
            g2.fill(e);
            g2.setColor(new Color(220,255,220,160));
            g2.fillOval(cx - 6, cy - 8, 10, 6);
            g2.setColor(new Color(10,60,10));
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(e);
        }

        void drawDoorIcon(Graphics2D g2, int x, int y, int s) {
            int pad = Math.max(2, s/8);
            int dx = x + pad, dy = y + pad;
            int dw = s - pad*2, dh = s - pad*2;

            if (doorImg != null) {
                // draw the image scaled to fit inside the cell (with padding), preserving aspect ratio
                int iw = doorImg.getWidth();
                int ih = doorImg.getHeight();
                double scale = Math.min((double)dw / iw, (double)dh / ih);
                int drawW = (int) Math.round(iw * scale);
                int drawH = (int) Math.round(ih * scale);
                int drawX = dx + (dw - drawW) / 2;
                int drawY = dy + (dh - drawH) / 2;
                g2.drawImage(doorImg, drawX, drawY, drawW, drawH, null);
                g2.setColor(new Color(0,0,0,60));
                g2.drawRect(drawX, drawY, drawW-1, drawH-1);
                return;
            }

            // fallback: stylized door drawn with Java2D
            RoundRectangle2D frame = new RoundRectangle2D.Double(dx-2, dy-2, dw+4, dh+4, 6, 6);
            g2.setColor(new Color(100,60,30));
            g2.fill(frame);

            GradientPaint gp = new GradientPaint(dx, dy, new Color(180,110,60), dx+dw, dy+dh, new Color(140,80,40));
            g2.setPaint(gp);
            RoundRectangle2D door = new RoundRectangle2D.Double(dx, dy, dw, dh, 4, 4);
            g2.fill(door);

            g2.setColor(new Color(120,70,35,120));
            int seg = 3;
            for (int i=1;i<seg;i++) {
                int px = dx + i * (dw / seg);
                g2.fillRect(px-1, dy+4, 2, dh-8);
            }

            int kx = dx + dw - Math.max(8, s/5);
            int ky = dy + dh/2;
            g2.setColor(new Color(220,200,120));
            g2.fillOval(kx-3, ky-3, 6, 6);
            g2.setColor(new Color(120,90,50));
            g2.drawOval(kx-3, ky-3, 6, 6);
        }

        // -------- Confetti: start/paint/stop --------
        void startConfetti() {
            confettiActive = true;
            int w = Math.max(1, getWidth());
            int h = Math.max(1, getHeight());
            confX = new int[confettiCount];
            confY = new int[confettiCount];
            confSpeed = new int[confettiCount];
            confW = new int[confettiCount];
            confH = new int[confettiCount];
            for (int i = 0; i < confettiCount; i++) {
                confX[i] = confRng.nextInt(w);
                confY[i] = confRng.nextInt(60) - 60;
                confSpeed[i] = 2 + confRng.nextInt(4);
                confW[i] = 4 + confRng.nextInt(6);
                confH[i] = confW[i];
            }
            if (confettiTimer != null) confettiTimer.stop();
            confettiTimer = new Timer(16, e -> {
                for (int i = 0; i < confettiCount; i++) {
                    confY[i] += confSpeed[i];
                    if (confY[i] > h + 20) {
                        confX[i] = confRng.nextInt(w);
                        confY[i] = -20 - confRng.nextInt(80);
                        confSpeed[i] = 2 + confRng.nextInt(4);
                    }
                }
                repaint();
            });
            confettiTimer.start();

            // auto-stop after 3 seconds
            new Timer(3000, e -> {
                confettiActive = false;
                if (confettiTimer != null) confettiTimer.stop();
                confettiTimer = null;
                repaint();
                ((Timer)e.getSource()).stop();
            }).start();
        }

        void drawConfetti(Graphics2D g2) {
            for (int i = 0; i < confettiCount; i++) {
                g2.setColor(new Color(
                        50 + confRng.nextInt(206),
                        50 + confRng.nextInt(206),
                        50 + confRng.nextInt(206),
                        220
                ));
                g2.fillRect(confX[i], confY[i], confW[i], confH[i]);
            }
        }
    }

    /* ===========================
       Control panel & interactions
       =========================== */
    class ControlPanel extends JPanel {
        JComboBox<String> genAlgChoice;
        JComboBox<String> solveChoice;
        JButton genBtn, solveBtn, pauseBtn, resetBtn;
        JSlider delaySlider, loopsSlider, sizeSlider;
        JSpinner extraWaysSpinner;
        JLabel statusLabel;
        AtomicBoolean solving = new AtomicBoolean(false);
        Timer solverTimer = null;
        Solver currentSolver;

        ControlPanel() {
            setLayout(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            setBackground(new Color(28,56,28));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = new Insets(4,4,4,4);

            JLabel t = new JLabel("Generator");
            t.setForeground(Color.WHITE);
            t.setFont(new Font("SansSerif", Font.BOLD, 13));
            add(t, gbc);

            genAlgChoice = new JComboBox<>(new String[]{"Prim", "Kruskal"});
            gbc.gridy++; add(genAlgChoice, gbc);

            gbc.gridy++;
            add(new JLabel("Extra loops (%)") {{ setForeground(Color.WHITE); }}, gbc);
            loopsSlider = new JSlider(0,100,8);
            gbc.gridy++; add(loopsSlider, gbc);

            gbc.gridy++;
            add(new JLabel("Extra ways (open walls)") {{ setForeground(Color.WHITE); }}, gbc);
            extraWaysSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100, 1));
            gbc.gridy++; add(extraWaysSpinner, gbc);

            gbc.gridy++;
            add(new JLabel("Grid size (odd)") {{ setForeground(Color.WHITE); }}, gbc);
            sizeSlider = new JSlider(11, 51, DEFAULT_MAZE_SIZE);
            sizeSlider.setMajorTickSpacing(10); sizeSlider.setPaintTicks(true);
            gbc.gridy++; add(sizeSlider, gbc);

            genBtn = new JButton("Generate Maze");
            genBtn.setBackground(new Color(85,170,85)); genBtn.setForeground(Color.WHITE);
            gbc.gridy++; add(genBtn, gbc);

            gbc.gridy++; add(new JSeparator(), gbc);

            JLabel s = new JLabel("Solver");
            s.setForeground(Color.WHITE); s.setFont(new Font("SansSerif", Font.BOLD, 13));
            gbc.gridy++; add(s, gbc);

            solveChoice = new JComboBox<>(new String[]{"BFS", "DFS", "Dijkstra", "A*"});
            gbc.gridy++; add(solveChoice, gbc);

            gbc.gridy++; add(new JLabel("Animation delay (ms)") {{ setForeground(Color.WHITE); }}, gbc);
            delaySlider = new JSlider(5,500,40);
            gbc.gridy++; add(delaySlider, gbc);

            solveBtn = new JButton("Start Solve");
            solveBtn.setBackground(new Color(0,130,200)); solveBtn.setForeground(Color.WHITE);
            gbc.gridy++; add(solveBtn, gbc);

            pauseBtn = new JButton("Pause"); pauseBtn.setEnabled(false);
            pauseBtn.setBackground(new Color(200,120,20)); pauseBtn.setForeground(Color.WHITE);
            gbc.gridy++; add(pauseBtn, gbc);

            resetBtn = new JButton("Reset Visuals");
            resetBtn.setBackground(new Color(130,130,130)); resetBtn.setForeground(Color.WHITE);
            gbc.gridy++; add(resetBtn, gbc);

            gbc.gridy++; add(new JSeparator(), gbc);

            statusLabel = new JLabel("Ready"); statusLabel.setForeground(Color.YELLOW);
            gbc.gridy++; add(statusLabel, gbc);

            // Listeners
            genBtn.addActionListener(e -> {
                if (solving.get()) { JOptionPane.showMessageDialog(JungleMazeGame.this, "Cannot generate while solving"); return; }
                generateMazeAction();
            });

            solveBtn.addActionListener(e -> {
                if (solving.get()) { JOptionPane.showMessageDialog(JungleMazeGame.this, "Already solving"); return; }
                startSolvingAction();
            });

            pauseBtn.addActionListener(e -> {
                if (solverTimer != null) {
                    if (solverTimer.isRunning()) {
                        solverTimer.stop();
                        pauseBtn.setText("Resume"); statusLabel.setText("Paused");
                    } else {
                        solverTimer.start();
                        pauseBtn.setText("Pause"); statusLabel.setText("Solving...");
                    }
                }
            });

            resetBtn.addActionListener(e -> {
                if (solving.get()) { JOptionPane.showMessageDialog(JungleMazeGame.this, "Cannot reset while solving"); return; }
                maze.clearMarks();
                mazePanel.repaint();
                statusLabel.setText("Visuals reset");
            });

            // initial generate
            generateMazeAction();
        }

        void generateMazeAction() {
            String genAlg = (String)genAlgChoice.getSelectedItem();
            int size = sizeSlider.getValue();
            if (size % 2 == 0) size++;
            maze = new Maze(size);
            mazePanel.m = maze;
            mazePanel.setPreferredSize(new Dimension(maze.size * BLOCK_SIZE + mazePanel.padding*2,
                    maze.size * BLOCK_SIZE + mazePanel.padding*2));
            pack();

            statusLabel.setText("Generating...");
            if ("Prim".equals(genAlg)) maze.generatePrim();
            else maze.generateKruskal();

            maze.placeStartAndExit();

            // apply extra ways (open specific walls to create alternative routes)
            int extraWays = (int) extraWaysSpinner.getValue();
            if (extraWays > 0) {
                maze.createExtraWays(extraWays);
            }

            double frac = loopsSlider.getValue()/100.0;
            maze.openRandomWalls(frac);

            statusLabel.setText("Maze generated ("+maze.size+"x"+maze.size+"). Start at ("+maze.startR+","+maze.startC+"). Exit at ("+maze.exitR+","+maze.exitC+")");
            mazePanel.repaint();
        }

        void startSolvingAction() {
            String solverName = (String)solveChoice.getSelectedItem();
            SolverType type;
            if ("BFS".equals(solverName)) type = SolverType.BFS;
            else if ("DFS".equals(solverName)) type = SolverType.DFS;
            else if ("Dijkstra".equals(solverName)) type = SolverType.DIJKSTRA;
            else type = SolverType.ASTAR;

            int delay = delaySlider.getValue();
            maze.clearMarks();

            currentSolver = new Solver(maze, type);
            solving.set(true);
            solveBtn.setEnabled(false);
            pauseBtn.setEnabled(true);
            statusLabel.setText("Solving...");

            // --- STOP backsound and START bubble loop SFX (fast retrigger) ---
            JungleMazeGame.this.stopBacksoundLoop();
            JungleMazeGame.this.startBubbleLoop(350);

            solverTimer = new Timer(delay, null);
            solverTimer.addActionListener(ev -> {
                boolean finished = currentSolver.step();
                mazePanel.repaint();
                if (finished) {
                    solverTimer.stop();

                    // stop bubble sound/loop immediately
                    JungleMazeGame.this.stopBubbleLoop();

                    // resume backsound after solver finishes
                    JungleMazeGame.this.startBacksoundLoop();

                    solving.set(false);
                    pauseBtn.setEnabled(false);
                    solveBtn.setEnabled(true);
                    if (currentSolver.found) {
                        // play confetti sound and start visual confetti
                        JungleMazeGame.this.playConfettiOnce();
                        mazePanel.startConfetti();

                        // format weight: integer (weights are integers)
                        String wtText = String.format("%d", (long)Math.round(currentSolver.totalWeight));
                        String msg = "FOUND\nTraversal Steps : " + currentSolver.steps
                                + "\nShortest Path Steps : " + currentSolver.shortestPathSteps
                                + "\nWeight Total : " + wtText;
                        statusLabel.setText("FOUND — Traversal: " + currentSolver.steps + " — Path steps: " + currentSolver.shortestPathSteps + " — Weight: " + wtText);
                        JOptionPane.showMessageDialog(JungleMazeGame.this, msg, "Result", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        statusLabel.setText("No path");
                        JOptionPane.showMessageDialog(JungleMazeGame.this, "No path found", "Result", JOptionPane.WARNING_MESSAGE);
                    }
                }
            });
            solverTimer.setDelay(delay);
            solverTimer.start();
        }
    }

    /* ===========================
       Solver implementations
       - BFS/DFS: unweighted exploration
       - Dijkstra/A*: use terrain weights (terrainWeight)
       =========================== */
    enum SolverType { BFS, DFS, DIJKSTRA, ASTAR }

    class Solver {
        Maze mz;
        SolverType type;
        boolean found = false;
        int steps = 0;
        int shortestPathSteps = 0;
        double totalWeight = 0.0;

        // BFS/DFS - shared deque
        Deque<SimpleNode> deque;
        boolean[][] visited;
        SimpleNode[][] parent;

        // Dijkstra/A*
        double[][] dist;
        SimpleNode[][] dparent;
        PriorityQueue<PQNode> pq;

        Solver(Maze mz, SolverType type) {
            this.mz = mz;
            this.type = type;
            visited = new boolean[mz.size][mz.size];
            parent = new SimpleNode[mz.size][mz.size];
            dparent = new SimpleNode[mz.size][mz.size];

            if (type == SolverType.BFS || type == SolverType.DFS) {
                deque = new ArrayDeque<>();
                deque.add(new SimpleNode(mz.startR, mz.startC, 0));
                visited[mz.startR][mz.startC] = true;
            } else {
                dist = new double[mz.size][mz.size];
                for (int r=0;r<mz.size;r++) Arrays.fill(dist[r], Double.POSITIVE_INFINITY);
                pq = new PriorityQueue<>(Comparator.comparingDouble(n -> n.priority));
                dist[mz.startR][mz.startC] = 0.0;
                double h0 = (type == SolverType.ASTAR) ? heuristic(mz.startR, mz.startC) : 0.0;
                pq.add(new PQNode(mz.startR, mz.startC, 0.0, h0));
            }
        }

        class SimpleNode {
            int r,c;
            int d;
            SimpleNode(int r,int c,int d){this.r=r;this.c=c;this.d=d;}
        }

        class PQNode {
            int r,c;
            double priority;
            double g;
            PQNode(int r,int c,double g,double h){
                this.r=r; this.c=c; this.g=g; this.priority=g+h;
            }
        }

        double heuristic(int r, int c) {
            int dr = Math.abs(r - mz.exitR);
            int dc = Math.abs(c - mz.exitC);
            return (dr + dc) * Maze.minPositiveWeight();
        }

        boolean step() {
            if (type == SolverType.BFS || type == SolverType.DFS) {
                if (deque.isEmpty()) return true;

                SimpleNode cur;
                // FIX: DFS should pollLast (LIFO behavior)
                if (type == SolverType.BFS) {
                    cur = deque.pollFirst();  // BFS: FIFO (queue)
                } else {
                    cur = deque.pollLast();   // DFS: LIFO (stack)
                }

                if (cur == null) return true;
                steps++;
                int r = cur.r, c = cur.c;

                if (mz.state[r][c] != Maze.START && mz.state[r][c] != Maze.EXIT) {
                    mz.state[r][c] = Maze.EXPLORED;
                }

                if (r==mz.exitR && c==mz.exitC) {
                    totalWeight = reconstructPath(parent, cur);
                    found = true;
                    return true;
                }

                for (int[] d : Maze.DIRS4) {
                    int nr = r + d[0], nc = c + d[1];
                    if (!mz.inBounds(nr,nc)) continue;
                    if (mz.state[nr][nc] == Maze.WALL) continue;
                    if (visited[nr][nc]) continue;

                    visited[nr][nc] = true;
                    parent[nr][nc] = cur;

                    // Both add to back (addLast)
                    // BFS: pollFirst + addLast = FIFO ✓
                    // DFS: pollLast + addLast = LIFO ✓
                    deque.addLast(new SimpleNode(nr,nc,cur.d+1));
                }
                return false;

            } else {
                // Dijkstra or A* - unchanged
                while (!pq.isEmpty()) {
                    PQNode top = pq.poll();
                    int r = top.r, c = top.c;

                    if (top.g > dist[r][c] + 1e-9) {
                        continue;
                    }

                    steps++;
                    if (mz.state[r][c] != Maze.START && mz.state[r][c] != Maze.EXIT) {
                        mz.state[r][c] = Maze.EXPLORED;
                    }

                    if (r==mz.exitR && c==mz.exitC) {
                        totalWeight = reconstructPath(dparent, new SimpleNode(r,c,0));
                        found = true;
                        return true;
                    }

                    for (int[] d : Maze.DIRS4) {
                        int nr = r + d[0], nc = c + d[1];
                        if (!mz.inBounds(nr,nc)) continue;
                        if (mz.state[nr][nc] == Maze.WALL) continue;

                        double w = Maze.terrainWeight(mz.terrain[nr][nc]);
                        double tentative = dist[r][c] + w;

                        if (tentative + 1e-9 < dist[nr][nc]) {
                            dist[nr][nc] = tentative;
                            dparent[nr][nc] = new SimpleNode(r,c,0);
                            double h = (type == SolverType.ASTAR) ? heuristic(nr,nc) : 0.0;
                            pq.add(new PQNode(nr,nc, tentative, h));
                        }
                    }
                    return false;
                }
                return true;
            }
        }

        double reconstructPath(SimpleNode[][] parentArr, SimpleNode end) {
            int r = end.r, c = end.c;
            double sum = 0.0;
            int cellCount = 0;

            while (true) {
                cellCount++;
                if (mz.state[r][c] != Maze.START && mz.state[r][c] != Maze.EXIT) {
                    mz.state[r][c] = Maze.SOLUTION;
                }
                sum += Maze.terrainWeight(mz.terrain[r][c]);
                SimpleNode p = parentArr[r][c];
                if (p == null) break;
                r = p.r; c = p.c;
            }

            shortestPathSteps = Math.max(0, cellCount - 1);
            mz.state[mz.startR][mz.startC] = Maze.START;
            mz.state[mz.exitR][mz.exitC] = Maze.EXIT;
            return sum;
        }
    }
}
