package net.hellomouse.skipauth.mixin;

import com.mojang.authlib.GameProfile;
import inet.ipaddr.IPAddress;
import inet.ipaddr.ipv4.IPv4Address;
import inet.ipaddr.ipv6.IPv6Address;
import net.hellomouse.skipauth.SkipAuth;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

@Mixin(ServerLoginNetworkHandler.class)
public class ServerLoginNetworkHandlerMixin {
	@Shadow
	@Final MinecraftServer server;

	@Shadow
	public @Final ClientConnection connection;

	@Shadow
	ServerLoginNetworkHandler.State state;

	@Shadow
	public void disconnect(Text reason) {}

	@Inject(method = "onHello", at = @At(
		value = "INVOKE",
		target = "Lnet/minecraft/server/MinecraftServer;isOnlineMode()Z"
	), cancellable = true)
	public void skipauth$onHello(LoginHelloC2SPacket packet, CallbackInfo ci) {
		// do nothing if offline mode
		if (!server.isOnlineMode()) return;
		var username = packet.name();
		// check username against offline list
		var allowedAddress = SkipAuth.offlinePlayers.get(packet.name());
		if (allowedAddress != null) {
			var remoteSocketAddress = connection.getAddress();
			InetSocketAddress remoteInetSocketAddress;
			if (remoteSocketAddress instanceof InetSocketAddress) {
				remoteInetSocketAddress = (InetSocketAddress) remoteSocketAddress;
			} else {
				SkipAuth.log.warn("failed to cast remote address, giving up");
				return;
			}

			var remoteInetAddress = remoteInetSocketAddress.getAddress();
			IPAddress remoteAddress;
			if (remoteInetAddress instanceof Inet4Address) {
				remoteAddress = new IPv4Address((Inet4Address) remoteInetAddress);
			} else if (remoteInetAddress instanceof Inet6Address) {
				remoteAddress = new IPv6Address((Inet6Address) remoteInetAddress);
			} else {
				throw new RuntimeException("unknown IP version");
			}

			if (allowedAddress.contains(remoteAddress)) {
				// ok to log in
				SkipAuth.log.info("skipped authentication for offline user {}", username);
				state = ServerLoginNetworkHandler.State.READY_TO_ACCEPT;
			} else {
				SkipAuth.log.warn("address mismatch for offline user {}", username);
				// kick immediately
				disconnect(Text.literal("Address mismatch"));
			}
			ci.cancel();
		}
		// otherwise, proceed as normal
	}
}
