package dev.mettle.leximine;

import net.fabricmc.loader.api.FabricLoader;

/** Reads the mod's own version out of loader metadata, so it is never duplicated in code. */
public final class LeximineMeta {
	private LeximineMeta() {
	}

	public static String version() {
		return FabricLoader.getInstance()
				.getModContainer(Leximine.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	public static boolean isDevelopment() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}
}
