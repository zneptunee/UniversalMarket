package com.sola.universalmarket.creative;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Intercepts the client's creative set-slot packet and reinterprets it as
 * "the player wants to BUY this item type".
 *
 * Signatures used here were verified against the packetevents v2.13.0 tag:
 *   PacketListenerAbstract(PacketListenerPriority)
 *   PacketReceiveEvent#getPlayer() / #setCancelled(boolean)
 *   WrapperPlayClientCreativeInventoryAction(PacketReceiveEvent)
 *   ...#getSlot() : int      ...#getItemStack() : protocol ItemStack
 *   SpigotConversionUtil#toBukkitItemMaterial(ItemType) : org.bukkit.Material
 *
 * SECURITY MODEL - read this before changing anything:
 *
 *  1. The packet is ALWAYS cancelled. There is no code path where a creative
 *     set-slot packet is allowed through. The item the client asked for is
 *     discarded entirely; it is used only as a lookup token for a Material.
 *
 *  2. Only the Material is read. Amount, NBT, components, enchantments, custom
 *     name and lore from the client are never read and never trusted. A spoofed
 *     "64x sharpness 255 netherite sword" resolves to exactly the Material and
 *     is then priced, limit-checked and rebuilt server-side from scratch.
 *
 *  3. Quantity is forced server-side (default 1, spec section 13). The client
 *     cannot influence it.
 *
 *  4. Belt and braces: vanilla's own handler already ignores creative set-slot
 *     packets from a player whose SERVER-side gamemode is not creative, and we
 *     never change the server-side gamemode. So even a total failure of this
 *     listener cannot grant a free item - it would only cause a visual desync,
 *     which the forced resync then corrects.
 */
public final class CreativePacketListener extends PacketListenerAbstract {

    private final CreativeMarketService service;

    public CreativePacketListener(CreativeMarketService service) {
        // HIGHEST so we cancel before anything else can act on it.
        super(PacketListenerPriority.HIGHEST);
        this.service = service;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
            return;
        }

        final Player player = event.getPlayer();
        if (player == null) return;

        CreativeMarketSession session = service.getSession(player.getUniqueId());
        if (session == null) {
            // Not in a market session. The player should not be sending these at
            // all (their server gamemode is survival), so drop it and resync.
            event.setCancelled(true);
            service.scheduleResync(player);
            return;
        }

        // Rule 1: never let this packet reach the server handler.
        event.setCancelled(true);

        final int slot;
        final Material material;
        try {
            WrapperPlayClientCreativeInventoryAction wrapper =
                    new WrapperPlayClientCreativeInventoryAction(event);
            slot = wrapper.getSlot();
            com.github.retrooper.packetevents.protocol.item.ItemStack clientItem = wrapper.getItemStack();

            if (clientItem == null || clientItem.isEmpty()) {
                // Player cleared a slot / dragged something out. Never a purchase.
                // Never charge, never grant. Just put their real inventory back.
                service.scheduleResync(player);
                return;
            }
            material = SpigotConversionUtil.toBukkitItemMaterial(clientItem.getType());
        } catch (Throwable t) {
            // A malformed or unexpected packet must never take the session down.
            service.debug("Malformed creative packet from " + player.getName() + ": " + t);
            service.scheduleResync(player);
            return;
        }

        if (material == null || material == Material.AIR) {
            service.scheduleResync(player);
            return;
        }

        // Everything past here happens on the main thread. Packet listeners run
        // on netty threads and MUST NOT touch Bukkit inventories or economy.
        service.handlePurchaseIntent(player, session, material, slot);
    }
}
