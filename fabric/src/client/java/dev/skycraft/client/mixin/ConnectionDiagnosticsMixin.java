package dev.skycraft.client.mixin;

import dev.skycraft.client.NetworkDiagnostics;
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
	@Unique private final long skycraft$traceId = NetworkDiagnostics.nextId();
	@Inject(method = "channelActive", at = @At("TAIL"))
	private void skycraft$active(ChannelHandlerContext context, CallbackInfo ci) { skycraft$log("channel_active", context, null); }
	@Inject(method = "channelInactive", at = @At("HEAD"))
	private void skycraft$inactive(ChannelHandlerContext context, CallbackInfo ci) { skycraft$log("channel_inactive", context, null); }
	@Inject(method = "exceptionCaught", at = @At("HEAD"))
	private void skycraft$error(ChannelHandlerContext context, Throwable error, CallbackInfo ci) { skycraft$log("channel_error", context, error); }
	@Unique private void skycraft$log(String event, ChannelHandlerContext context, Throwable error) {
		NetworkDiagnostics.channel(event, skycraft$traceId, context.channel().remoteAddress(), context.channel().localAddress(), getReceiving().name(), error);
	}
}
