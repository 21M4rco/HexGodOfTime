package com.loki.client;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;

public final class TemporalCloth {
   private static final int ROWS = 14;
   private static final int COLS = 7;
   private static final double TOP_Y = 0.03;
   private static final double TOP_Z = 0.25;
   private static final double LENGTH = 1.85;
   private static final double GRAVITY = 0.034;
   private static final double DAMPING = 0.955;
   private static final double FEET = 1.5;
   private final double[][] x = new double[14][7];
   private final double[][] y = new double[14][7];
   private final double[][] z = new double[14][7];
   private final double[][] px = new double[14][7];
   private final double[][] py = new double[14][7];
   private final double[][] pz = new double[14][7];
   private final double[][] rx = new double[14][7];
   private final double[][] ry = new double[14][7];
   private final double[][] rz = new double[14][7];
   private boolean ready;
   private Vec3 lastPosition;
   private long tick = Long.MIN_VALUE;
   private float yaw;
   private double gust;
   private double floor = Double.MAX_VALUE;

   private static double t(int r) {
      return (double)r / 13.0;
   }

   private static double u(int c) {
      return (double)c / 6.0 * 2.0 - 1.0;
   }

   private static double halfWidth(double t) {
      return 0.46 + 0.35 * t;
   }

   private static double bodyZ(double t) {
      return 0.23 + 0.12 * Math.min(1.0, t * 2.2);
   }

   private void reset(float bodyYaw) {
      for (int r = 0; r < 14; r++) {
         for (int c = 0; c < 7; c++) {
            this.x[r][c] = u(c) * halfWidth(t(r));
            this.y[r][c] = 0.03 + 1.85 * t(r);
            this.z[r][c] = 0.25 + 0.18 * t(r);
            this.px[r][c] = this.rx[r][c] = this.x[r][c];
            this.py[r][c] = this.ry[r][c] = this.y[r][c];
            this.pz[r][c] = this.rz[r][c] = this.z[r][c];
         }
      }

      this.yaw = bodyYaw;
      this.ready = true;
   }

   public void tick(AbstractClientPlayer p) {
      if (this.tick != (long)p.tickCount) {
         this.tick = (long)p.tickCount;
         if (!this.ready || lastPosition == null || p.position().distanceToSqr(lastPosition) > 16) {
            this.reset(p.yBodyRot);
         }

         for (int r = 0; r < 14; r++) {
            for (int c = 0; c < 7; c++) {
               this.rx[r][c] = this.x[r][c];
               this.ry[r][c] = this.y[r][c];
               this.rz[r][c] = this.z[r][c];
            }
         }

         Vec3 v = lastPosition == null ? Vec3.ZERO : p.position().subtract(lastPosition);
         lastPosition = p.position();
         double rad = Math.toRadians((double)p.yBodyRot);
         double forward = -v.x * Math.sin(rad) + v.z * Math.cos(rad);
         double side = v.x * Math.cos(rad) + v.z * Math.sin(rad);
         double turn = (double)Mth.clamp(Mth.wrapDegrees(p.yBodyRot - this.yaw), -40.0F, 40.0F);
         this.yaw = p.yBodyRot;
         this.gust = this.gust * 0.9 + (p.getRandom().nextDouble() - 0.5) * 0.02;
         BlockHitResult hit = p.level().clip(new ClipContext(p.position(), p.position().add(0.0, -4.0, 0.0), Block.COLLIDER, Fluid.NONE, p));
         this.floor = hit.getType() == Type.MISS
            ? Double.MAX_VALUE
            : 1.5 + (p.getY() - hit.getLocation().y) / Math.max(0.5, 1.0) - 0.04;
         double windZ = Math.max(0.0, forward) * 0.4 + Math.abs(side) * 0.08 + 0.004 + this.gust * 0.5;
         double windX = side * 0.35 + turn * 0.0022;
         double windY = v.y * 0.3;
         double breeze = Math.sin((double)p.tickCount * 0.07) * 0.0035;

         for (int r = 1; r < 14; r++) {
            for (int c = 0; c < 7; c++) {
               double loose = 0.35 + 0.65 * t(r);
               double ax = (windX + breeze * (1.0 - Math.abs(u(c)) * 0.5) + Math.sin((double)p.tickCount * 0.11 + (double)c * 1.3 + (double)r * 0.4) * 0.0012)
                  * loose;
               double ay = 0.034 + windY * loose;
               double az = windZ * loose + Math.cos((double)p.tickCount * 0.09 + (double)r * 0.5) * 9.0E-4 * loose;
               double nx = this.x[r][c] + (this.x[r][c] - this.px[r][c]) * 0.955 + ax;
               double ny = this.y[r][c] + (this.y[r][c] - this.py[r][c]) * 0.955 + ay;
               double nz = this.z[r][c] + (this.z[r][c] - this.pz[r][c]) * 0.955 + az;
               this.px[r][c] = this.x[r][c];
               this.py[r][c] = this.y[r][c];
               this.pz[r][c] = this.z[r][c];
               this.x[r][c] = nx;
               this.y[r][c] = ny;
               this.z[r][c] = nz;
            }
         }

         double segment = 0.1423076923076923;

         for (int pass = 0; pass < 5; pass++) {
            for (int r = 1; r < 14; r++) {
               for (int c = 0; c < 7; c++) {
                  this.constrain(r - 1, c, r, c, Math.hypot(segment, halfWidth(t(r)) - halfWidth(t(r - 1))) * 0.98, r - 1 == 0 ? 0.0 : 0.35);
                  if (c + 1 < 7) {
                     this.constrain(r, c, r, c + 1, 2.0 * halfWidth(t(r)) / 6.0, 0.5);
                  }

                  if (this.z[r][c] < bodyZ(t(r))) {
                     this.z[r][c] = bodyZ(t(r));
                  }

                  if (this.y[r][c] < 0.03 + segment * (double)r * 0.35) {
                     this.y[r][c] = 0.03 + segment * (double)r * 0.35;
                  }

                  if (this.y[r][c] > this.floor) {
                     this.y[r][c] = this.floor;
                     this.py[r][c] = this.y[r][c];
                     this.px[r][c] = this.px[r][c] + (this.x[r][c] - this.px[r][c]) * 0.55;
                     this.pz[r][c] = this.pz[r][c] + (this.z[r][c] - this.pz[r][c]) * 0.55;
                  }
               }
            }
         }
      }
   }

   private void constrain(int ra, int ca, int rb, int cb, double rest, double shareA) {
      double dx = this.x[rb][cb] - this.x[ra][ca];
      double dy = this.y[rb][cb] - this.y[ra][ca];
      double dz = this.z[rb][cb] - this.z[ra][ca];
      double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
      if (!(d < 1.0E-6)) {
         double k = (d - rest) / d;
         double sb = 1.0 - shareA;
         this.x[ra][ca] = this.x[ra][ca] + dx * k * shareA;
         this.y[ra][ca] = this.y[ra][ca] + dy * k * shareA;
         this.z[ra][ca] = this.z[ra][ca] + dz * k * shareA;
         this.x[rb][cb] = this.x[rb][cb] - dx * k * sb;
         this.y[rb][cb] = this.y[rb][cb] - dy * k * sb;
         this.z[rb][cb] = this.z[rb][cb] - dz * k * sb;
      }
   }

   public float[] sample(float vx, float vy, float partial) {
      float tt = Mth.clamp((vy - 0.03F) / 1.85F, 0.0F, 1.0F);
      float row = tt * 13.0F;
      float col = Mth.clamp((vx / (float)halfWidth((double)tt) + 1.0F) / 2.0F, 0.0F, 1.0F) * 6.0F;
      int r = (int)row;
      int c = (int)col;
      int r1 = Math.min(r + 1, 13);
      int c1 = Math.min(c + 1, 6);
      float fr = row - (float)r;
      float fc = col - (float)c;
      float[] out = new float[3];
      double[][][] now = new double[][][]{this.x, this.y, this.z};
      double[][][] old = new double[][][]{this.rx, this.ry, this.rz};

      for (int i = 0; i < 3; i++) {
         double v0 = Mth.lerp((double)partial, old[i][r][c], now[i][r][c]);
         double v1 = Mth.lerp((double)partial, old[i][r1][c], now[i][r1][c]);
         double v2 = Mth.lerp((double)partial, old[i][r][c1], now[i][r][c1]);
         double v3 = Mth.lerp((double)partial, old[i][r1][c1], now[i][r1][c1]);
         out[i] = (float)Mth.lerp((double)fc, Mth.lerp((double)fr, v0, v1), Mth.lerp((double)fr, v2, v3));
      }

      return out;
   }

   public static float authoredZ(float vx, float vy) {
      float tt = Mth.clamp((vy - 0.03F) / 1.85F, 0.0F, 1.0F);
      float u = Mth.clamp(vx / (float)halfWidth((double)tt), -1.0F, 1.0F);
      return (float)(0.25 + 0.18 * (double)tt + 0.035 * Math.cos((double)u * Math.PI * 5.0) * Math.sin((double)tt * Math.PI / 2.0));
   }
}

