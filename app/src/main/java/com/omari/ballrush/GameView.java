// برمجة وتطوير: محمد العمري - 730215825
package com.omari.ballrush;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    // ===== إعدادات يمكن تعديلها لضبط الصعوبة =====
    static final int TOTAL = 100;          // عدد المراحل
    static final int MAXB = 900;           // أقصى عدد كرات ظاهرة
    static final float GOAL_BASE = 1.8f;     // مضاعف الهدف في أول مرحلة
    static final float GOAL_GROW = 7f;     // زيادة المضاعف مع الصعوبة
    static final int[] MULTS = {2, 3, 2, 4, 5, 3, 10, 6, 20, 50, 100, 500};
    // ============================================

    static final float WW = 1000f, WH = 1900f;
    static final float R = 7f, G = 1500f, REST = 0.35f;
    static final float WALL_L = 70f, WALL_R = 930f, BUCKET_TOP = 1640f;
    static final int MENU = 0, LEVELS = 1, PLAY = 2, RESULT = 3;
    static final int GW = 64, GH = 120, CELL = 16;

    static class Gate { float baseX, y, w = 280, h = 56, amp, speed, phase, curX, remaining, flash, popCd; int type, val, idx; boolean alive = true; }
    static class Peg { float x, y; }
    static class Bar { float x, y, len, ang; }
    static class Spinner { float x, y, len, speed, ang; }
    static class Btn { RectF r = new RectF(); String t; int id, color; }
    static class Pop { float x, y, life; String t; int col; }

    final Object lock = new Object();
    Thread thread; volatile boolean running; SurfaceHolder holder;
    SharedPreferences prefs;
    int state = MENU, level = 1, unlocked = 1, pressedId;
    int[] stars = new int[TOTAL + 2];
    int sw = 1, sh = 1; float sc = 1, ox, oy;
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG), bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    Shader bg, pillarL, pillarR, bucketSh;
    RectF rt = new RectF(), rt2 = new RectF(); Path path = new Path();
    int[] cols = {0xFF22E6F0, 0xFFE63CE6, 0xFFF5F03C, 0xFFFFFFFF, 0xFF3CE68C};
    Bitmap[] ballBmp = new Bitmap[5];
    Random rnd = new Random();
    float anim, transT, enterT, resT;
    float ta = 1f;

    float[] bx = new float[MAXB], by = new float[MAXB], bvx = new float[MAXB], bvy = new float[MAXB];
    int[] bw = new int[MAXB]; long[] bm = new long[MAXB]; byte[] bcol = new byte[MAXB]; int n;
    int[] head = new int[GW * GH], nxt = new int[MAXB], cellIdx = new int[MAXB];

    ArrayList<Gate> gates = new ArrayList<>(); ArrayList<Peg> pegs = new ArrayList<>();
    ArrayList<Bar> bars = new ArrayList<>(); ArrayList<Spinner> spins = new ArrayList<>();
    ArrayList<Pop> pops = new ArrayList<>();
    float[] sg = new float[5 * 80]; int sgn;
    int supply, supplyTotal, goal; long collected;
    boolean started, finished, resWin; int resStars;
    float funnelX = 500, targetX = 500, time, emitAcc, endTimer, bucketX = 500, bucketW, bucketAmp, bucketSpeed, bump;

    ArrayList<Btn> btns = new ArrayList<>();
    float tsx, tfx; float scroll, maxScroll, downY, lastY, moved, scrollV; long lastT; boolean dragging;
    float[] decX = new float[40], decP = new float[40], decS = new float[40];

    public GameView(Context ctx) {
        super(ctx);
        holder = getHolder(); holder.addCallback(this);
        prefs = ctx.getSharedPreferences("ballrush", Context.MODE_PRIVATE);
        unlocked = prefs.getInt("unlocked", 1);
        for (int i = 1; i <= TOTAL; i++) stars[i] = prefs.getInt("s" + i, 0);
        tp.setTypeface(Typeface.DEFAULT_BOLD); tp.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 0; i < 40; i++) { decX[i] = rnd.nextFloat(); decP[i] = rnd.nextFloat(); decS[i] = 0.05f + rnd.nextFloat() * 0.12f; }
        makeBalls();
        startLevel(1);
        setState(MENU);
    }

    // ================= أدوات =================
    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
    static int shade(int c) { return mix(c, 0xFF000000, 0.5f); }
    static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    static float easeBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }
    float rf(float a, float b) { return a + rnd.nextFloat() * (b - a); }

    void makeBalls() {
        for (int k = 0; k < 5; k++) {
            Bitmap b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b); Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            int c = cols[k];
            q.setShader(new RadialGradient(18, 16, 34, new int[]{mix(c, 0xFFFFFFFF, 0.6f), c, mix(c, 0xFF000000, 0.5f)},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(24, 24, 23, q);
            q.setShader(new RadialGradient(17, 14, 8, 0xE6FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            cv.drawCircle(17, 14, 8, q);
            ballBmp[k] = b;
        }
    }

    void ball(Canvas c, int k, float x, float y, float r) {
        rt.set(x - r - 1, y - r - 1, x + r + 1, y + r + 1);
        c.drawBitmap(ballBmp[k], null, rt, bp);
    }

    void setState(int s) {
        state = s; transT = anim; pressedId = 0;
        if (s == LEVELS) { enterT = anim; scroll = 0; scrollV = 0; }
        if (s == RESULT) { resT = anim; buildResultButtons(); } else buildMenuButtons();
    }

    // ================= المراحل =================
    void startLevel(int lv) {
        synchronized (lock) {
            level = lv; Random r = new Random(lv * 7919L + 13);
            float diff = Math.min(lv, 60) / 60f;
            supplyTotal = 40 + (int) (lv * 2f); supply = supplyTotal;
            goal = (int) (supplyTotal * (GOAL_BASE + GOAL_GROW * diff));
            gates.clear(); pegs.clear(); bars.clear(); spins.clear(); pops.clear();
            int rows = 3 + Math.min(lv / 5, 4);
            float top = 520, bot = 1450, gap = (bot - top) / (rows - 1);
            int idx = 0;
            for (int row = 0; row < rows; row++) {
                float y = top + row * gap + r.nextInt(41) - 20;
                boolean two = row > 0 && r.nextFloat() < 0.4f + 0.4f * diff;
                boolean prevMinus = false;
                for (int k = 0; k < (two ? 2 : 1); k++) {
                    Gate g = new Gate(); g.idx = idx++; g.y = y;
                    g.baseX = two ? (k == 0 ? 290 + r.nextInt(40) : 710 - r.nextInt(40)) : 250 + r.nextInt(500);
                    boolean minus = row > 0 && !prevMinus && r.nextFloat() < 0.06f + 0.22f * diff;
                    if (minus) {
                        g.type = 2;
                        g.val = Math.max(8, (int) (supplyTotal * (0.25f + row * 0.3f * (0.3f + diff * 1.5f)) * (0.6f + r.nextFloat() * 0.6f)));
                        g.remaining = g.val; prevMinus = true;
                    } else if (row > 0 && r.nextFloat() < 0.2f) {
                        g.type = 1; g.val = 5 + r.nextInt(Math.min(10 + lv, 55));
                    } else {
                        g.type = 0; g.val = MULTS[r.nextInt(Math.min(MULTS.length, 2 + lv / 6))];
                    }
                    g.baseX = Math.max(220, Math.min(780, g.baseX));
                    if (lv >= 8) g.amp = Math.min(r.nextFloat() * Math.min(90f, lv * 3f), Math.min(g.baseX - 220, 780 - g.baseX));
                    g.speed = 0.5f + r.nextFloat() * 0.6f; g.phase = r.nextFloat() * 6.28f;
                    g.curX = g.baseX; gates.add(g);
                }
            }
            if (lv >= 4) for (int i = 0, c = Math.min(1 + lv / 4, 12); i < c; i++) {
                Peg pg = new Peg(); pg.x = 150 + r.nextInt(700); pg.y = 380 + r.nextInt(1100); pegs.add(pg);
            }
            if (lv >= 12) for (int i = 0, c = Math.min(1 + (lv - 12) / 7, 4); i < c; i++) {
                Bar b = new Bar(); b.x = 200 + r.nextInt(600); b.y = 400 + r.nextInt(1100); b.len = 160;
                b.ang = (r.nextBoolean() ? 1 : -1) * (0.4f + r.nextFloat() * 0.4f); bars.add(b);
            }
            if (lv >= 25) for (int i = 0, c = Math.min(1 + (lv - 25) / 12, 3); i < c; i++) {
                Spinner s = new Spinner(); s.x = 250 + r.nextInt(500); s.y = 650 + r.nextInt(750); s.len = 200;
                s.speed = (r.nextBoolean() ? 1 : -1) * (1f + r.nextFloat() * 2f); spins.add(s);
            }
            bucketW = Math.max(300f, 520f - 170f * diff);
            bucketAmp = lv >= 6 ? Math.min(Math.min(200f, lv * 6f), (860f - bucketW) / 2f - 10f) : 0;
            bucketSpeed = 0.4f + diff * 0.8f;
            n = 0; collected = 0; started = false; finished = false; time = 0; emitAcc = 0; endTimer = 0;
            funnelX = targetX = 500; bucketX = 500; bump = 0;
            updateSegs();
            setState(PLAY);
        }
    }

    void addSeg(float x1, float y1, float x2, float y2, float half) {
        int o = sgn * 5; sg[o] = x1; sg[o + 1] = y1; sg[o + 2] = x2; sg[o + 3] = y2; sg[o + 4] = half; sgn++;
    }

    void updateSegs() {
        sgn = 0;
        addSeg(funnelX - 125, 140, funnelX - 34, 250, 10);
        addSeg(funnelX + 125, 140, funnelX + 34, 250, 10);
        for (Bar b : bars) {
            float dx = (float) Math.cos(b.ang) * b.len / 2, dy = (float) Math.sin(b.ang) * b.len / 2;
            addSeg(b.x - dx, b.y - dy, b.x + dx, b.y + dy, 10);
        }
        for (Spinner s : spins) {
            float dx = (float) Math.cos(s.ang) * s.len / 2, dy = (float) Math.sin(s.ang) * s.len / 2;
            addSeg(s.x - dx, s.y - dy, s.x + dx, s.y + dy, 9);
        }
        for (Peg pg : pegs) addSeg(pg.x, pg.y, pg.x, pg.y, 14);
        addSeg(bucketX - bucketW / 2, BUCKET_TOP, bucketX - bucketW / 2, BUCKET_TOP + 60, 9);
        addSeg(bucketX + bucketW / 2, BUCKET_TOP, bucketX + bucketW / 2, BUCKET_TOP + 60, 9);
    }

    // ================= الفيزياء =================
    int spawn(float x, float y, float vx, float vy, int w, long m) {
        int i = n++; bx[i] = x; by[i] = y; bvx[i] = vx; bvy[i] = vy; bw[i] = w; bm[i] = m; bcol[i] = (byte) rnd.nextInt(5); return i;
    }

    void collide(int i, int o) {
        float x1 = sg[o], y1 = sg[o + 1], x2 = sg[o + 2], y2 = sg[o + 3], half = sg[o + 4];
        float ex = x2 - x1, ey = y2 - y1, l2 = ex * ex + ey * ey, px = bx[i], py = by[i];
        float t = l2 > 0 ? ((px - x1) * ex + (py - y1) * ey) / l2 : 0;
        if (t < 0) t = 0; else if (t > 1) t = 1;
        float cx = x1 + t * ex, cy = y1 + t * ey, dx = px - cx, dy = py - cy, d2 = dx * dx + dy * dy, rr = R + half;
        if (d2 < rr * rr) {
            float d = (float) Math.sqrt(d2), nx, ny;
            if (d < 1e-3f) { nx = 0; ny = -1; } else { nx = dx / d; ny = dy / d; }
            bx[i] = cx + nx * rr; by[i] = cy + ny * rr;
            float vn = bvx[i] * nx + bvy[i] * ny;
            if (vn < 0) { bvx[i] -= (1 + REST) * vn * nx; bvy[i] -= (1 + REST) * vn * ny; }
            bvx[i] *= 0.995f;
        }
    }

    // تصادم الكرة مع الكرة (شبكة مكانية)
    void ballCollisions() {
        Arrays.fill(head, -1);
        for (int i = 0; i < n; i++) {
            int cx = (int) (bx[i] / CELL), cy = (int) (by[i] / CELL);
            if (cx < 0) cx = 0; else if (cx >= GW) cx = GW - 1;
            if (cy < 0) cy = 0; else if (cy >= GH) cy = GH - 1;
            int c = cy * GW + cx; cellIdx[i] = c; nxt[i] = head[c]; head[c] = i;
        }
        final float d = 2 * R, d2 = d * d;
        for (int i = 0; i < n; i++) {
            int cx = cellIdx[i] % GW, cy = cellIdx[i] / GW;
            for (int oy2 = -1; oy2 <= 1; oy2++) {
                int yy = cy + oy2; if (yy < 0 || yy >= GH) continue;
                for (int ox2 = -1; ox2 <= 1; ox2++) {
                    int xx = cx + ox2; if (xx < 0 || xx >= GW) continue;
                    for (int j = head[yy * GW + xx]; j >= 0; j = nxt[j]) {
                        if (j <= i) continue;
                        float dx = bx[j] - bx[i], dy = by[j] - by[i], q = dx * dx + dy * dy;
                        if (q >= d2) continue;
                        float dist = (float) Math.sqrt(q), nx, ny;
                        if (dist < 1e-4f) { nx = (i & 1) == 0 ? 1 : -1; ny = 0; dist = 0; } else { nx = dx / dist; ny = dy / dist; }
                        float push = Math.min((d - dist) * 0.5f, 2.5f);
                        bx[i] -= nx * push; by[i] -= ny * push; bx[j] += nx * push; by[j] += ny * push;
                        float rv = (bvx[j] - bvx[i]) * nx + (bvy[j] - bvy[i]) * ny;
                        if (rv < 0) {
                            float imp = -(1f + 0.25f) * rv * 0.5f;
                            bvx[i] -= imp * nx; bvy[i] -= imp * ny; bvx[j] += imp * nx; bvy[j] += imp * ny;
                        }
                    }
                }
            }
        }
    }

    void physics(float h) {
        int n0 = n;
        outer:
        for (int i = 0; i < n0; i++) {
            if (bw[i] <= 0) continue;
            bvy[i] += G * h; bvx[i] *= 0.9995f;
            float sp2 = bvx[i] * bvx[i] + bvy[i] * bvy[i];
            if (sp2 > 1600f * 1600f) { float k = 1600f / (float) Math.sqrt(sp2); bvx[i] *= k; bvy[i] *= k; }
            bx[i] += bvx[i] * h; by[i] += bvy[i] * h;
            if (bx[i] < WALL_L + R) { bx[i] = WALL_L + R; if (bvx[i] < 0) bvx[i] *= -REST; }
            else if (bx[i] > WALL_R - R) { bx[i] = WALL_R - R; if (bvx[i] > 0) bvx[i] *= -REST; }
            for (int s = 0; s < sgn; s++) collide(i, s * 5);
            for (int g = 0; g < gates.size(); g++) {
                Gate gt = gates.get(g);
                if (!gt.alive) continue;
                if (Math.abs(bx[i] - gt.curX) < gt.w / 2 && Math.abs(by[i] - gt.y) < gt.h / 2) {
                    if (gt.type == 2) {
                        int take = (int) Math.min(gt.remaining, bw[i]);
                        gt.remaining -= take; bw[i] -= take; gt.flash = 0.6f;
                        if (gt.remaining <= 0) gt.alive = false;
                        if (bw[i] <= 0) continue outer;
                    } else if (((bm[i] >> gt.idx) & 1L) == 0) {
                        bm[i] |= 1L << gt.idx;
                        gt.flash = 1f;
                        if (gt.popCd <= 0 && pops.size() < 12) {
                            Pop pp = new Pop(); pp.x = gt.curX + rf(-40, 40); pp.y = gt.y - 40; pp.life = 0.9f;
                            pp.t = gt.type == 0 ? "X" + gt.val : "+" + gt.val; pp.col = 0xFFFFF176; pops.add(pp); gt.popCd = 0.3f;
                        }
                        int extra = gt.type == 0 ? gt.val - 1 : gt.val;
                        if (n + extra <= MAXB) {
                            for (int j = 0; j < extra; j++)
                                spawn(bx[i] + rf(-10, 10), by[i] + rf(-6, 6), bvx[i] + rf(-140, 140), bvy[i] + rf(-40, 80), bw[i], bm[i]);
                        } else if (gt.type == 0) bw[i] = (int) Math.min(100000000L, (long) bw[i] * gt.val);
                        else bw[i] = (int) Math.min(100000000L, (long) bw[i] + gt.val);
                    }
                }
            }
            if (by[i] > BUCKET_TOP + 30 && Math.abs(bx[i] - bucketX) < bucketW / 2) {
                collected += bw[i]; bw[i] = 0; bump = 1f;
            } else if (by[i] > WH + 60) bw[i] = 0;
        }
        int k = 0;
        for (int i = 0; i < n; i++) if (bw[i] > 0) {
            if (k != i) { bx[k] = bx[i]; by[k] = by[i]; bvx[k] = bvx[i]; bvy[k] = bvy[i]; bw[k] = bw[i]; bm[k] = bm[i]; bcol[k] = bcol[i]; }
            k++;
        }
        n = k;
        ballCollisions();
    }

    void update(float dt) {
        time += dt;
        funnelX += (targetX - funnelX) * Math.min(1f, dt * 14f);
        for (Gate g : gates) {
            g.curX = g.baseX + g.amp * (float) Math.sin(time * g.speed + g.phase);
            g.flash = Math.max(0, g.flash - dt * 3f); g.popCd -= dt;
        }
        for (Spinner s : spins) s.ang += s.speed * dt;
        for (int i = pops.size() - 1; i >= 0; i--) { Pop pp = pops.get(i); pp.life -= dt; pp.y -= 90 * dt; if (pp.life <= 0) pops.remove(i); }
        bucketX = 500 + bucketAmp * (float) Math.sin(time * bucketSpeed);
        bump = Math.max(0, bump - dt * 4f);
        updateSegs();
        if (!started || finished) return;
        if (supply > 0) {
            emitAcc = Math.min(emitAcc + dt * 55f, 3f);
            while (emitAcc >= 1f && supply > 0 && n < MAXB) {
                spawn(funnelX + rf(-55, 55), rf(145, 175), rf(-20, 20), 40, 1, 0L);
                supply--; emitAcc -= 1f;
            }
        } else endTimer += dt;
        float h = dt / 3f;
        for (int s = 0; s < 3; s++) { physics(h); updateSegs(); }
        if (endTimer > 10f) n = 0;
        if (supply == 0 && n == 0) finishLevel();
    }

    void finishLevel() {
        finished = true; resWin = collected >= goal;
        double r = (double) collected / goal;
        resStars = !resWin ? 0 : (r >= 2.5 ? 3 : (r >= 1.6 ? 2 : 1));
        if (resWin) {
            if (resStars > stars[level]) stars[level] = resStars;
            if (level < TOTAL && level + 1 > unlocked) unlocked = level + 1;
            SharedPreferences.Editor e = prefs.edit();
            e.putInt("unlocked", unlocked); e.putInt("s" + level, stars[level]); e.apply();
        }
        setState(RESULT);
    }

    // ================= الواجهة والأزرار =================
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        synchronized (lock) {
            sw = w; sh = h; sc = Math.min(w / WW, h / WH); ox = (w - WW * sc) / 2; oy = (h - WH * sc) / 2;
            bg = new LinearGradient(0, 0, 0, h, 0xFF5CB0FF, 0xFF2A5FD8, Shader.TileMode.CLAMP);
            pillarL = new LinearGradient(0, 0, WALL_L, 0, 0xFFE58CFF, 0xFF8E2FB0, Shader.TileMode.CLAMP);
            pillarR = new LinearGradient(WALL_R, 0, WW, 0, 0xFF8E2FB0, 0xFFE58CFF, Shader.TileMode.CLAMP);
            bucketSh = new LinearGradient(0, BUCKET_TOP, 0, 1870, 0xFFD97CF5, 0xFF5A1A70, Shader.TileMode.CLAMP);
            buildMenuButtons();
        }
    }

    Btn mk(String t, int id, int color, float cx, float cy, float w, float h) {
        Btn b = new Btn(); b.t = t; b.id = id; b.color = color; b.r.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2); return b;
    }

    void buildMenuButtons() {
        btns.clear();
        if (state == MENU) {
            btns.add(mk("ابدأ اللعب", 1, 0xFF2EC24A, sw / 2f, sh * 0.55f, sw * 0.7f, sh * 0.085f));
            btns.add(mk("المراحل", 2, 0xFFF5A623, sw / 2f, sh * 0.66f, sw * 0.7f, sh * 0.085f));
        } else if (state == LEVELS) {
            btns.add(mk("رجوع", 6, 0xFFE53935, sw * 0.17f, sh * 0.055f, sw * 0.26f, sh * 0.055f));
        }
    }

    void buildResultButtons() {
        btns.clear();
        float cy = sh * 0.57f, w = sw * 0.62f, h = sh * 0.062f;
        if (resWin && level < TOTAL) {
            btns.add(mk("المرحلة التالية", 3, 0xFF2EC24A, sw / 2f, cy, w, h));
            btns.add(mk("إعادة", 4, 0xFFF5A623, sw / 2f, cy + h * 1.3f, w, h));
            btns.add(mk("القائمة", 5, 0xFF8E2FB0, sw / 2f, cy + h * 2.6f, w, h));
        } else {
            btns.add(mk("إعادة", 4, 0xFFF5A623, sw / 2f, cy, w, h));
            btns.add(mk("القائمة", 5, 0xFF8E2FB0, sw / 2f, cy + h * 1.3f, w, h));
        }
    }

    void action(int id) {
        if (id == 1) startLevel(unlocked);
        else if (id == 2) setState(LEVELS);
        else if (id == 3) startLevel(level + 1);
        else if (id == 4) startLevel(level);
        else if (id == 5 || id == 6) setState(MENU);
    }

    public boolean onBack() {
        synchronized (lock) {
            if (state == MENU) return false;
            if (state == PLAY || state == RESULT) setState(LEVELS); else setState(MENU);
            return true;
        }
    }

    float cell() { return sw * 0.92f / 5f; }

    int hit(float x, float y) { for (Btn b : btns) if (b.r.contains(x, y)) return b.id; return 0; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        synchronized (lock) {
            float x = e.getX(), y = e.getY(); int a = e.getActionMasked();
            if (state == PLAY) {
                if (a == MotionEvent.ACTION_DOWN) {
                    float wx = (x - ox) / sc, wy = (y - oy) / sc;
                    if (Math.hypot(wx - 60, wy - 56) < 46) { setState(LEVELS); return true; }
                    started = true; tsx = x; tfx = targetX;
                }
                if (a == MotionEvent.ACTION_MOVE)
                    targetX = Math.max(150f, Math.min(850f, tfx + (x - tsx) / sc * 1.7f));
                return true;
            }
            if (a == MotionEvent.ACTION_DOWN) pressedId = hit(x, y);
            if (a == MotionEvent.ACTION_CANCEL) pressedId = 0;
            if (state == LEVELS) {
                if (a == MotionEvent.ACTION_DOWN) { downY = lastY = y; moved = 0; dragging = true; scrollV = 0; lastT = e.getEventTime(); }
                else if (a == MotionEvent.ACTION_MOVE) {
                    float dy = y - lastY;
                    scroll = Math.max(0, Math.min(maxScroll, scroll - dy));
                    moved += Math.abs(dy);
                    float dtm = (e.getEventTime() - lastT) / 1000f;
                    if (dtm > 0.001f) scrollV = scrollV * 0.6f + 0.4f * (-dy / dtm);
                    lastY = y; lastT = e.getEventTime();
                } else if (a == MotionEvent.ACTION_UP) {
                    dragging = false; pressedId = 0;
                    if (moved < 25) {
                        scrollV = 0;
                        int id = hit(x, y);
                        if (id != 0) { action(id); return true; }
                        float cs = cell(), x0 = (sw - cs * 5) / 2, gy0 = sh * 0.13f;
                        if (y > gy0) {
                            int c = (int) ((x - x0) / cs), r = (int) ((y - gy0 + scroll) / cs), lv = r * 5 + c + 1;
                            if (c >= 0 && c < 5 && lv >= 1 && lv <= TOTAL && lv <= unlocked) startLevel(lv);
                        }
                    }
                } else if (a == MotionEvent.ACTION_CANCEL) dragging = false;
                return true;
            }
            if (a == MotionEvent.ACTION_UP) {
                int id = hit(x, y); pressedId = 0;
                if (id != 0) action(id);
            }
            return true;
        }
    }

    // ================= الرسم: أدوات =================
    void txt(Canvas c, String s, float x, float y, float size, int col, boolean outline) {
        tp.setTextSize(size); tp.setTextAlign(Paint.Align.CENTER);
        if (outline) {
            tp.setStyle(Paint.Style.STROKE); tp.setStrokeWidth(size * 0.16f); tp.setColor(((int) (0xCC * ta) << 24) | 0x1A0A3A);
            c.drawText(s, x, y, tp);
        }
        tp.setStyle(Paint.Style.FILL); tp.setColor(((int) ((col >>> 24) * ta) << 24) | (col & 0xFFFFFF)); c.drawText(s, x, y, tp);
    }

    void line3d(Canvas c, float x1, float y1, float x2, float y2, float w, int col) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(w + 4); p.setColor(shade(col)); c.drawLine(x1, y1 + w * 0.22f, x2, y2 + w * 0.22f, p);
        p.setStrokeWidth(w); p.setColor(col); c.drawLine(x1, y1, x2, y2, p);
        p.setStrokeWidth(w * 0.28f); p.setColor(mix(col, 0xFFFFFFFF, 0.55f)); c.drawLine(x1, y1 - w * 0.18f, x2, y2 - w * 0.18f, p);
        p.setStyle(Paint.Style.FILL);
    }

    void drawStar(Canvas c, float cx, float cy, float r, boolean on) {
        path.reset();
        for (int i = 0; i < 10; i++) {
            double ang = -Math.PI / 2 + i * Math.PI / 5; float rad = (i % 2 == 0) ? r : r * 0.45f;
            float px = cx + (float) Math.cos(ang) * rad, py = cy + (float) Math.sin(ang) * rad;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        p.setStyle(Paint.Style.FILL); p.setColor(on ? 0xFFFFC107 : 0xFF5A4A70); c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, r * 0.12f)); p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(on ? 0xFFFF8F00 : 0xFF3A2A50); c.drawPath(path, p);
        if (on) { p.setStyle(Paint.Style.FILL); p.setColor(0x66FFFFFF); c.drawCircle(cx - r * 0.25f, cy - r * 0.25f, r * 0.18f, p); }
        p.setStyle(Paint.Style.FILL);
    }

    void drawBtn(Canvas c, Btn b) {
        float s = (pressedId == b.id ? 0.94f : 1f) * (b.id == 1 && state == MENU ? 1f + 0.03f * (float) Math.sin(anim * 4) : 1f);
        c.save(); c.scale(s, s, b.r.centerX(), b.r.centerY());
        p.setStyle(Paint.Style.FILL);
        rt2.set(b.r); rt2.offset(0, 9); p.setColor(shade(b.color)); c.drawRoundRect(rt2, 34, 34, p);
        p.setShader(new LinearGradient(0, b.r.top, 0, b.r.bottom, mix(b.color, 0xFFFFFFFF, 0.35f), b.color, Shader.TileMode.CLAMP));
        c.drawRoundRect(b.r, 34, 34, p); p.setShader(null);
        rt2.set(b.r.left + 14, b.r.top + 6, b.r.right - 14, b.r.centerY() - 2); p.setColor(0x38FFFFFF); c.drawRoundRect(rt2, 24, 24, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(0x77FFFFFF); c.drawRoundRect(b.r, 34, 34, p); p.setStyle(Paint.Style.FILL);
        txt(c, b.t, b.r.centerX(), b.r.centerY() + b.r.height() * 0.16f, b.r.height() * 0.46f, 0xFFFFFFFF, true);
        c.restore();
    }

    void drawBg(Canvas c, boolean decor) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(bg); c.drawRect(0, 0, sw, sh, p); p.setShader(null);
        // بقع ضوء متحركة
        for (int i = 0; i < 9; i++) {
            float x = decX[i] * sw + (float) Math.sin(anim * 0.3f + i) * 40;
            float y = ((decP[i] + anim * 0.012f * (1 + i % 3)) % 1f) * (sh + 300) - 150;
            p.setColor(0x16FFFFFF); c.drawCircle(x, y, sw * (0.10f + (i % 3) * 0.07f), p);
        }
        if (decor) {
            bp.setAlpha(210);
            for (int i = 0; i < 40; i++) {
                float yy = ((decP[i] + anim * decS[i]) % 1f) * (sh + 80) - 40;
                float xx = decX[i] * sw + (float) Math.sin(anim * 0.8f + i) * sw * 0.02f;
                ball(c, i % 5, xx, yy, sw * (0.014f + (i % 4) * 0.006f));
            }
            bp.setAlpha(255);
        }
    }

    void render(Canvas c) {
        if (state == MENU) { drawBg(c, true); drawMenu(c); }
        else if (state == LEVELS) { drawBg(c, true); drawLevels(c); }
        else { drawBg(c, false); drawGame(c); if (state == RESULT) drawResult(c); }
        float a = 1f - (anim - transT) / 0.35f;
        if (a > 0) { p.setStyle(Paint.Style.FILL); p.setColor((int) (clamp01(a) * 255) << 24); c.drawRect(0, 0, sw, sh, p); }
    }

    // ================= القائمة الرئيسية =================
    void drawMenu(Canvas c) {
        // عنوان متحرك
        c.save(); c.translate(sw / 2f, sh * 0.15f);
        c.rotate((float) Math.sin(anim * 1.4f) * 2.2f);
        float pulse = 1f + 0.035f * (float) Math.sin(anim * 3f); c.scale(pulse, pulse);
        float ts = sw * 0.15f;
        tp.setTextSize(ts); tp.setTextAlign(Paint.Align.CENTER);
        tp.setStyle(Paint.Style.STROKE); tp.setStrokeWidth(ts * 0.22f); tp.setColor(0xFF3A0F5E);
        c.drawText("اجمع الكرات", 0, ts * 0.05f + 10, tp);
        tp.setColor(0xFF6A1FA0); c.drawText("اجمع الكرات", 0, 0, tp);
        tp.setStyle(Paint.Style.FILL);
        tp.setShader(new LinearGradient(0, -ts, 0, 0, 0xFFFFF59D, 0xFFFF9800, Shader.TileMode.CLAMP));
        c.drawText("اجمع الكرات", 0, 0, tp); tp.setShader(null);
        c.restore();
        txt(c, "BALL  RUSH", sw / 2f, sh * 0.215f, sw * 0.075f, 0xFFFFFFFF, true);

        // مشهد مصغر: قمع + بوابة + سلة وكرات تتساقط
        float cx = sw / 2f, y0 = sh * 0.255f, y1 = sh * 0.465f, fw = sw * 0.26f;
        line3d(c, cx - fw, y0, cx - sw * 0.045f, y0 + sh * 0.045f, sw * 0.022f, 0xFFC15BE8);
        line3d(c, cx + fw, y0, cx + sw * 0.045f, y0 + sh * 0.045f, sw * 0.022f, 0xFFC15BE8);
        float gx = cx + (float) Math.sin(anim * 1.3f) * sw * 0.12f, gy = (y0 + y1) / 2f;
        rt.set(gx - sw * 0.11f, gy - sh * 0.017f, gx + sw * 0.11f, gy + sh * 0.017f);
        rt2.set(rt); rt2.offset(0, 6); p.setColor(0xFF9A5A00); c.drawRoundRect(rt2, 14, 14, p);
        p.setColor(0xFFF5A623); c.drawRoundRect(rt, 14, 14, p);
        txt(c, "X5", gx, gy + sh * 0.012f, sh * 0.03f, 0xFFFFFFFF, true);
        for (int i = 0; i < 22; i++) {
            float t = (anim * 0.55f + i / 22f) % 1f;
            float yy = y0 + sh * 0.03f + t * (y1 - y0 - sh * 0.03f);
            float xx = cx + (float) Math.sin(t * 7f + i * 1.7f) * sw * (0.035f + 0.02f * t);
            ball(c, i % 5, xx, yy, sw * 0.017f);
        }
        rt.set(cx - sw * 0.17f, y1, cx + sw * 0.17f, y1 + sh * 0.07f);
        p.setShader(new LinearGradient(0, rt.top, 0, rt.bottom, 0xFFD97CF5, 0xFF5A1A70, Shader.TileMode.CLAMP));
        c.drawRoundRect(rt, 36, 36, p); p.setShader(null);
        p.setColor(0xFF3E1050); rt2.set(rt.left + 10, rt.top + 4, rt.right - 10, rt.top + 22); c.drawOval(rt2, p);

        for (Btn b : btns) drawBtn(c, b);
        txt(c, "المرحلة المتاحة: " + unlocked + " / " + TOTAL, sw / 2f, sh * 0.755f, sw * 0.05f, 0xFFFFFFFF, true);
        int tot = 0; for (int i = 1; i <= TOTAL; i++) tot += stars[i];
        drawStar(c, sw / 2f - sw * 0.09f, sh * 0.795f, sw * 0.03f, true);
        txt(c, tot + " / " + (TOTAL * 3), sw / 2f + sw * 0.04f, sh * 0.805f, sw * 0.045f, 0xFFFFE082, true);

        rt.set(sw * 0.1f, sh * 0.865f, sw * 0.9f, sh * 0.955f);
        p.setColor(0x55000000); c.drawRoundRect(rt, 30, 30, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(0x55FFFFFF); c.drawRoundRect(rt, 30, 30, p); p.setStyle(Paint.Style.FILL);
        txt(c, "برمجة وتطوير: محمد العمري", sw / 2f, sh * 0.907f, sw * 0.055f, 0xFFFFFFFF, true);
        txt(c, "730215825", sw / 2f, sh * 0.945f, sw * 0.055f, 0xFFF5F03C, true);
    }

    // ================= شاشة المراحل =================
    void drawLevels(Canvas c) {
        float cs = cell(), x0 = (sw - cs * 5) / 2, gy0 = sh * 0.13f;
        maxScroll = Math.max(0, (TOTAL / 5) * cs - (sh - gy0) + 30);
        if (scroll > maxScroll) scroll = maxScroll;
        c.save(); c.clipRect(0, gy0, sw, sh);
        for (int lv = 1; lv <= TOTAL; lv++) {
            int r = (lv - 1) / 5, col = (lv - 1) % 5;
            float l = x0 + col * cs + cs * 0.06f, t = gy0 + 10 + r * cs - scroll + cs * 0.06f, s = cs * 0.88f;
            if (t > sh || t + s < gy0) continue;
            float tt = clamp01((anim - enterT - 0.035f * (r + col * 0.5f)) / 0.35f);
            if (tt <= 0) continue;
            float sc2 = easeBack(tt);
            rt.set(l, t, l + s, t + s);
            c.save(); c.scale(sc2, sc2, rt.centerX(), rt.centerY());
            boolean open = lv <= unlocked, cur = lv == unlocked;
            int base = !open ? 0xFF6B7A99 : (cur ? 0xFF2EC24A : (stars[lv] > 0 ? 0xFFB347D9 : 0xFF9B3FC4));
            if (cur) { float g = 6 + 5 * (float) Math.sin(anim * 5); rt2.set(rt); rt2.inset(-g, -g); p.setColor(0x55FFFFFF); c.drawRoundRect(rt2, 28, 28, p); }
            rt2.set(rt); rt2.offset(0, 8); p.setColor(shade(base)); c.drawRoundRect(rt2, 24, 24, p);
            p.setShader(new LinearGradient(0, rt.top, 0, rt.bottom, mix(base, 0xFFFFFFFF, 0.35f), base, Shader.TileMode.CLAMP));
            c.drawRoundRect(rt, 24, 24, p); p.setShader(null);
            rt2.set(rt.left + 8, rt.top + 6, rt.right - 8, rt.centerY() - 4); p.setColor(0x33FFFFFF); c.drawRoundRect(rt2, 18, 18, p);
            if (open) {
                txt(c, "" + lv, rt.centerX(), rt.centerY() - s * 0.02f, s * 0.36f, 0xFFFFFFFF, true);
                for (int k = 0; k < 3; k++) drawStar(c, rt.centerX() + (k - 1) * s * 0.25f, rt.bottom - s * 0.17f, s * 0.1f, k < stars[lv]);
            } else {
                txt(c, "" + lv, rt.centerX(), rt.top + s * 0.3f, s * 0.24f, 0xFFDDE5F5, false);
                float cx = rt.centerX(), cy = rt.centerY() + s * 0.12f;
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s * 0.055f); p.setColor(0xFFCFD8E8);
                rt2.set(cx - s * 0.09f, cy - s * 0.2f, cx + s * 0.09f, cy + s * 0.04f); c.drawArc(rt2, 180, 180, false, p);
                p.setStyle(Paint.Style.FILL); p.setColor(0xFFE8EEF8);
                rt2.set(cx - s * 0.15f, cy - s * 0.04f, cx + s * 0.15f, cy + s * 0.2f); c.drawRoundRect(rt2, 8, 8, p);
                p.setColor(0xFF6B7A99); c.drawCircle(cx, cy + s * 0.07f, s * 0.03f, p);
            }
            c.restore();
        }
        c.restore();
        // الشريط العلوي
        rt.set(0, 0, sw, gy0 - 4);
        p.setShader(new LinearGradient(0, 0, 0, rt.bottom, 0xFF9B3FC4, 0xFF5A1A70, Shader.TileMode.CLAMP)); c.drawRect(rt, p); p.setShader(null);
        p.setColor(0x44000000); c.drawRect(0, rt.bottom, sw, rt.bottom + 8, p);
        txt(c, "اختر المرحلة", sw * 0.6f, sh * 0.075f, sw * 0.07f, 0xFFFFFFFF, true);
        for (Btn b : btns) drawBtn(c, b);
    }

    // ================= اللعب =================
    void drawGame(Canvas c) {
        c.save(); c.translate(ox, oy); c.scale(sc, sc);
        p.setStyle(Paint.Style.FILL);
        p.setShader(pillarL); c.drawRect(-2000, 112, WALL_L, 6000, p);
        p.setShader(pillarR); c.drawRect(WALL_R, 112, 3000, 6000, p); p.setShader(null);
        p.setColor(0x55000000); c.drawRect(WALL_L, 112, WALL_L + 14, 6000, p); c.drawRect(WALL_R - 14, 112, WALL_R, 6000, p);
        // خط التصويب
        if (!finished) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(6); p.setColor(0x66FFFFFF);
            p.setPathEffect(new android.graphics.DashPathEffect(new float[]{20, 18}, anim * 80f));
            c.drawLine(funnelX, 270, funnelX, BUCKET_TOP - 10, p);
            p.setPathEffect(null); p.setStyle(Paint.Style.FILL);
        }
        // العوائق
        for (int s = 0; s < sgn; s++) {
            int o = s * 5;
            if (sg[o] == sg[o + 2] && sg[o + 1] == sg[o + 3]) {
                float x = sg[o], y = sg[o + 1], r = sg[o + 4];
                p.setColor(0x44000000); c.drawCircle(x + 3, y + 6, r, p);
                p.setShader(new RadialGradient(x - r * 0.3f, y - r * 0.35f, r * 1.4f, 0xFFF3B4FF, 0xFF8E2FB0, Shader.TileMode.CLAMP));
                c.drawCircle(x, y, r, p); p.setShader(null);
            } else line3d(c, sg[o], sg[o + 1], sg[o + 2], sg[o + 3], sg[o + 4] * 2, 0xFFC15BE8);
        }
        // السلة
        RectF bk = new RectF(bucketX - bucketW / 2, BUCKET_TOP, bucketX + bucketW / 2, 1870);
        p.setShader(bucketSh); c.drawRoundRect(bk, 50, 50, p); p.setShader(null);
        p.setColor(0xFF3E1050); rt.set(bk.left + 14, bk.top - 4, bk.right - 14, bk.top + 40); c.drawOval(rt, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(8); p.setColor(0xFFF0A8FF); c.drawOval(rt, p); p.setStyle(Paint.Style.FILL);
        txt(c, "" + collected, bucketX, 1790, 60 + bump * 24, 0xFFFFFFFF, true);
        // البوابات
        for (Gate g : gates) {
            if (!g.alive) continue;
            int col = g.type == 2 ? 0xFFE53935 : g.type == 1 ? 0xFF00BCD4 : (g.val >= 10 ? 0xFF2EE62E : 0xFFF5A623);
            float k = 1f + 0.18f * g.flash;
            c.save(); c.translate(g.curX, g.y); c.scale(k, k);
            if (g.flash > 0) { p.setColor((int) (g.flash * 90) << 24 | 0xFFFFFF); rt.set(-g.w / 2 - 14, -g.h / 2 - 14, g.w / 2 + 14, g.h / 2 + 14); c.drawRoundRect(rt, 22, 22, p); }
            rt.set(-g.w / 2, -g.h / 2, g.w / 2, g.h / 2);
            rt2.set(rt); rt2.offset(0, 11); p.setColor(shade(col)); c.drawRoundRect(rt2, 12, 12, p);
            p.setShader(new LinearGradient(0, -g.h / 2, 0, g.h / 2, mix(col, 0xFFFFFFFF, 0.3f), col, Shader.TileMode.CLAMP));
            c.drawRoundRect(rt, 12, 12, p); p.setShader(null);
            rt2.set(-g.w / 2 + 8, -g.h / 2 + 5, g.w / 2 - 8, -2); p.setColor(0x44FFFFFF); c.drawRoundRect(rt2, 8, 8, p);
            String t = g.type == 0 ? "X" + g.val : g.type == 1 ? "+" + g.val : "-" + (int) g.remaining;
            txt(c, t, 0, 16, 46, 0xFFFFFFFF, true);
            c.restore();
        }
        // الكرات
        for (int i = 0; i < n; i++) {
            float rr = bw[i] > 1 ? R * (1f + 0.35f * (float) Math.log10(bw[i])) : R;
            ball(c, bcol[i], bx[i], by[i], rr);
        }
        // مؤثرات النقاط
        for (Pop pp : pops) { ta = clamp01(pp.life / 0.5f); txt(c, pp.t, pp.x, pp.y, 52, pp.col, true); }
        ta = 1f;
        if (!finished) txt(c, "" + supply, funnelX, 215, 34, 0xFFFFFFFF, true);
        // الشريط العلوي
        p.setShader(new LinearGradient(0, 0, 0, 112, 0xFFDCC0F5, 0xFFB48ADB, Shader.TileMode.CLAMP));
        c.drawRect(-3000, -3000, 4000, 112, p); p.setShader(null);
        p.setColor(0x33000000); c.drawRect(-3000, 112, 4000, 124, p);
        txt(c, "اجمع " + goal + " كرة", 500, 50, 46, 0xFFFFFFFF, true);
        p.setColor(0xFF4A2670); rt.set(150, 64, 850, 94); c.drawRoundRect(rt, 15, 15, p);
        float pr = Math.min(1f, (float) collected / goal);
        if (pr > 0) {
            rt.set(150, 64, 150 + 700 * pr, 94);
            p.setShader(new LinearGradient(0, 64, 0, 94, 0xFF9CFF7A, 0xFF1EA832, Shader.TileMode.CLAMP)); c.drawRoundRect(rt, 15, 15, p); p.setShader(null);
        }
        txt(c, collected + " / " + goal, 500, 88, 22, 0xFFFFFFFF, true);
        p.setColor(0xFF6A1FA0); c.drawCircle(60, 56, 38, p);
        p.setColor(0xFF8E2FB0); c.drawCircle(60, 53, 36, p);
        p.setColor(0xFFFFFFFF); rt.set(46, 38, 55, 70); c.drawRoundRect(rt, 3, 3, p); rt.set(65, 38, 74, 70); c.drawRoundRect(rt, 3, 3, p);
        txt(c, "م " + level, 930, 60, 38, 0xFF4A1A70, false);
        if (!started) {
            float a = 0.6f + 0.4f * (float) Math.sin(anim * 4);
            ta = a; txt(c, "اسحب يمينًا ويسارًا لتحريك القمع", 500, 1000, 46, 0xFFFFFFFF, true); ta = 1f;
        }
        c.restore();
    }

    // ================= شاشة النتيجة =================
    void drawResult(Canvas c) {
        float rt0 = anim - resT;
        p.setStyle(Paint.Style.FILL); p.setColor((int) (clamp01(rt0 / 0.3f) * 0xAA) << 24); c.drawRect(0, 0, sw, sh, p);
        if (resWin) {
            for (int i = 0; i < 60; i++) {
                float yy = ((i * 0.071f + rt0 * (0.22f + (i % 5) * 0.05f)) % 1f) * (sh + 60) - 30;
                float xx = ((i * 0.137f) % 1f) * sw + (float) Math.sin(rt0 * 3 + i) * 20;
                c.save(); c.rotate(rt0 * 200 + i * 37, xx, yy);
                p.setColor(cols[i % 5]); rt.set(xx - 8, yy - 4, xx + 8, yy + 4); c.drawRect(rt, p); c.restore();
            }
        }
        float s = easeBack(clamp01(rt0 / 0.45f));
        c.save(); c.scale(s, s, sw / 2f, sh / 2f);
        RectF pn = new RectF(sw * 0.09f, sh * 0.2f, sw * 0.91f, sh * 0.79f);
        rt2.set(pn); rt2.offset(0, 12); p.setColor(0xFF2A0A3A); c.drawRoundRect(rt2, 46, 46, p);
        p.setShader(new LinearGradient(0, pn.top, 0, pn.bottom, 0xFF8E3FB8, 0xFF4A1568, Shader.TileMode.CLAMP)); c.drawRoundRect(pn, 46, 46, p); p.setShader(null);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(5); p.setColor(0x88FFFFFF); c.drawRoundRect(pn, 46, 46, p); p.setStyle(Paint.Style.FILL);
        txt(c, resWin ? "أحسنت!" : "حاول مرة أخرى", sw / 2f, sh * 0.29f, sw * 0.11f, resWin ? 0xFF7CFF6B : 0xFFFF8A80, true);
        for (int k = 0; k < 3; k++) {
            float sx = sw / 2f + (k - 1) * sw * 0.22f, sy = sh * 0.385f - (k == 1 ? sh * 0.012f : 0), r = sw * 0.085f;
            drawStar(c, sx, sy, r, false);
            if (k < resStars) {
                float tk = clamp01((rt0 - 0.4f - 0.25f * k) / 0.3f);
                if (tk > 0) drawStar(c, sx, sy, r * easeBack(tk), true);
            }
        }
        txt(c, collected + " / " + goal, sw / 2f, sh * 0.485f, sw * 0.07f, 0xFFFFFFFF, true);
        for (Btn b : btns) drawBtn(c, b);
        txt(c, "برمجة وتطوير: محمد العمري - 730215825", sw / 2f, sh * 0.765f, sw * 0.04f, 0xFFFFFFFF, false);
        c.restore();
    }

    // ================= الخيط =================
    @Override public void surfaceCreated(SurfaceHolder h) { running = true; thread = new Thread(this); thread.start(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) {
        running = false;
        try { if (thread != null) thread.join(); } catch (InterruptedException ignored) { }
    }

    @Override public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime(); float dt = Math.min(0.033f, (now - last) / 1e9f); last = now;
            Canvas c = null;
            try {
                c = Build.VERSION.SDK_INT >= 26 ? holder.lockHardwareCanvas() : holder.lockCanvas();
                if (c != null) synchronized (lock) {
                    anim += dt;
                    if (state == PLAY) update(dt);
                    else if (state == LEVELS && !dragging && Math.abs(scrollV) > 5f) {
                        scroll += scrollV * dt; scrollV *= (float) Math.pow(0.05, dt);
                        if (scroll < 0) { scroll = 0; scrollV = 0; } else if (scroll > maxScroll) { scroll = maxScroll; scrollV = 0; }
                    }
                    render(c);
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) { }
            }
            long spent = (System.nanoTime() - now) / 1000000L;
            if (spent < 16) try { Thread.sleep(16 - spent); } catch (InterruptedException ignored) { }
        }
    }
}
