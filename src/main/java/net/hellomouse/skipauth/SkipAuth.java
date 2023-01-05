package net.hellomouse.skipauth;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import inet.ipaddr.AddressStringException;
import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import net.minecraft.server.WhitelistEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.UuidUtil;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;
import org.quiltmc.qsl.command.api.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

public class SkipAuth implements ModInitializer {
	public static final Logger log = LoggerFactory.getLogger("SkipAuth");
	public final LiteralArgumentBuilder<ServerCommandSource> skipauthCommand;
	public static final ConcurrentHashMap<String, IPAddress> offlinePlayers = new ConcurrentHashMap<>();
	public final Path configPath = QuiltLoader.getConfigDir().resolve("skipauth.txt");

	public SkipAuth() {
		skipauthCommand = CommandManager
			.literal("skipauth")
			.requires(source -> source.hasPermissionLevel(3))
			.executes(context -> {
				context.getSource().sendFeedback(Text.literal("hello"), false);
				return 0;
			})
			.then(
				CommandManager.literal("add")
					.then(
						CommandManager.argument("user", StringArgumentType.word())
							.then(
								CommandManager.argument("ip", StringArgumentType.greedyString())
									.executes(context -> {
										var source = context.getSource();
										var username = StringArgumentType.getString(context, "user");
										var addressString = StringArgumentType.getString(context, "ip");
										IPAddress address;
										try {
											// this supports subnets
											address = new IPAddressString(addressString).toAddress();
										} catch (AddressStringException ex) {
											source.sendFeedback(Text.literal("Cannot parse as address or subnet: " + addressString), false);
											return 1;
										}

										offlinePlayers.put(username, address);
										source.sendFeedback(Text.literal("Added offline-mode user " + username + " with address " + address), true);
										saveConfig();
										return 0;
									})
							)
					)
			)
			.then(
				CommandManager.literal("list")
					.executes(context -> {
						var source = context.getSource();
						if (offlinePlayers.size() > 0) {
							source.sendFeedback(Text.literal("The following offline-mode users are known:"), false);
							for (var entry : offlinePlayers.entrySet()) {
								source.sendFeedback(Text.literal("Username " + entry.getKey() + ", address " + entry.getValue()), false);
							}
						} else {
							source.sendFeedback(Text.literal("No offline-mode users have been added."), false);
						}
						return 0;
					})
			)
			.then(
				CommandManager.literal("remove")
					.then(
						CommandManager.argument("user", StringArgumentType.word())
							.executes(context -> {
								var username = StringArgumentType.getString(context, "user");
								IPAddress prev;
								prev = offlinePlayers.remove(username);
								if (prev == null) {
									context.getSource().sendFeedback(Text.literal("Unknown offline-mode user " + username), true);
									return 1;
								} else {
									context.getSource().sendFeedback(Text.literal("Removed offline-mode user " + username), true);
									saveConfig();
									return 0;
								}
							})
					)
			)
			.then(CommandManager.literal("reload")
				.executes(context -> {
					var source = context.getSource();
					var success = loadConfig();
					if (success) {
						source.sendFeedback(Text.literal("Successfully reloaded offline players list"), true);
						return 0;
					} else {
						source.sendFeedback(Text.literal("Failed to reload offline players list (see console)"), true);
						return 1;
					}
				})
			)
			.then(
				CommandManager.literal("whitelist")
					.then(
						CommandManager.argument("user", StringArgumentType.word())
							.executes(context -> {
								// this is required as whitelist checks both username and UUID
								// /whitelist add will perform a UUID lookup with Mojang instead of using offline UUID
								var username = StringArgumentType.getString(context, "user");
								var offlineUUID = UuidUtil.getOfflinePlayerUuid(username);
								var profile = new GameProfile(offlineUUID, username);

								var whitelist = context.getSource().getServer().getPlayerManager().getWhitelist();
								whitelist.add(new WhitelistEntry(profile));

								var reply = "Whitelisted offline-mode player " + username + " (UUID " + offlineUUID + ")";
								context.getSource().sendFeedback(Text.literal(reply), true);
								return 0;
							})
					)
			);
	}

	public boolean loadConfig() {
		try (var reader = Files.newBufferedReader(configPath)) {
			offlinePlayers.clear();
			String line = reader.readLine();
			while (line != null) {
				// skip empty lines
				if (line.length() == 0) continue;
				var split = line.split(" ", 2);
				var username = split[0];
				try {
					IPAddress address = new IPAddressString(split[1]).toAddress();
					offlinePlayers.put(username, address);
				} catch (AddressStringException ex) {
					log.error("error parsing configuration: invalid IP address or subnet", ex);
					return false;
				}
				line = reader.readLine();
			}
			return true;
		} catch (NoSuchFileException ex) {
			log.warn("configuration file does not exist");
		} catch (IOException ex) {
			log.error("error reading configuration", ex);
		}
		return false;
	}

	public void saveConfig() {
		try (var writer = Files.newBufferedWriter(configPath)) {
			for (var entry : offlinePlayers.entrySet()) {
				writer.write(entry.getKey());
				writer.write(" ");
				writer.write(entry.getValue().toString());
				writer.write('\n');
			}
		} catch (IOException ex) {
			log.error("error writing configuration", ex);
		}
	}

	@Override
	public void onInitialize(ModContainer mod) {
		CommandRegistrationCallback.EVENT.register(((dispatcher, buildContext, environment) -> {
			dispatcher.register(skipauthCommand);
		}));

		loadConfig();
	}
}
