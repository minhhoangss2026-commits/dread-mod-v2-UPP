package vn.dread.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import vn.dread.DreadMod;

public class DreadClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(DreadMod.FLAYED, FlayedRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(DreadMod.PUZZLE_PACKET, (client, handler, buf, sender) -> {
            int mask = buf.readInt();
            boolean ending = buf.readBoolean();
            client.execute(() -> client.setScreen(ending ? new EndScreen() : new PuzzleScreen(mask)));
        });
    }
}
