package dev.skycraft.client.mixin;

import dev.skycraft.client.NetworkClient;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Only SkyCraft's active local bridge changes the advertised login endpoint. */
@Mixin(Connection.class)
public abstract class ConnectionHandshakeMixin {
	@Shadow public abstract java.net.SocketAddress getRemoteAddress();

	@ModifyVariable(method = "initiateServerboundConnection", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private String skycraft$originalHost(String host) {
		var endpoint = NetworkClient.handshakeEndpoint(getRemoteAddress());
		return endpoint == null ? host : endpoint.host();
	}

	@ModifyVariable(method = "initiateServerboundConnection", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int skycraft$originalPort(int port) {
		var endpoint = NetworkClient.handshakeEndpoint(getRemoteAddress());
		return endpoint == null ? port : endpoint.port();
	}
}
