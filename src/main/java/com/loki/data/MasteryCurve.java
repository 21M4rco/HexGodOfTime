package com.loki.data;

public final class MasteryCurve {
   public static final int MAX_LEVEL = 1000;
   public static final long MAX_XP = xpForLevel(1000);

   private MasteryCurve() {
   }

   public static long xpForLevel(int level) {
      long i = (long)Math.max(0, Math.min(1000, level));
      return 40L * i + 3L * i * i / 10L;
   }

   public static int levelForXp(long xp) {
      long i = Math.max(0L, Math.min(MAX_XP, xp));
      int j = 0;
      int k = 1000;

      while (j < k) {
         int l = j + k + 1 >>> 1;
         if (xpForLevel(l) <= i) {
            j = l;
         } else {
            k = l - 1;
         }
      }

      return j;
   }

   public static long intoLevel(long xp) {
      int i = levelForXp(xp);
      return Math.max(0L, xp - xpForLevel(i));
   }

   public static long nextLevelCost(long xp) {
      int i = levelForXp(xp);
      return i >= 1000 ? 0L : xpForLevel(i + 1) - xpForLevel(i);
   }

}
