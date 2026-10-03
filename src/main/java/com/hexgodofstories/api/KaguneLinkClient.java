package com.hexgodofstories.api;

import com.hexgodofstories.client.ClientState;
import net.minecraft.world.entity.Entity;

/**
 * What HexKagunes asks this mod on a client, when both are installed: a contract like {@link KaguneLink}'s, looked up
 * by name and called through a method handle, never loaded on a dedicated server.
 */
public final class KaguneLinkClient {
    private KaguneLinkClient() {}

    /** Held completely still on this client (stopped in time, frozen): its kagune is drawn still with it. */
    public static boolean suspended(Entity e) {return ClientState.suspended(e);}
}
