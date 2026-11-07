package dev.verity.core.run;

import dev.verity.core.profile.Profile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Per-invocation overrides on top of the profile. */
public class RunOptions {
    /** Only run targets matching any of these filters (see {@link Target#matchesFilter}). */
    public List<String> only = new ArrayList<>();
    /** Only run this part of the targets; ignored by {@link #prepareOnly}, which must install all of them. */
    public Shard shard;
    public Integer maxParallel;
    public Profile.DisplayMode display;
    /** Where reports go; default {@code <home>/runs/<timestamp>-<profile>}. */
    public Path reportDir;
    public Boolean keepInstances;
    /** Install everything but do not launch. */
    public boolean prepareOnly;
    /** Overrides the display implementation (embedding/tests). */
    public dev.verity.core.display.DisplayProvider displayProvider;
}
