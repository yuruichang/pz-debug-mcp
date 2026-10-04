package fixture;
import java.lang.instrument.Instrumentation;
public final class TestAgent {
    public static Instrumentation instrumentation;
    public static void premain(String args, Instrumentation inst) { instrumentation = inst; }
}
