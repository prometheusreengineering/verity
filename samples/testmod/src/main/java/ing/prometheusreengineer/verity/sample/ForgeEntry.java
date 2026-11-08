package ing.prometheusreengineer.verity.sample;

/**
 * One class for every Forge flavour: FML 1.7.10 ({@code cpw...Mod}), FML 1.8-1.12 ({@code modid}), MinecraftForge 1.13+
 * ({@code value}) and NeoForge. Each loader only looks at the annotation it knows and constructs the class.
 */
@cpw.mods.fml.common.Mod(modid = "veritysample", name = "Verity sample", version = "1", acceptedMinecraftVersions = "*")
@net.minecraftforge.fml.common.Mod(value = "veritysample", modid = "veritysample", name = "Verity sample", version = "1",
        acceptedMinecraftVersions = "*", clientSideOnly = true)
@net.neoforged.fml.common.Mod("veritysample")
public final class ForgeEntry {
    public ForgeEntry() {
        SampleChecks.start(detectLoader());
    }

    private static String detectLoader() {
        if (present("net.neoforged.fml.common.Mod")) return "neoforge";
        if (present("net.minecraftforge.fml.ModLoader")) return "forge";
        return "forge (legacy)";
    }

    private static boolean present(String cls) {
        try {
            Class.forName(cls, false, ForgeEntry.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
