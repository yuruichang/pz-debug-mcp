import java.nio.file.Path;
import zombie.core.znet.SteamWorkshopItem;

/** Runs the shipped upload validator without invoking Steam submission. */
public final class WorkshopCheck {
    public static void main(String[] args) throws Exception {
        zombie.ZomboidFileSystem.instance.init();
        if (args.length > 1) zombie.ZomboidFileSystem.instance.setCacheDir(args[1]);
        Path staging = Path.of(args[0]).toAbsolutePath().normalize();
        SteamWorkshopItem item = new SteamWorkshopItem(staging.toString());
        if (!item.readWorkshopTxt()) throw new AssertionError("Cannot read workshop.txt");
        String error = item.validateContents();
        if (error != null) throw new AssertionError(error + ": " + item.getExtendedErrorInfo(error));
        if (item.getTitle().isBlank() || item.getDescription().isBlank())
            throw new AssertionError("Workshop title and description are required");
        if (!SteamWorkshopItem.getAllowedTags().containsAll(item.getTags()))
            throw new AssertionError("Unsupported Workshop tags: " + item.getTags());
        System.out.println("Workshop validation passed: " + item.getFolderName() +
            "; visibility=" + item.getVisibility() + "; tags=" + item.getTags());
    }
}
