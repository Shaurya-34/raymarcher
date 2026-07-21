import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

public class Main {

    // Basic 3D vector math on plain double[3] arrays with no external library.
    static class Operations {

        static double[] sub(double[] a, double[] b) {
            return new double[]{a[0]-b[0], a[1]-b[1], a[2]-b[2]};
        }

        // Smooth minimum :- blends two SDF distances near their boundary instead
        // of snapping to whichever is smaller (which is what plain min() does).
        // k controls the width of the blend zone.
        static double smin(double a, double b, double k) {
            double h = Math.max(k - Math.abs(a - b), 0.0) / k;
            return Math.min(a, b) - h*h*k*(1.0/4.0);
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
        // Componentwise absolute value, needed because Java has no native vec3 type,
        // so operations GLSL does per-component have to be spelled out by hand.
        static double[] abs(double[] a) {
            return new double[]{Math.abs(a[0]), Math.abs(a[1]), Math.abs(a[2])};
        }
        // Componentwise max against a scalar (e.g. max(q, 0.0) in GLSL).
        static double[] maxScalar(double[] a, double s) {
            return new double[]{Math.max(a[0], s), Math.max(a[1], s), Math.max(a[2], s)};
        }
        // Largest of the three components — used by the box SDF's "inside" term.
        static double maxComponent(double[] a) {
            return Math.max(a[0], Math.max(a[1], a[2]));
        }
    }

    // Signed distance functions: given a point in space, return how far it is
    // from the nearest surface of the shape. Negative = inside, positive = outside.
    static class Shapes {

        static double sdSphere(double[] p, double[] center, double radius) {
            return Operations.length(Operations.sub(p, center)) - radius;
        }

        // b = half-extents of the box (e.g. {1,1,1} makes a 2x2x2 box).
        static double sdBox(double[] p, double[] center, double[] b) {
            double[] q = Operations.sub(Operations.abs(Operations.sub(p, center)), b);
            double outside = Operations.length(Operations.maxScalar(q, 0.0)); // distance if outside the box
            double inside = Math.min(Operations.maxComponent(q), 0.0);        // distance if inside the box
            return outside + inside;
        }
    }

    // The scene description: combines every shape into one distance function.
    // This is the function the ray marcher queries at every step.
    static double scene(double[] p) {
        double sphere = Shapes.sdSphere(p, new double[] {-0.6, 0 , 0}, 1.0);
        double box = Shapes.sdBox(p, new double[] {0.6, 0, 0}, new double[] {0.7, 0.7, 0.7});
        return Operations.smin(sphere, box, 0.4); // smooth-blended union of the two shapes
    }

    // Sphere tracing: walk along the ray, using the scene's distance-to-surface
    // as a safe step size each time. Stop when very close to a surface (hit)
    // or after travelling too far without hitting anything (miss).
    static double rayMarch(double[] rayOrigin, double[] rayDir) {
        double distTravelled = 0.0;
        int maxSteps = 100;
        double maxDist = 100.0;
        double surfDist = 0.001; // how close counts as "touching" the surface

        for (int i = 0; i < maxSteps; i++) {
            double[] p = Operations.add(rayOrigin, Operations.scale(rayDir, distTravelled));
            double distToScene = scene(p);
            distTravelled += distToScene;
            if (distToScene < surfDist || distTravelled > maxDist) break;
        }
        return distTravelled;
    }

    // Estimates the surface normal at a point by nudging it slightly along each
    // axis and seeing how the distance changes (the gradient of the SDF).
    // This gradient points away from the surface, that's the normal.
    static double[] getNormal(double[] p) {
        double eps = 0.0005; // small offset for the finite-difference approximation
        double dx = scene(new double[]{p[0]+eps, p[1], p[2]}) - scene(new double[]{p[0]-eps, p[1], p[2]});
        double dy = scene(new double[]{p[0], p[1]+eps, p[2]}) - scene(new double[]{p[0], p[1]-eps, p[2]});
        double dz = scene(new double[]{p[0], p[1], p[2]+eps}) - scene(new double[]{p[0], p[1], p[2]-eps});
        return Operations.normalize(new double[]{dx, dy, dz});
    }

    public static void main(String[] args) throws IOException {
        double[] cameraPos = {0, 0, -3};
        double[] lightPos = {1, 2, -3};
        int numSamples = 4; // antialiasing: samples per pixel, averaged together

        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        int width = image.getWidth();
        int height = image.getHeight();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                // Accumulate color across sub-pixel samples before writing anything —
                // averaging AFTER the sample loop is what actually smooths edges.
                double rSum = 0, gSum = 0, bSum = 0;

                for (int i = 0; i < numSamples; i++) {

                    // Jitter the sample into one of 4 sub-pixel quadrants (2x2 grid)
                    // instead of always sampling the exact pixel center.
                    double offsetX = (i % 2 == 0) ? 0.25 : 0.75;
                    double offsetY = (i < 2) ? 0.25 : 0.75;

                    // Map pixel + sub-pixel offset to normalized camera-space coords.
                    double u = (x + offsetX - width / 2.0) / height;
                    double v = -(y + offsetY - height / 2.0) / height;

                    double[] rayDir = Operations.normalize(new double[]{u, v, 1.0});
                    double dist = rayMarch(cameraPos, rayDir);

                    double r, g, b; // doubles here, not int, avoid rounding before averaging
                    if (dist < 100.0) {
                        // Hit: shade using simple diffuse (Lambertian) lighting
                        // brightness = how directly the surface faces the light.
                        double[] hitPoint = Operations.add(cameraPos, Operations.scale(rayDir, dist));
                        double[] normal = getNormal(hitPoint);
                        double[] lightDir = Operations.normalize(Operations.sub(lightPos, hitPoint));
                        double diffuse = Math.max(0.0, Operations.dot(normal, lightDir));
                        r = g = b = diffuse * 255;
                    } else {
                        // Miss: background color.
                        r = g = b = 20;
                    }

                    rSum += r; gSum += g; bSum += b;
                }

                // Average the samples, THEN write the pixel once.
                int r = (int)(rSum / numSamples);
                int g = (int)(gSum / numSamples);
                int b = (int)(bSum / numSamples);
                int color = (255 << 24) | (r << 16) | (g << 8) | b; // pack ARGB into one int
                image.setRGB(x, y, color);
            }
        }

        File outputFile = new File("C:/Users/Shaurya/Desktop/output2.png");
        ImageIO.write(image, "png", outputFile);
        System.out.println("Image written successfully.");
    }
}
