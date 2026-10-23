package android.util;

/** 桌面测试用的 Base64 桩，只为让 RuleEngine 在 JVM 上编译运行（Android 上用的是真的）。 */
public class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_WRAP = 2;

    public static String encodeToString(byte[] b, int flags) {
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    public static byte[] decode(String s, int flags) {
        try {
            return java.util.Base64.getDecoder().decode(s.replaceAll("\\s", ""));
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
