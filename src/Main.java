import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

public class Main {

    static class Operations {
        static double[] sub(double[] a, double[] b) {
            return new double[]{a[0]-b[0], a[1]-b[1], a[2]-b[2]};
        }
        static double smin(double a, double b, double k) {
            double h = Math.max(k - Math.abs(a - b), 0.0) / k;
            return Math.min(a, b) - h*h*k*(1.0/4.0);
        }
        static double mod(double a, double b){
            return a - b * Math.floor(a/b);
        }
        static double repeat(double coord, double spacing) {
            return mod(coord + 0.5 * spacing, spacing) - 0.5 * spacing;
        }
        static double[] add(double[] a, double[] b) {
            return new double[]{a[0]+b[0], a[1]+b[1], a[2]+b[2]};
        }
        static double[] scale(double[] a, double s) {
            return new double[]{a[0]*s, a[1]*s, a[2]*s};
        }
        static double dot(double[] a, double[] b) {
            return a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
        }
        static double length(double[] a) {
            return Math.sqrt(dot(a, a));
        }
        static double[] normalize(double[] a) {
            double len = length(a);
            return scale(a, 1.0 / len);
        }
        static double[] abs(double[] a) {
            return new double[]{Math.abs(a[0]), Math.abs(a[1]), Math.abs(a[2])};
        }
        static double[] maxScalar(double[] a, double s) {
            return new double[]{Math.max(a[0], s), Math.max(a[1], s), Math.max(a[2], s)};
        }
        static double maxComponent(double[] a) {
            return Math.max(a[0], Math.max(a[1], a[2]));
        }
    }

    static class Shapes {
        static double sdSphere(double[] p, double[] center, double radius) {
            return Operations.length(Operations.sub(p, center)) - radius;
        }
        static double sdBox(double[] p, double[] center, double[] b) {
            double[] q = Operations.sub(Operations.abs(Operations.sub(p, center)), b);
            double outside = Operations.length(Operations.maxScalar(q, 0.0));
            double inside = Math.min(Operations.maxComponent(q), 0.0);
            return outside + inside;
        }
        // flat ground plane at a given height (y = planeY)
        static double sdPlane(double[] p, double planeY) {
            return p[1] - planeY;
        }
    }

    static double scene(double[] p) {
        double sphere = Shapes.sdSphere(p, new double[] {-0.6, 0, 0}, 1.0);
        double box = Shapes.sdBox(p, new double[] {0.6, 0, 0}, new double[] {0.7, 0.7, 0.7});
        double blob = Operations.smin(sphere, box, 0.4);
        double ground = Shapes.sdPlane(p, -1.0); // ground sits below the objects
        return Math.min(blob, ground);
    }

    static double rayMarch(double[] rayOrigin, double[] rayDir) {
        double distTravelled = 0.0;
        int maxSteps = 100;
        double maxDist = 100.0;
        double surfDist = 0.001;
        for (int i = 0; i < maxSteps; i++) {
            double[] p = Operations.add(rayOrigin, Operations.scale(rayDir, distTravelled));
            double distToScene = scene(p);
            distTravelled += distToScene;
            if (distToScene < surfDist || distTravelled > maxDist) break;
        }
        return distTravelled;
    }

    static double[] getNormal(double[] p) {
        double eps = 0.0005;
        double dx = scene(new double[]{p[0]+eps, p[1], p[2]}) - scene(new double[]{p[0]-eps, p[1], p[2]});
        double dy = scene(new double[]{p[0], p[1]+eps, p[2]}) - scene(new double[]{p[0], p[1]-eps, p[2]});
        double dz = scene(new double[]{p[0], p[1], p[2]+eps}) - scene(new double[]{p[0], p[1], p[2]-eps});
        return Operations.normalize(new double[]{dx, dy, dz});
    }

    // March a secondary ray toward the light. If it grazes near other geometry
    // along the way, darken the result — that "closeness of the graze" is what
    // produces a soft penumbra instead of a hard on/off shadow.
    static double softShadow(double[] origin, double[] lightDir, double lightDist, double k) {
        double res = 1.0;
        double t = 0.02; // start slightly off the surface to avoid self-shadowing artifacts
        for (int i = 0; i < 64; i++) {
            if (t >= lightDist) break;
            double[] p = Operations.add(origin, Operations.scale(lightDir, t));
            double h = scene(p);
            if (h < 0.001) return 0.0; // fully blocked
            res = Math.min(res, k * h / t);
            t += h;
        }
        return res;
    }

    public static void main(String[] args) throws IOException {
        double[] cameraPos = {0, 1, -5};
        double[] lightPos = {3, 4, -3};
        int numSamples = 4;

        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        int width = image.getWidth();
        int height = image.getHeight();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                double rSum = 0, gSum = 0, bSum = 0;

                for (int i = 0; i < numSamples; i++) {
                    double offsetX = (i % 2 == 0) ? 0.25 : 0.75;
                    double offsetY = (i < 2) ? 0.25 : 0.75;

                    double u = (x + offsetX - width / 2.0) / height;
                    double v = -(y + offsetY - height / 2.0) / height;

                    double[] rayDir = Operations.normalize(new double[]{u, v, 1.0});
                    double dist = rayMarch(cameraPos, rayDir);

                    double r, g, b;
                    if (dist < 100.0) {
                        double[] hitPoint = Operations.add(cameraPos, Operations.scale(rayDir, dist));
                        double[] normal = getNormal(hitPoint);

                        double[] toLight = Operations.sub(lightPos, hitPoint);
                        double lightDist = Operations.length(toLight);
                        double[] lightDir = Operations.normalize(toLight);

                        double diffuse = Math.max(0.0, Operations.dot(normal, lightDir));

                        // offset the shadow ray's origin along the normal so it doesn't
                        // immediately re-detect the surface it just left as an obstruction
                        double[] shadowOrigin = Operations.add(hitPoint, Operations.scale(normal, 0.001));
                        double shadow = softShadow(shadowOrigin, lightDir, lightDist, 8.0);

                        double brightness = diffuse * shadow;
                        r = g = b = brightness * 255;
                    } else {
                        r = g = b = 20;
                    }

                    rSum += r; gSum += g; bSum += b;
                }

                int r = (int)(rSum / numSamples);
                int g = (int)(gSum / numSamples);
                int b = (int)(bSum / numSamples);
                int color = (255 << 24) | (r << 16) | (g << 8) | b;
                image.setRGB(x, y, color);
            }
        }

        File outputFile = new File("C:/Users/Shaurya/Desktop/output3.png");
        ImageIO.write(image, "png", outputFile);
        System.out.println("Image written successfully.");
    }
}
