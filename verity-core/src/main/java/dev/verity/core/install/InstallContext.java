package dev.verity.core.install;

import dev.verity.core.home.GlobalSettings;
import dev.verity.core.home.PathLocks;
import dev.verity.core.home.VerityHome;
import dev.verity.core.meta.MetaService;
import dev.verity.core.net.Downloader;
import dev.verity.core.net.Http;
import dev.verity.core.net.MetaCache;
import dev.verity.core.meta.MojangMeta;
import java.io.IOException;
import java.time.Duration;

/** Shared services for installing and launching; one per Verity process. */
public final class InstallContext {
    private final VerityHome home;
    private final GlobalSettings settings;
    private final Http http;
    private final Downloader downloader;
    private final MetaCache cache;
    private final MetaService meta;
    private final PathLocks locks;
    private final VersionResolver resolver;
    private final JavaRuntimeManager java;

    public InstallContext(VerityHome home, GlobalSettings settings, Http http) {
        this.home = home;
        this.settings = settings;
        this.http = http;
        this.downloader = new Downloader(http, settings.downloadThreads);
        this.cache = new MetaCache(http, home.metaCache(), Duration.ofMinutes(Math.max(0, settings.metadataTtlMinutes)));
        this.meta = new MetaService(new MojangMeta(home, cache, downloader), cache);
        this.locks = new PathLocks(home.locks());
        this.resolver = new VersionResolver(home.versions());
        this.java = new JavaRuntimeManager(this);
    }

    public static InstallContext open(VerityHome home) throws IOException {
        home.ensureCreated();
        return new InstallContext(home, GlobalSettings.load(home), new Http());
    }

    public VerityHome home() {
        return home;
    }

    public GlobalSettings settings() {
        return settings;
    }

    public Http http() {
        return http;
    }

    public Downloader downloader() {
        return downloader;
    }

    public MetaCache cache() {
        return cache;
    }

    public MetaService meta() {
        return meta;
    }

    public PathLocks locks() {
        return locks;
    }

    public VersionResolver resolver() {
        return resolver;
    }

    public JavaRuntimeManager java() {
        return java;
    }
}
