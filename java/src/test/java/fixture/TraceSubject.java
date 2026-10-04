package fixture;
public final class TraceSubject {
    public static int calls;
    public static int compute(int x) {
        calls++;
        if (x < 0) throw new IllegalArgumentException("negative");
        return x + 7;
    }
    public static void nothing() { calls++; }
}
