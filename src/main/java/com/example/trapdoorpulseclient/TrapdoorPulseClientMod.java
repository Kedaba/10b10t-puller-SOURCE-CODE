package com.example.trapdoorpulseclient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Client-side mod. Runs on an account other than any of the configured
 * players. When a configured player whispers "p" (rendered as
 * "<name> whispers: p"), this looks for the nearest trapdoor of that
 * player's configured wood type within reach of the LOCAL player (the
 * account this mod is installed on) and right-clicks it closed, then
 * right-clicks it open again a moment later.
 *
 * Who triggers which trapdoor type is read from a JSON config file at
 * config/trapdoor-pulse-client.json (created with a default example the
 * first time the mod runs) instead of being hardcoded, so you can add,
 * remove, or change entries without recompiling.
 *
 * Because this is a client mod, it has no authority over the world — the
 * only way to change a block is to send the same interaction the server
 * would accept from a normal right-click, which means the local player
 * needs to actually be near the trapdoor for this to do anything.
 *
 * Built for Minecraft / Fabric 26.1.2 (unobfuscated, Mojang mappings).
 */
public class TrapdoorPulseClientMod implements ClientModInitializer {

	private static final Logger LOGGER = LoggerFactory.getLogger("trapdoor-pulse-client");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** The exact whisper message that triggers a pull. */
	private static final String TRIGGER_COMMAND = "p";

	/** Player who can trigger the "who's running the mod" version check. */
	private static final String VERSION_CHECK_PLAYER = "Muck_Foyang";

	/** Public chat message that triggers a version-check reply. */
	private static final String VERSION_CHECK_TRIGGER = "!pbi";

	/**
	 * Matches a rendered public chat line like "<Muck_Foyang> !pbi", tolerant of
	 * extra whitespace. Used because this server routes public chat through the
	 * GAME event (system messages) rather than the signed CHAT event — see the
	 * README for the spoofing tradeoff this implies.
	 */
	private static final Pattern VERSION_CHECK_PATTERN = Pattern.compile(
			"^<\\s*" + Pattern.quote(VERSION_CHECK_PLAYER) + "\\s*>\\s*" + Pattern.quote(VERSION_CHECK_TRIGGER) + "\\s*$");

	/** How far (in blocks, each direction) to search around the local player for the target trapdoor. */
	private static final int SEARCH_RADIUS = 6;

	/** How many client ticks to wait between the two clicks. 20 ticks = 1 second. */
	private static final int REOPEN_DELAY_TICKS = 20;

	/** Server this account should auto-login on, if a crackedpass is configured. */
	private static final String LOGIN_SERVER_HOST = "10b10t.com";

	/** How many client ticks to wait after joining before sending /login. 20 ticks = 1 second, so 100 = 5 seconds. */
	private static final int LOGIN_DELAY_TICKS = 100;

	/** Recognized trapdoor type names for the config file's "trapdoor" field. */
	private static final Map<String, Block> TRAPDOOR_TYPES = new LinkedHashMap<>();
	static {
		TRAPDOOR_TYPES.put("oak", Blocks.OAK_TRAPDOOR);
		TRAPDOOR_TYPES.put("spruce", Blocks.SPRUCE_TRAPDOOR);
		TRAPDOOR_TYPES.put("birch", Blocks.BIRCH_TRAPDOOR);
		TRAPDOOR_TYPES.put("jungle", Blocks.JUNGLE_TRAPDOOR);
		TRAPDOOR_TYPES.put("acacia", Blocks.ACACIA_TRAPDOOR);
		TRAPDOOR_TYPES.put("dark_oak", Blocks.DARK_OAK_TRAPDOOR);
		TRAPDOOR_TYPES.put("mangrove", Blocks.MANGROVE_TRAPDOOR);
		TRAPDOOR_TYPES.put("cherry", Blocks.CHERRY_TRAPDOOR);
		TRAPDOOR_TYPES.put("bamboo", Blocks.BAMBOO_TRAPDOOR);
		TRAPDOOR_TYPES.put("crimson", Blocks.CRIMSON_TRAPDOOR);
		TRAPDOOR_TYPES.put("warped", Blocks.WARPED_TRAPDOOR);
		TRAPDOOR_TYPES.put("iron", Blocks.IRON_TRAPDOOR);
	}

	/** One row of the config file's "stations" list: who has to whisper, and which trapdoor type name to pull for them. */
	private static final class ConfigEntry {
		String player;
		String trapdoor;
	}

	/**
	 * The whole config file. "crackedpass" is blank by default, meaning this
	 * account is assumed premium (no auto-login needed). If it's non-blank,
	 * the mod sends "/login <crackedpass>" 5 seconds after joining
	 * LOGIN_SERVER_HOST.
	 */
	private static final class RootConfig {
		String crackedpass = "";
		List<ConfigEntry> stations = new ArrayList<>();
	}

	/** One player-to-trapdoor mapping, resolved from a ConfigEntry. */
	private record PullStation(String playerName, Block trapdoorBlock, Pattern pattern) {
		static PullStation of(String playerName, Block trapdoorBlock) {
			// Matches a rendered whisper line like "<playerName> whispers: p", tolerant of extra whitespace.
			Pattern pattern = Pattern.compile(
					"^" + Pattern.quote(playerName) + "\\s+whispers:\\s*" + Pattern.quote(TRIGGER_COMMAND) + "\\s*$");
			return new PullStation(playerName, trapdoorBlock, pattern);
		}
	}

	private List<PullStation> stations = List.of();
	private String crackedPassword = "";

	// -1 means no login is pending. Counts down to 0 after joining the target server.
	private int pendingLoginTicks = -1;

	// Pending "click again to reopen" actions.
	private final Deque<PendingClick> pendingClicks = new ArrayDeque<>();

	private record PendingClick(BlockPos pos, Block trapdoorBlock, int ticksRemaining) {
		PendingClick tick() {
			return new PendingClick(pos, trapdoorBlock, ticksRemaining - 1);
		}
	}

	@Override
	public void onInitializeClient() {
		stations = loadConfig();

		LOGGER.info("TrapdoorPulseClientMod initialized. Watching {} station(s). crackedpass configured: {}",
				stations.size(), !crackedPassword.isBlank());
		for (PullStation station : stations) {
			LOGGER.info("  - {} -> {}", station.playerName(), station.trapdoorBlock());
		}

		// This server routes public chat through system messages (GAME event)
		// rather than signed player chat (CHAT event) — same as whispers. The CHAT
		// listener is kept for logging only, in case that ever changes; the real
		// version-check trigger is handled in the GAME listener below.
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			String text = message.getString();
			LOGGER.info("CHAT event received (not actionable on this server): '{}' (sender={})",
					text, sender != null ? sender.name() : "null");
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			String text = message.getString();
			LOGGER.info("GAME event received (overlay={}): '{}'", overlay, text);

			String trimmed = text.trim();
			if (VERSION_CHECK_PATTERN.matcher(trimmed).matches()) {
				LOGGER.info("Version-check trigger matched.");
				announceVersion();
				return;
			}

			handleRawText(text);
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			ServerData serverData = client.getCurrentServer();
			String host = serverData != null ? serverData.ip : null;

			LOGGER.info("Joined server: {}", host);

			if (crackedPassword.isBlank()) {
				return; // Premium account, no login needed.
			}

			if (host == null || !host.toLowerCase(Locale.ROOT).contains(LOGIN_SERVER_HOST)) {
				return; // crackedpass is set, but this isn't the server it's for.
			}

			LOGGER.info("crackedpass configured and joined {}. Sending /login in {} ticks.",
					LOGIN_SERVER_HOST, LOGIN_DELAY_TICKS);
			pendingLoginTicks = LOGIN_DELAY_TICKS;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (pendingLoginTicks > 0) {
				pendingLoginTicks--;
				if (pendingLoginTicks == 0) {
					sendLoginCommand();
				}
			}

			if (pendingClicks.isEmpty()) {
				return;
			}

			int size = pendingClicks.size();
			for (int i = 0; i < size; i++) {
				PendingClick pending = pendingClicks.poll();
				pending = pending.tick();

				if (pending.ticksRemaining() <= 0) {
					clickTrapdoor(pending.pos(), pending.trapdoorBlock());
				} else {
					pendingClicks.add(pending);
				}
			}
		});
	}

	/** Sends "/login <crackedpass>" as this client. Never logs the password itself. */
	private void sendLoginCommand() {
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			LOGGER.warn("Can't send /login: no active connection.");
			return;
		}

		LOGGER.info("Sending /login now.");
		client.getConnection().sendCommand("login " + crackedPassword);
	}

	/**
	 * Sends "/w Muck_Foyang running <mod version> of pull bot" as this client,
	 * in response to Muck_Foyang's "!pbi" version-check trigger.
	 */
	private void announceVersion() {
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			LOGGER.warn("Can't reply to version check: no active connection.");
			return;
		}

		String version = FabricLoader.getInstance()
				.getModContainer("trapdoor-pulse-client")
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");

		String command = "w " + VERSION_CHECK_PLAYER + " running " + version + " of pull bot";
		LOGGER.info("Replying to version check: /{}", command);
		client.getConnection().sendCommand(command);
	}

	/**
	 * Loads config/trapdoor-pulse-client.json, creating it with a blank
	 * template the first time the mod runs if it doesn't exist yet. Also
	 * populates crackedPassword as a side effect.
	 *
	 * Accepts both the current object format ({"crackedpass": ..., "stations": [...]})
	 * and the older array-only format (just [...]), so existing config files
	 * from before "crackedpass" was added keep working.
	 */
	private List<PullStation> loadConfig() {
		Path configPath = FabricLoader.getInstance().getConfigDir().resolve("trapdoor-pulse-client.json");

		if (!Files.exists(configPath)) {
			RootConfig defaults = new RootConfig();
			defaults.crackedpass = "";
			defaults.stations.add(newEntry("insert username here", "insert trapdoor type here"));

			try {
				Files.writeString(configPath, GSON.toJson(defaults));
				LOGGER.info("No config found, wrote a blank template to {}", configPath);
			} catch (IOException e) {
				LOGGER.error("Failed to write default config to {}", configPath, e);
			}

			// Placeholder values won't match a real player or a known trapdoor type,
			// and crackedpass is blank, so nothing will trigger until the file is edited.
			return List.of();
		}

		try {
			String json = Files.readString(configPath).trim();
			List<ConfigEntry> entries;

			if (json.startsWith("[")) {
				// Legacy format: a bare array, no crackedpass field.
				Type listType = new TypeToken<List<ConfigEntry>>() {}.getType();
				entries = GSON.fromJson(json, listType);
				crackedPassword = "";
			} else {
				RootConfig root = GSON.fromJson(json, RootConfig.class);
				if (root == null) {
					LOGGER.warn("Config at {} parsed to nothing, using no stations.", configPath);
					return List.of();
				}
				entries = root.stations != null ? root.stations : List.of();
				crackedPassword = root.crackedpass != null ? root.crackedpass : "";
			}

			if (entries == null) {
				entries = List.of();
			}

			List<PullStation> result = new ArrayList<>();
			for (ConfigEntry entry : entries) {
				if (entry.player == null || entry.player.isBlank()) {
					LOGGER.warn("Skipping config entry with no player name.");
					continue;
				}

				String key = entry.trapdoor == null ? "" : entry.trapdoor.trim().toLowerCase(Locale.ROOT);
				Block block = TRAPDOOR_TYPES.get(key);
				if (block == null) {
					LOGGER.warn("Skipping config entry for {}: unknown trapdoor type '{}'. Valid types: {}",
							entry.player, entry.trapdoor, TRAPDOOR_TYPES.keySet());
					continue;
				}

				result.add(PullStation.of(entry.player, block));
			}

			return result;
		} catch (IOException e) {
			LOGGER.error("Failed to read config at {}", configPath, e);
			return List.of();
		}
	}

	private static ConfigEntry newEntry(String player, String trapdoor) {
		ConfigEntry entry = new ConfigEntry();
		entry.player = player;
		entry.trapdoor = trapdoor;
		return entry;
	}

	private void handleRawText(String text) {
		if (text == null) {
			return;
		}

		String trimmed = text.trim();
		for (PullStation station : stations) {
			if (station.pattern().matcher(trimmed).matches()) {
				LOGGER.info("Trigger matched for {}! Searching for a {}...", station.playerName(), station.trapdoorBlock());
				pull(station.trapdoorBlock());
				return;
			}
		}
	}

	private void pull(Block trapdoorBlock) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null) {
			LOGGER.warn("player was null, aborting.");
			return;
		}

		BlockPos trapdoorPos = findNearestTrapdoor(player, SEARCH_RADIUS, trapdoorBlock);
		if (trapdoorPos == null) {
			LOGGER.info("No {} found within {} blocks of {}.", trapdoorBlock, SEARCH_RADIUS, player.blockPosition());
			return;
		}

		LOGGER.info("Found {} at {}.", trapdoorBlock, trapdoorPos);

		BlockState state = client.level.getBlockState(trapdoorPos);
		boolean currentlyOpen = state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
		LOGGER.info("Trapdoor currently open={}", currentlyOpen);

		if (currentlyOpen) {
			// Already open: click once now to close it, then queue the reopen click.
			clickTrapdoor(trapdoorPos, trapdoorBlock);
		}
		// If it's already closed, no click needed yet — it's already in the "closed" state we want first.

		pendingClicks.add(new PendingClick(trapdoorPos, trapdoorBlock, REOPEN_DELAY_TICKS));
	}

	/** Cube search centered on the player, returns the closest matching trapdoor found, or null. */
	private BlockPos findNearestTrapdoor(LocalPlayer player, int radius, Block trapdoorBlock) {
		Minecraft client = Minecraft.getInstance();
		BlockPos origin = player.blockPosition();
		BlockPos closest = null;
		double closestDistSq = Double.MAX_VALUE;

		BlockPos min = origin.offset(-radius, -radius, -radius);
		BlockPos max = origin.offset(radius, radius, radius);

		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			BlockState state = client.level.getBlockState(pos);
			if (state.is(trapdoorBlock)) {
				double distSq = origin.distSqr(pos);
				if (distSq < closestDistSq) {
					closestDistSq = distSq;
					closest = pos.immutable();
				}
			}
		}

		return closest;
	}

	/** Simulates a right-click on the trapdoor at pos, exactly like a normal player interaction. */
	private void clickTrapdoor(BlockPos pos, Block trapdoorBlock) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || client.gameMode == null) {
			LOGGER.warn("Can't click trapdoor at {}: player or gameMode was null.", pos);
			return;
		}

		BlockState state = client.level.getBlockState(pos);
		if (!state.is(trapdoorBlock)) {
			LOGGER.warn("Block at {} is no longer a {} (state={}), skipping click.", pos, trapdoorBlock, state);
			return;
		}

		double distSq = player.blockPosition().distSqr(pos);
		LOGGER.info("Clicking trapdoor at {}. Player is {} blocks away (squared).", pos, distSq);

		Vec3 hitPos = Vec3.atCenterOf(pos);
		BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, pos, false);

		var result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
		player.swing(InteractionHand.MAIN_HAND);
		LOGGER.info("useItemOn result: {}", result);
	}
}
