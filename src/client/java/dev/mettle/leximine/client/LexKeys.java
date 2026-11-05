package dev.mettle.leximine.client;

import dev.mettle.leximine.config.LexConfig;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Key bindings.
 *
 * <p>Push-to-talk is only consulted when {@code -Dleximine.pushToTalk=true};
 * by default the microphone opens on its own for the length of a challenge.
 * The pass/fail keys stay in as a way to play, and to test, without speaking.
 */
public final class LexKeys {
	private static final String CATEGORY = "category.leximine";

	public static KeyBinding pushToTalk;
	public static KeyBinding debugPass;
	public static KeyBinding debugFail;
	public static KeyBinding hearIt;
	public static KeyBinding reportPrompt;

	private static boolean talkHeld;

	private LexKeys() {
	}

	public static void register() {
		// V is unbound in vanilla and is the usual voice-chat key.
		pushToTalk = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.leximine.push_to_talk", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));
		debugPass = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.leximine.debug_pass", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY));
		// J, not L: L is vanilla's Advancements key, and the generated
		// options.txt showed both bound to it.
		debugFail = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.leximine.debug_fail", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY));
		// Hear the word before attempting it. Not a cheat and not optional
		// for some languages: a Japanese prompt is 84% likely to contain a
		// kanji, whose reading cannot be worked out by rule, so without this
		// the player is shown a character and asked to say it with no way to
		// find out how. B for "before", and unbound in vanilla.
		hearIt = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.leximine.hear_it", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));
		// Report a sentence as wrong. Outside Spanish the sentence frames are
		// written by someone who does not speak the language; the people who
		// do are the ones playing in it. N is unbound in vanilla.
		reportPrompt = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.leximine.report", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY));
	}

	public static void tick(MinecraftClient client) {
		// wasPressed() drains the queued press, so this is edge-triggered and
		// a held key cannot spam verdicts.
		while (debugPass.wasPressed()) {
			ChallengeTracker.get().answerCurrent(true);
		}
		while (debugFail.wasPressed()) {
			ChallengeTracker.get().answerCurrent(false);
		}
		while (hearIt.wasPressed()) {
			ChallengeTracker.get().speakCurrentTarget();
		}
		while (reportPrompt.wasPressed()) {
			PromptReport.reportLast();
		}

		if (!LexConfig.get().pushToTalk()) {
			return;
		}
		// isPressed() is level-triggered, which is what hold-to-talk needs;
		// the edge is tracked here so press and release each fire once.
		boolean held = pushToTalk.isPressed();
		if (held && !talkHeld) {
			ChallengeTracker.get().onPushToTalkPressed();
		} else if (!held && talkHeld) {
			ChallengeTracker.get().onPushToTalkReleased();
		}
		talkHeld = held;
	}
}
