package fixture;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="fixture.DebugSubject", methodName="calculate")
public final class PatchFixture {
    static { System.setProperty("pzdebug.patch_fixture_initialized", "yes"); }
    @Patch.OnEnter
    public static void enter() {}
}
