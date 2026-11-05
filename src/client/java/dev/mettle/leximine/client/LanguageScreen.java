package dev.mettle.leximine.client;

import dev.mettle.leximine.Leximine;
import dev.mettle.leximine.config.LexConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pick the language you are learning, without leaving the game.
 *
 * <p>It was already switchable — {@code /lex set language ja_jp} has always
 * worked and takes effect on the next challenge — but only from inside a
 * world, and only if you knew the code. This is the same setting reachable
 * from the title screen, which is where you decide what you are about to
 * play.
 *
 * <p><strong>This writes the shared config, which the server reads.</strong>
 * In single player, and in a world you host, that is the same file and the
 * change applies immediately. Joining someone else's server, the language is
 * theirs to choose and this will not override it — the screen says so rather
 * than letting you set something that silently does nothing.
 */
public final class LanguageScreen extends Screen {
	/** Below the title and the search box. */
	private static final int LIST_TOP = 60;
	/**
	 * Room for the selected-language line, the tag legend, and the two
	 * buttons under them. Raised from 56 when the legend was added and the
	 * list started drawing over the "Selected:" line.
	 */
	private static final int LIST_BOTTOM_MARGIN = 66;

	private final Screen parent;
	private LanguageList list;
	private TextFieldWidget search;
	private List<LanguageCatalog.Entry> all = List.of();
	private String selected;

	public LanguageScreen(Screen parent) {
		super(Text.literal("Leximine - language"));
		this.parent = parent;
		this.selected = LexConfig.get().language();
	}

	@Override
	protected void init() {
		// Shown immediately from the built-in list, then replaced when the
		// backend answers. Opening to an empty box for two seconds and then
		// filling it reads as a bug even when it is not.
		if (all.isEmpty()) {
			all = LanguageCatalog.fallback();
			LanguageCatalog.fetch().thenAccept(rows -> {
				if (this.client != null) {
					this.client.execute(() -> {
						all = rows;
						rebuild();
					});
				}
			});
		}

		search = new TextFieldWidget(this.textRenderer, this.width / 2 - 150, 34, 300, 20,
				Text.literal("Search"));
		search.setPlaceholder(Text.literal("Search (japanese, ja_jp, ...)")
				.formatted(Formatting.DARK_GRAY));
		search.setChangedListener(text -> rebuild());
		addSelectableChild(search);

		list = new LanguageList();
		addSelectableChild(list);

		addDrawableChild(ButtonWidget.builder(Text.literal("Use this language"), button -> apply())
				.dimensions(this.width / 2 - 154, this.height - 32, 150, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> close())
				.dimensions(this.width / 2 + 4, this.height - 32, 150, 20)
				.build());

		rebuild();
	}

	private void rebuild() {
		if (list == null) {
			return;
		}
		String needle = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
		List<LanguageCatalog.Entry> shown = new ArrayList<>();
		for (LanguageCatalog.Entry entry : all) {
			if (needle.isEmpty()
					|| entry.code().toLowerCase(Locale.ROOT).contains(needle)
					|| entry.name().toLowerCase(Locale.ROOT).contains(needle)) {
				shown.add(entry);
			}
		}
		list.setEntries(shown);
	}

	private void apply() {
		if (selected == null || selected.isBlank()) {
			close();
			return;
		}
		LexConfig.get().setLanguage(selected);
		Leximine.LOGGER.info("language set to {} from the title screen", selected);
		close();
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(parent);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		this.renderBackground(context, mouseX, mouseY, delta);
		list.render(context, mouseX, mouseY, delta);
		search.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14,
				0xFFFFFF);

		String name = LanguageCatalog.nameFor(selected);
		Text footer = Text.literal("Selected: " + (name.isBlank() ? selected : name)
						+ "  (" + selected + ")")
				.formatted(Formatting.GRAY);
		context.drawCenteredTextWithShadow(this.textRenderer, footer, this.width / 2,
				this.height - 56, 0xA0A0A0);

		// Says what the two tags mean, once, rather than leaving "words only"
		// to be guessed at.
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal("full = sentences at the high tiers;  "
								+ "words only = item names at every tier")
						.formatted(Formatting.DARK_GRAY),
				this.width / 2, this.height - 45, 0x80_80_80);
		super.render(context, mouseX, mouseY, delta);
	}

	// ------------------------------------------------------------------
	private final class LanguageList extends AlwaysSelectedEntryListWidget<LanguageList.Row> {
		/**
		 * {@code (client, width, height, y, itemHeight)} -- five arguments,
		 * not the six-argument {@code (.., top, bottom, ..)} form most 1.20
		 * examples online use. Checked against the remapped jar rather than
		 * guessed: this mapping takes the widget's own height and top, and
		 * works the bottom out itself.
		 */
		LanguageList() {
			super(LanguageScreen.this.client,
					LanguageScreen.this.width,
					LanguageScreen.this.height - LIST_TOP - LIST_BOTTOM_MARGIN,
					LIST_TOP,
					18);
		}

		void setEntries(List<LanguageCatalog.Entry> rows) {
			clearEntries();
			for (LanguageCatalog.Entry entry : rows) {
				Row row = new Row(entry);
				addEntry(row);
				if (entry.code().equalsIgnoreCase(selected)) {
					setSelected(row);
				}
			}
		}

		@Override
		public int getRowWidth() {
			return 300;
		}

		@Override
		protected int getScrollbarPositionX() {
			return LanguageScreen.this.width / 2 + 160;
		}

		final class Row extends AlwaysSelectedEntryListWidget.Entry<Row> {
			private final LanguageCatalog.Entry entry;

			Row(LanguageCatalog.Entry entry) {
				this.entry = entry;
			}

			@Override
			public Text getNarration() {
				return Text.literal(entry.label());
			}

			@Override
			public boolean mouseClicked(double mouseX, double mouseY, int button) {
				selected = entry.code();
				setSelected(this);
				return true;
			}

			@Override
			public void render(DrawContext context, int index, int y, int x, int width,
					int height, int mouseX, int mouseY, boolean hovered, float delta) {
				boolean isSelected = entry.code().equalsIgnoreCase(selected);
				int colour = isSelected ? 0xFF_FF_C4_40 : 0xFF_E0_E0_E0;
				context.drawTextWithShadow(LanguageScreen.this.textRenderer,
						entry.label(), x + 4, y + 4, colour);
				// Two different facts, and conflating them was the first
				// version's mistake. "ready" only ever meant the dictionary
				// is on disk -- every language works, the rest are extracted
				// from your own game on first use. What actually differs is
				// whether tiers 3 and 4 produce a sentence: without a phrase
				// pack the ladder is flat and a netherite ingot asks exactly
				// what a stick does, which is worth knowing before you pick.
				String tag = "";
				int tagColour = 0;
				if (entry.sentences()) {
					tag = "full";
					tagColour = 0xFF_55_E0_6A;
				} else if (entry.ready()) {
					tag = "words only";
					tagColour = 0xFF_C0_A0_50;
				}
				if (!tag.isEmpty()) {
					int tagX = x + width - LanguageScreen.this.textRenderer.getWidth(tag) - 8;
					context.drawTextWithShadow(LanguageScreen.this.textRenderer,
							tag, tagX, y + 4, tagColour);
				}
			}
		}
	}
}
