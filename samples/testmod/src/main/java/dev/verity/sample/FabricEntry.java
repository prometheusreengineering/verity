package dev.verity.sample;

import net.fabricmc.api.ClientModInitializer;

/** Fabric entry point ({@code fabric.mod.json} → {@code entrypoints.client}). */
public final class FabricEntry implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SampleChecks.start("fabric");
    }
}
