package com.lge.blurengine2;

/** Isolated candidate source, NOT installed. No Android APIs or per-draw objects. */
public final class LandscapeSizing {
    private LandscapeSizing() {}
    /** Zero means NOT APPLICABLE or INVALID: caller must distinguish eligibility.
     * Fit surface aspect inside 1920*scale by 1080*scale, with no upscaling.
     * The surface and screenshot dimensions are NOT changed by this helper.
     * Integer raster rounding is less than one pixel per unclamped axis.
     */
    public static long fit(int surfaceW, int surfaceH, float captureScale,
                           boolean realtime, boolean image, boolean offscreen) {
        if (!realtime || image || offscreen || surfaceW <= surfaceH || surfaceH <= 0)
            return 0L;
        if (!(captureScale > 0f) || captureScale == Float.POSITIVE_INFINITY) return 0L;
        double cw = 1920.0 * captureScale, ch = 1080.0 * captureScale;
        // Fail closed rather than overflow/saturate an implausible scale.
        if (cw > Integer.MAX_VALUE || ch > Integer.MAX_VALUE || cw < 1 || ch < 1)
            return 0L;
        double factor = cw / surfaceW;
        if (ch / surfaceH < factor) factor = ch / surfaceH;
        if (factor > 1.0) factor = 1.0;
        int w = (int)(surfaceW * factor), h = (int)(surfaceH * factor);
        if (w <= 0 || h <= 0) return 0L;
        return ((long)w << 32) | (h & 0xffffffffL);
    }
    public static int width(long size) { return (int)(size >>> 32); }
    public static int height(long size) { return (int)size; }
    /** Six MultiScale targets use divisors 2,4,8,16,32,64, plus full input FBO.
     * Tiny targets clamp to one, not zero; their exact aspect is raster-limited.
     */
    public static int level(int base, int index) {
        if (base <= 0 || index < 0 || index >= 6) throw new IllegalArgumentException();
        int v = base >> (index + 1);
        return v == 0 ? 1 : v;
    }
}
