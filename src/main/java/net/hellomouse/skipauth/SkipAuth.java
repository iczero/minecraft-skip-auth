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
import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;
import org.quiltmc.qsl.command.api.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

public class SkipAuth implements ModInitializer {
	public static final Logger log = LoggerFactory.getLogger("SkipAuth");
	public final LiteralArgumentBuilder<ServerCommandSource> skipauthCommand;
	public static final ConcurrentHashMap<String, IPAddress> offlinePlayers = new ConcurrentHashMap<>();

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
								CommandManager.argument("ip", StringArgumentType.word())
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
									return 0;
								}
							})
					)
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

	@Override
	public void onInitialize(ModContainer mod) {
		// TODO: persist config somewhere
		CommandRegistrationCallback.EVENT.register(((dispatcher, buildContext, environment) -> {
			dispatcher.register(skipauthCommand);
		}));
	}
}
