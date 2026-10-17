package heist.engine;

/**
 * The timing curves of the original's animations. UIView's default curve and the named Core Animation functions are cubic
 * Bezier curves through (0,0) and (1,1): ease-in-out (0.42, 0, 0.58, 1), ease-in (0.42, 0, 1, 1) and ease-out (0, 0, 0.58, 1).
 */
public final class Ease {
    private Ease() {}

    public static float inOut(float t) { return bezier(0.42f, 0f, 0.58f, 1f, t); }

    public static float in(float t) { return bezier(0.42f, 0f, 1f, 1f, t); }

    public static float out(float t) { return bezier(0f, 0f, 0.58f, 1f, t); }

    /** The y value of the cubic Bezier (0,0) (x1,y1) (x2,y2) (1,1) at time x = t. */
    public static float bezier(float x1, float y1, float x2, float y2, float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        // solve x(s) = t by bisection, then evaluate y(s)
        double lo = 0, hi = 1, s = t;
        for (int i = 0; i < 40; i++) {
            s = (lo + hi) / 2;
            double x = cubic(x1, x2, s);
            if (x < t) lo = s; else hi = s;
        }
        return (float) cubic(y1, y2, s);
    }

    private static double cubic(double a, double b, double s) {
        double u = 1 - s;
        return 3 * u * u * s * a + 3 * u * s * s * b + s * s * s;
    }
}
