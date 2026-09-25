package com.hexgodofstories.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

public final class AuthoredMesh {
   private final List<AuthoredMesh.Face> faces = new ArrayList<>();
   private final Map<String, float[]> centers = new HashMap<>();
   private float minY = Float.POSITIVE_INFINITY;
   private float maxY = Float.NEGATIVE_INFINITY;
   private float[] spine;

   public AuthoredMesh(String asset) {
      this(openAsset(asset));
   }

   private static Reader openAsset(String asset) {
      ResourceLocation id = ResourceLocation.tryBuild("hexgodofstories", "models/" + asset + ".obj");

      try {
         return new InputStreamReader(Minecraft.getInstance().getResourceManager().open(id), StandardCharsets.UTF_8);
      } catch (IOException var3) {
         throw new IllegalStateException("Cannot load mod asset " + id, var3);
      }
   }

   AuthoredMesh(Reader source) {
      try {
         try (BufferedReader reader = new BufferedReader(source)) {
            List<float[]> vertices = new ArrayList<>();
            List<float[]> uvs = new ArrayList<>();
            String group = "root";

            String line;
            while ((line = reader.readLine()) != null) {
               String[] s = line.trim().split("\\s+");
               String var8 = s[0];
               switch (var8) {
                  case "v":
                     float[] point = new float[]{Float.parseFloat(s[1]), Float.parseFloat(s[2]), Float.parseFloat(s[3])};
                     vertices.add(point);
                     this.minY = Math.min(this.minY, point[1]);
                     this.maxY = Math.max(this.maxY, point[1]);
                     break;
                  case "vt":
                     uvs.add(new float[]{Float.parseFloat(s[1]), Float.parseFloat(s[2])});
                     break;
                  case "g":
                     group = s.length > 1 ? s[1] : "root";
                     break;
                  case "f":
                     if (s.length != 5) {
                        throw new IOException("Expected authored quad: " + line);
                     }

                     AuthoredMesh.Point[] points = new AuthoredMesh.Point[4];

                     for (int i = 0; i < 4; i++) {
                        String[] index = s[i + 1].split("/");
                        float[] p = vertices.get(Integer.parseInt(index[0]) - 1);
                        float[] uv = uvs.get(Integer.parseInt(index[1]) - 1);
                        points[i] = new AuthoredMesh.Point(p[0], p[1], p[2], uv[0], uv[1]);
                     }

                     if (group.startsWith("arms_")) {
                        for (int row = 0; row < 8; row++) {
                           float a = (float)row / 8.0F;
                           float b = (float)(row + 1) / 8.0F;
                           this.faces
                              .add(
                                 new AuthoredMesh.Face(
                                    group,
                                    new AuthoredMesh.Point[]{
                                       lerp(points[0], points[3], a),
                                       lerp(points[1], points[2], a),
                                       lerp(points[1], points[2], b),
                                       lerp(points[0], points[3], b)
                                    }
                                 )
                              );
                        }
                     } else {
                        this.faces.add(new AuthoredMesh.Face(group, points));
                     }
               }
            }
         }
      } catch (IOException var17) {
         throw new IllegalStateException("Cannot parse mod asset", var17);
      }
   }

   private static AuthoredMesh.Point lerp(AuthoredMesh.Point a, AuthoredMesh.Point b, float t) {
      return new AuthoredMesh.Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, a.u + (b.u - a.u) * t, a.v + (b.v - a.v) * t);
   }

   public float top() {
      return this.minY;
   }

   public float[] spine(float y) {
      if (this.spine == null) {
         float[] sx = new float[24];
         float[] sz = new float[24];
         int[] n = new int[24];

         for (AuthoredMesh.Face face : this.faces) {
            for (AuthoredMesh.Point p : face.points) {
               int i = this.band(p.y);
               sx[i] += p.x;
               sz[i] += p.z;
               n[i]++;
            }
         }

         this.spine = new float[48];

         for (int i = 0; i < 24; i++) {
            if (n[i] > 0) {
               this.spine[i * 2] = sx[i] / (float)n[i];
               this.spine[i * 2 + 1] = sz[i] / (float)n[i];
            } else if (i > 0) {
               this.spine[i * 2] = this.spine[i * 2 - 2];
               this.spine[i * 2 + 1] = this.spine[i * 2 - 1];
            }
         }
      }

      int ix = this.band(y);
      return new float[]{this.spine[ix * 2], this.spine[ix * 2 + 1]};
   }

   private int band(float y) {
      float span = Math.max(1.0E-4F, this.maxY - this.minY);
      return Math.max(0, Math.min(23, (int)((y - this.minY) / span * 24.0F)));
   }

   public float bottom() {
      return this.maxY;
   }

   public boolean hasGroup(String name) {
      for (AuthoredMesh.Face f : this.faces) {
         if (f.group.equals(name)) {
            return true;
         }
      }

      return false;
   }

   public float[] groupCenter(String name) {
      return this.centers.computeIfAbsent(name, g -> {
         double x = 0.0;
         double y = 0.0;
         double z = 0.0;
         int n = 0;

         for (AuthoredMesh.Face f : this.faces) {
            if (f.group.equals(g)) {
               for (AuthoredMesh.Point p : f.points) {
                  x += (double)p.x;
                  y += (double)p.y;
                  z += (double)p.z;
                  n++;
               }
            }
         }

         return n == 0 ? new float[]{0.0F, 0.0F, 0.0F} : new float[]{(float)(x / (double)n), (float)(y / (double)n), (float)(z / (double)n)};
      });
   }

   public void drawManifesting(PoseStack pose, VertexConsumer out, int light, float presence, float gripY) {
      this.drawManifesting(pose, out, light, presence, gripY, null);
   }

   public void drawManifesting(PoseStack pose, VertexConsumer out, int light, float presence, float gripY, AuthoredMesh.Paint paint) {
      float growth = Math.max(0.0F, Math.min(1.0F, presence));
      if (!(growth <= 0.0F)) {
         float boundary = growth * Math.max(Math.abs(this.minY - gripY), Math.abs(this.maxY - gripY));
         this.draw(
            pose,
            out,
            light,
            (group, v) -> Math.abs(v.y - gripY) > boundary
                  ? null
                  : new AuthoredMesh.Point(v.x * (0.55F + 0.45F * growth), v.y, v.z * (0.55F + 0.45F * growth), v.u, v.v),
            paint
         );
      }
   }

   public void draw(PoseStack pose, VertexConsumer out, int light, AuthoredMesh.Deform deform) {
      this.draw(pose, out, light, deform, null);
   }

   public void draw(PoseStack pose, VertexConsumer out, int light, AuthoredMesh.Deform deform, AuthoredMesh.Paint paint) {
      for (AuthoredMesh.Face face : this.faces) {
         AuthoredMesh.Point[] p = new AuthoredMesh.Point[4];
         boolean hidden = false;

         for (int i = 0; i < 4; i++) {
            p[i] = deform.apply(face.group, face.points[i]);
            if (p[i] == null) {
               hidden = true;
               break;
            }
         }

         if (!hidden) {
            Vector3f normal = new Vector3f(p[1].x - p[0].x, p[1].y - p[0].y, p[1].z - p[0].z)
               .cross(new Vector3f(p[2].x - p[0].x, p[2].y - p[0].y, p[2].z - p[0].z));
            if (!((double)normal.lengthSquared() < 1.0E-10)) {
               normal.normalize();

               for (AuthoredMesh.Point v : p) {
                  int r = 255;
                  int g = 255;
                  int b = 255;
                  int a = 255;
                  if (paint != null) {
                     int[] c = paint.apply(face.group, v);
                     if (c != null) {
                        r = c[0];
                        g = c[1];
                        b = c[2];
                        a = c.length > 3 ? c[3] : 255;
                     }
                  }

                  out.vertex(pose.last().pose(), v.x, v.y, v.z)
                     .color(r, g, b, a)
                     .uv(v.u, v.v)
                     .overlayCoords(OverlayTexture.NO_OVERLAY)
                     .uv2(light)
                     .normal(pose.last().normal(), normal.x, normal.y, normal.z)
                     .endVertex();
               }
            }
         }
      }
   }

   /** Clip both reveal fronts through the surface instead of compressing hidden vertices. */
   public void drawRevealing(PoseStack pose, VertexConsumer out, int light, float growth) {
      if (growth >= 1) { draw(pose, out, light, (group, p) -> p); return; }
      float boundary = Math.max(Math.abs(minY), Math.abs(maxY)) * Math.max(0, growth);
      for (Face face : faces) {
         List<Point> polygon = clipReveal(List.of(face.points), boundary, 1);
         polygon = clipReveal(polygon, boundary, -1);
         for (int i = 1; i + 1 < polygon.size(); i++) {
            Point a = polygon.get(0), b = polygon.get(i), c = polygon.get(i + 1);
            Vector3f normal = new Vector3f(b.x-a.x,b.y-a.y,b.z-a.z)
               .cross(new Vector3f(c.x-a.x,c.y-a.y,c.z-a.z));
            if (normal.lengthSquared() < 1.0e-10f) continue;
            normal.normalize();
            for (Point v : new Point[]{a,b,c,c}) {
               out.vertex(pose.last().pose(),v.x,v.y,v.z).color(255,255,255,255)
                  .uv(v.u,v.v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                  .normal(pose.last().normal(),normal.x,normal.y,normal.z).endVertex();
            }
         }
      }
   }

   private static List<Point> clipReveal(List<Point> source, float boundary, int side) {
      List<Point> result = new ArrayList<>();
      if (source.isEmpty()) return result;
      Point previous = source.get(source.size()-1);
      for (Point current : source) {
         boolean before = previous.y*side <= boundary, after = current.y*side <= boundary;
         if (before != after) {
            float t = (side*boundary-previous.y)/(current.y-previous.y);
            result.add(lerp(previous,current,t));
         }
         if (after) result.add(current);
         previous = current;
      }
      return result;
   }

   public interface Deform {
      AuthoredMesh.Point apply(String var1, AuthoredMesh.Point var2);
   }

   private static record Face(String group, AuthoredMesh.Point[] points) {
   }

   public interface Paint {
      int[] apply(String var1, AuthoredMesh.Point var2);
   }

   public static record Point(float x, float y, float z, float u, float v) {
   }
}

