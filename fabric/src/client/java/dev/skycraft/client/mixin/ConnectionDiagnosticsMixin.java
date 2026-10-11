package dev.skycraft.client.mixin;

import dev.skycraft.client.NetworkDiagnostics;
import dev.skycraft.client.NetworkClient;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Channel lifecycle only: never reads, decodes or records gameplay/authentication packets. */
@Mixin(Connection.class)
public abstract class ConnectionDiagnosticsMixin {
	@Shadow public abstract PacketFlow getReceiving();
	@Shadow public abstract net.minecraft.network.PacketListener getPacketListener();
	@Shadow public abstract boolean isMemoryConnection();
	@Unique private final long skycraft$traceId = NetworkDiagnostics.nextId();
	@Inject(method = "channelActive", at = @At("TAIL"))
	private void skycraft$active(ChannelHandlerContext context, CallbackInfo ci) { skycraft$log("channel_active", context, null); }
	@Inject(method = "channelInactive", at = @At("HEAD"))
	private void skycraft$inactive(ChannelHandlerContext context, CallbackInfo ci) { skycraft$log("channel_inactive", context, null); }
	@Inject(method = "exceptionCaught", at = @At("HEAD"))
	private void skycraft$error(ChannelHandlerContext context, Throwable error, CallbackInfo ci) {
		skycraft$log("channel_error", context, error);
		if (!isMemoryConnection() && getReceiving() == PacketFlow.CLIENTBOUND && NetworkClient.activeAttempt() != 0) {
			Throwable cause = error;
			for (int depth = 0; depth < 8 && cause.getCause() != null && cause.getCause() != cause; depth++) cause = cause.getCause();
			NetworkClient.recordFailure("network_exception", cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause.getClass().getSimpleName());
		}
	}
	@Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
	private void skycraft$disconnect(net.minecraft.network.DisconnectionDetails details, CallbackInfo ci) {
		if (isMemoryConnection()) return;
		var listener = getPacketListener();
		String name = listener == null ? "unknown" : listener.getClass().getSimpleName();
		String phase = name.contains("Login") || name.contains("Handshake") ? "login"
			: name.contains("Configuration") ? "configuration" : name.contains("PacketListener") ? "play" : "unknown";
		String reason = dev.skycraft.network.DiagnosticText.safe(details.reason().getString());
		String code = details.reason().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translated
			? translated.getKey() : "literal";
		NetworkDiagnostics.event("connection_disconnect", java.util.Map.of("connection_id", skycraft$traceId,
			"attempt_id", NetworkClient.activeAttempt(), "receiving", getReceiving().name(), "phase", phase,
			"reason", reason, "reason_code", code));
		if (getReceiving() == PacketFlow.CLIENTBOUND && NetworkClient.activeAttempt() != 0)
			NetworkClient.recordFailure(phase, reason, code);
	}
	@Unique private void skycraft$log(String event, ChannelHandlerContext context, Throwable error) {
		NetworkDiagnostics.channel(event, skycraft$traceId, context.channel().remoteAddress(), context.channel().localAddress(), getReceiving().name(), error);
	}
}
