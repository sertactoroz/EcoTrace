package com.ecotrace.api.common.util;

public final class GeoHashUtil {

    private static final char[] BASE32 =
            {'0','1','2','3','4','5','6','7','8','9',
             'b','c','d','e','f','g','h','j','k','m',
             'n','p','q','r','s','t','u','v','w','x','y','z'};

    private GeoHashUtil() {}

    public static String encode(double lat, double lon, int precision) {
        if (precision < 1 || precision > 12) {
            throw new IllegalArgumentException("precision must be 1..12");
        }
        double[] latRange = {-90.0, 90.0};
        double[] lonRange = {-180.0, 180.0};
        StringBuilder out = new StringBuilder(precision);
        boolean isLon = true;
        int bit = 0;
        int ch = 0;
        while (out.length() < precision) {
            double mid;
            if (isLon) {
                mid = (lonRange[0] + lonRange[1]) / 2.0;
                if (lon >= mid) {
                    ch = (ch << 1) | 1;
                    lonRange[0] = mid;
                } else {
                    ch = ch << 1;
                    lonRange[1] = mid;
                }
            } else {
                mid = (latRange[0] + latRange[1]) / 2.0;
                if (lat >= mid) {
                    ch = (ch << 1) | 1;
                    latRange[0] = mid;
                } else {
                    ch = ch << 1;
                    latRange[1] = mid;
                }
            }
            isLon = !isLon;
            if (++bit == 5) {
                out.append(BASE32[ch]);
                bit = 0;
                ch = 0;
            }
        }
        return out.toString();
    }
}
