package dev.mettle.leximine.client;

import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.LeximineMeta;
import dev.mettle.leximine.config.LexConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Client entrypoint. Everything that touches the screen, the keyboard or the
 * microphone hangs off here; none of it is loaded on a dedicated server.
 */
public class LeximineClient implements ClientModInitializer {
	/**
	 * The title screen is re-initialised on every resize, and the seam below
	 * must not slam the picker back open each time.
	 */
	private static boolean openedOnce;

	@Override
	public void onInitializeClient() {
		ChallengeTracker.registerReceivers();
		LexKeys.register();

		HudRenderCallback.EVENT.register((context, tickDelta) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			ChallengeHud.render(context, client);
			// Drawn after, and lower down: the wheel spins while the verdict
			// flash is still fading, and they must not share a line.
			LottoRoulette.get().render(context, client);
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			LexKeys.tick(client);
			ChallengeTracker.get().tick(client);
			LottoRoulette.get().tick(client);
			maybeOpenScreenForTesting(client);
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> LexSocket.get().shutdown());

		// A language button on the title screen. Through ScreenEvents rather
		// than a mixin into TitleScreen: the vanilla screen moves its widgets
		// around between versions and a mixin that targets its layout breaks
		// on contact, while adding a widget afterwards does not care how the
		// rest of the screen is arranged.
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof TitleScreen)) {
				return;
			}
			// Top-left. The bottom edge looks like the obvious home for this
			// and is not: two screenshots in, it sat on the
			// "Minecraft 1.20.4/Fabric (Modded)" version string at one
			// height and on the accessibility button at the next. The top
			// left corner of the title screen is genuinely empty -- the logo
			// is centred and the splash sits to its right -- so nothing has
			// to be dodged.
			Screens.getButtons(screen).add(ButtonWidget.builder(
							languageButtonLabel(),
							button -> client.setScreen(new LanguageScreen(screen)))
					.dimensions(4, 4, 120, 20)
					.build());

			// Test seam, in the same spirit as -Dleximine.fakeMic. A screen
			// behind a button on the title screen cannot be reached by the
			// e2e harness at all: it drives the game over RCON, which needs a
			// server, and the title screen is the one place there is not one.
			// Synthetic clicks are not an answer either -- Windows refuses to
			// hand a background game the foreground, so the click lands
			// somewhere else entirely.
		});

		Leximine.LOGGER.info("Leximine {} loaded (client)", LeximineMeta.version());
		LexClientConfig.logSettings();
		AudioCapture.logInputDevices();

		// One socket for the session, opened now: a connection handshake
		// inside the player's five-second window is time they cannot spare.
		LexSocket.get().setListener(ChallengeTracker.get());
		LexSocket.get().connect();
	}

	/**
	 * {@code -Dleximine.openScreen=language}: open the picker so it can be
	 * looked at. Development seam, in the same spirit as
	 * {@code -Dleximine.fakeMic}.
	 *
	 * <p>Driven from the tick rather than from {@code ScreenEvents.AFTER_INIT},
	 * which was tried first and does not work: the client installs the title
	 * screen after that callback returns, and then installs it <em>again</em>
	 * once the Realms check finishes, so anything opened from there is thrown
	 * away twice. Waiting for the title screen to still be the current screen
	 * a second later is the only version of this that survives both.
	 *
	 * <p>Exists because this screen is otherwise unreachable by any test:
	 * the e2e harness drives the game over RCON, which needs a server, and
	 * the title screen is the one place there is not one. Synthetic mouse
	 * clicks are not an alternative — Windows will not give a background game
	 * the foreground, so the click lands on whatever is in front instead.
	 */
	private static void maybeOpenScreenForTesting(MinecraftClient client) {
		if (openedOnce || !"language".equals(System.getProperty("leximine.openScreen"))) {
			return;
		}
		if (!(client.currentScreen instanceof TitleScreen title)) {
			return;
		}
		if (++titleTicks < 40) {
			return;
		}
		openedOnce = true;
		client.setScreen(new LanguageScreen(title));
	}

	private static int titleTicks;

	/** "Learning: Japanese" — the current setting, readable at a glance. */
	private static Text languageButtonLabel() {
		String code = LexConfig.get().language();
		String name = LanguageCatalog.nameFor(code);
		return Text.literal("Learning: " + (name.isBlank() ? code : name));
	}
}
