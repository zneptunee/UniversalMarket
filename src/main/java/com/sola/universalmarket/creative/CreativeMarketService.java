package com.sola.universalmarket.creative;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities;
import com.sola.universalmarket.UniversalMarketPlugin;
import com.sola.universalmarket.catalog.MarketCatalog;
import com.sola.universalmarket.catalog.MarketItem;
import com.sola.universalmarket.economy.EconomyService;
import com.sola.universalmarket.util.NumberFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the "client thinks it is creative, server knows it is survival" trick.
 *
 * WHAT IS ACTUALLY POSSIBLE (verified, not guessed):
 *
 *  CAN DO   - Send ClientboundGameEvent CHANGE_GAME_MODE so the client renders the
 *             real vanilla creative inventory, with real tabs, real search, real
 *             scrollbar and real item icons, when the player presses E.
 *  CAN DO   - Intercept and cancel the resulting creative set-slot packet.
 *  CAN DO   - Suppress flight by immediately overwriting the client's abilities.
 *
 *  CANNOT   - Force the creative screen open from the server. There is no
 *             clientbound packet for it. The player must press E. Any plugin
 *             claiming otherwise is opening a fake chest GUI.
 *  CANNOT   - Detect when the creative screen is opened or closed. It is a purely
 *             client-side screen and sends no open/close packet.
 *  CANNOT   - Rewrite hover tooltips of native creative catalog entries. The
 *             catalog is client-generated. Prices are therefore shown in the HUD.
 *  CANNOT   - Show the vanilla hearts/hunger bar while the client is in creative.
 *             The client hides it. We render it into the action bar instead.
 *
 * These four limits are inherent to the vanilla protocol, not implementation gaps.
 */
public final class CreativeMarketService {

    private final UniversalMarketPlugin plugin;
    private final EconomyService economy;
    private final MarketCatalog catalog;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private final Map<UUID, CreativeMarketSession> sessions = new ConcurrentHashMap<>();

    private BukkitTask hudTask;
    private CreativePacketListener packetListener;

    public CreativeMarketService(UniversalMarketPlugin plugin, EconomyService economy, MarketCatalog catalog) {
        this.plugin = plugin;
        this.economy = economy;
        this.catalog = catalog;
    }

    // ==================================================================
    // Lifecycle
    // ==================================================================

    public void register() {
        this.packetListener = new CreativePacketListener(this);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);

        // One shared repeating task for every active session. We do NOT run a
        // per-player timer, and we never scan inventories here.
        long period = Math.max(5L, plugin.getConfig().getLong("creative-market.hud-refresh-ticks", 10L));
        this.hudTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSessions, period, period);
    }

    public void shutdown() {
        if (hudTask != null) hudTask.cancel();
        // Never leave anyone stuck client-side in fake creative.
        for (UUID id : sessions.keySet().toArray(new UUID[0])) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) exitMarket(p, "plugin disable");
            else sessions.remove(id);
        }
        if (packetListener != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            } catch (Throwable ignored) { }
        }
    }

    public CreativeMarketSession getSession(UUID id) {
        return sessions.get(id);
    }

    public boolean inMarket(Player p) {
        return p != null && sessions.containsKey(p.getUniqueId());
    }

    // ==================================================================
    // Enter / exit
    // ==================================================================

    public boolean enterMarket(Player player) {
        if (player == null || !player.isOnline()) return false;
        if (sessions.containsKey(player.getUniqueId())) return true;

        if (plugin.bedrock().isBedrock(player.getUniqueId())) {
            // Spec 15/52: never run the Java packet workflow through Geyser.
            return false;
        }

        CreativeMarketSession session = new CreativeMarketSession(player);
        sessions.put(player.getUniqueId(), session);

        // --- server-side hardening BEFORE we tell the client anything ---
        player.setGameMode(GameMode.SURVIVAL);   // explicit: server side never changes
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(false);
        player.closeInventory();

        // --- now lie to the client, and only the client ---
        sendClientGameMode(player, true);
        suppressClientFlight(player);

        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.6f, 1.4f);
        player.sendMessage(mm.deserialize(plugin.messages().get("creative.entered")));
        debug("Creative market ENTER " + player.getName());
        return true;
    }

    public void exitMarket(Player player, String reason) {
        if (player == null) return;
        CreativeMarketSession session = sessions.remove(player.getUniqueId());
        if (session == null || !session.markClosed()) return;

        try {
            // Tell the client the truth again, using the gamemode the SERVER holds.
            sendClientGameMode(player, false);

            player.setGameMode(session.originalGameMode());
            player.setAllowFlight(session.originalAllowFlight());
            player.setFlying(session.originalAllowFlight() && session.originalFlying());
            player.setInvulnerable(session.originalInvulnerable());

            // Push authoritative abilities and inventory back down.
            suppressClientFlight(player);
            player.updateInventory();
            player.sendActionBar(Component.empty());
            player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, 0.6f, 1.2f);
        } catch (Throwable t) {
            plugin.getLogger().warning("Error restoring " + player.getName()
                    + " from creative market (" + reason + "): " + t);
        }
        debug("Creative market EXIT " + player.getName() + " (" + reason + ") after "
                + session.purchasesThisSession() + " purchases");
    }

    /** Called from quit/kick handlers where the Player object may already be gone. */
    public void forceForget(UUID id) {
        CreativeMarketSession s = sessions.remove(id);
        if (s != null) s.markClosed();
    }

    // ==================================================================
    // Client state packets
    // ==================================================================

    private void sendClientGameMode(Player player, boolean fakeCreative) {
        float value = fakeCreative ? 1f : gameModeId(player.getGameMode());
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerChangeGameState(
                        WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE, value));
    }

    private float gameModeId(GameMode mode) {
        return switch (mode) {
            case CREATIVE -> 1f;
            case ADVENTURE -> 2f;
            case SPECTATOR -> 3f;
            default -> 0f;
        };
    }

    /**
     * The CHANGE_GAME_MODE packet makes the vanilla client re-derive its abilities
     * and grant itself flight. We immediately overwrite that with an authoritative
     * abilities packet, and re-assert it on a timer, so the client never gets to
     * fly. Without this the player would fly locally and then be rubber-banded or
     * kicked by the server's own flight check.
     *
     * Constructor verified: (godMode, flying, flightAllowed, creativeMode, flySpeed, fovModifier)
     */
    private void suppressClientFlight(Player player) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerPlayerAbilities(
                        false,   // godMode      - no invulnerability
                        false,   // flying       - not flying
                        false,   // flightAllowed - MAY NOT FLY
                        false,   // creativeMode - no instant break
                        0.05f,   // default walk-equivalent fly speed
                        0.1f));  // default fov modifier
    }

    public void scheduleResync(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) player.updateInventory();
        });
    }

    // ==================================================================
    // Periodic tick: HUD + safety re-assert
    // ==================================================================

    private void tickSessions() {
        if (sessions.isEmpty()) return;
        long timeoutMs = plugin.getConfig().getLong("creative-market.session-timeout-seconds", 600) * 1000L;

        for (Map.Entry<UUID, CreativeMarketSession> e : sessions.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) { forceForget(e.getKey()); continue; }

            CreativeMarketSession s = e.getValue();
            if (timeoutMs > 0 && s.ageMillis() > timeoutMs) {
                exitMarket(p, "timeout");
                continue;
            }

            // Re-assert safety every tick cycle. Cheap, and closes the window where
            // some other plugin or a vanilla ability resend re-enables flight.
            if (p.getAllowFlight() || p.isFlying() || p.isInvulnerable()) {
                p.setAllowFlight(false);
                p.setFlying(false);
                p.setInvulnerable(false);
                suppressClientFlight(p);
            }
            if (p.getGameMode() != GameMode.SURVIVAL) {
                // Somebody changed their real gamemode mid-session. Bail out safely.
                exitMarket(p, "server gamemode changed");
                continue;
            }
            sendHud(p, s);
        }
    }

    /**
     * Spec 11 + 12: the client hides hearts and hunger while it believes it is in
     * creative, so we rebuild that information plus the live balance in the action
     * bar. This is the honest substitute for an impossible requirement.
     */
    private void sendHud(Player p, CreativeMarketSession s) {
        int hearts = (int) Math.ceil(p.getHealth());
        int maxHearts = (int) Math.ceil(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        String bal = NumberFormatter.money(economy.balance(p));

        String template = plugin.messages().get("creative.hud");
        String text = template
                .replace("%health%", String.valueOf(hearts))
                .replace("%maxhealth%", String.valueOf(maxHearts))
                .replace("%hunger%", String.valueOf(p.getFoodLevel()))
                .replace("%balance%", bal)
                .replace("%qty%", String.valueOf(s.purchaseQuantity()));
        p.sendActionBar(mm.deserialize(text));
    }

    // ==================================================================
    // Purchase
    // ==================================================================

    /**
     * Called from the netty thread. Hops to the main thread, then runs the full
     * validation chain from spec section 13 in order.
     */
    public void handlePurchaseIntent(Player player, CreativeMarketSession session, Material material, int slot) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                doPurchase(player, session, material);
            } catch (Throwable t) {
                plugin.getLogger().severe("Purchase failure for " + player.getName() + ": " + t);
                t.printStackTrace();
            } finally {
                if (player.isOnline()) player.updateInventory();
            }
        });
    }

    private void doPurchase(Player player, CreativeMarketSession session, Material material) {
        if (!player.isOnline() || session.isClosed()) return;

        long gate = plugin.getConfig().getLong("creative-market.min-purchase-interval-millis", 200L);
        if (!session.tryPurchaseGate(gate)) {
            debug("Rate limited purchase from " + player.getName());
            return;
        }

        // Step 2: resolve to an APPROVED catalog entry. Material only.
        MarketItem item = catalog.byMaterial(material);
        if (item == null || item.blacklisted()) {
            deny(player, plugin.messages().get("buy.not-for-sale")
                    .replace("%item%", pretty(material)));
            return;
        }

        // Step 3/4: quantity is ours, not the client's.
        int qty = session.purchaseQuantity();

        // Step 6: current price including any daily deal.
        BigDecimal unit = plugin.pricing().currentBuyPrice(item);
        BigDecimal total = NumberFormatter.toWholeDollars(unit.multiply(BigDecimal.valueOf(qty)));
        if (total.signum() <= 0) {
            deny(player, plugin.messages().get("buy.not-for-sale").replace("%item%", pretty(material)));
            return;
        }

        // Step 8: rare goods allowance.
        if (item.rare()) {
            var check = plugin.rareGoods().checkAllowance(player.getUniqueId(), item, qty);
            if (!check.allowed()) {
                deny(player, plugin.messages().get("buy.rare-limit")
                        .replace("%item%", item.displayName())
                        .replace("%time%", NumberFormatter.duration(check.resetInMillis())));
                return;
            }
        }

        // Step 7: funds.
        BigDecimal balance = economy.balance(player);
        if (balance.compareTo(total) < 0) {
            deny(player, plugin.messages().get("buy.insufficient")
                    .replace("%item%", item.displayName())
                    .replace("%price%", NumberFormatter.money(total))
                    .replace("%balance%", NumberFormatter.money(balance)));
            return;
        }

        // Step 9: inventory space. Spec 72 - never drop expensive items on the floor.
        ItemStack stack = catalog.createApprovedStack(item, qty);
        if (stack == null) {
            deny(player, plugin.messages().get("buy.not-for-sale").replace("%item%", pretty(material)));
            return;
        }
        if (!hasSpaceFor(player, stack)) {
            deny(player, plugin.messages().get("buy.inventory-full"));
            return;
        }

        // Step 10: withdraw. Atomic-ish: money first, then item, refund on failure.
        if (!economy.withdraw(player, total)) {
            deny(player, plugin.messages().get("buy.economy-error"));
            return;
        }

        // Step 11/12: server-built stack only.
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            // Could not fully deliver: roll the money back and drop nothing.
            for (ItemStack rem : leftover.values()) player.getInventory().removeItem(rem);
            if (!economy.deposit(player, total)) {
                plugin.getLogger().severe("ROLLBACK FAILED refunding " + NumberFormatter.exactMoney(total)
                        + " to " + player.getName() + " for " + item.id()
                        + " - manual correction required.");
            }
            deny(player, plugin.messages().get("buy.inventory-full"));
            return;
        }

        // Step 13/14.
        if (item.rare()) plugin.rareGoods().recordPurchase(player.getUniqueId(), item, qty);
        plugin.transactions().recordPurchase(player.getUniqueId(), item.id(), qty, total);
        plugin.pricing().onPlayerBought(item, qty);
        session.incrementPurchases();
        session.lastItemId(item.id());

        BigDecimal after = economy.balance(player);
        player.sendMessage(mm.deserialize(plugin.messages().get("buy.success")
                .replace("%qty%", String.valueOf(qty))
                .replace("%item%", item.displayName())
                .replace("%price%", NumberFormatter.money(total))
                .replace("%balance%", NumberFormatter.money(after))));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.6f);

        // Spec 61: nudge toward the player economy when it is genuinely cheaper.
        if (plugin.getConfig().getBoolean("player-shops.warn-on-cheaper-player-shop", true)) {
            plugin.playerShops().cheapestFor(item).ifPresent(listing -> {
                if (listing.pricePerItem().compareTo(unit) < 0) {
                    player.sendMessage(mm.deserialize(plugin.messages().get("buy.cheaper-elsewhere")
                            .replace("%owner%", listing.ownerName())
                            .replace("%price%", NumberFormatter.money(listing.pricePerItem()))));
                }
            });
        }

        debug(player.getName() + " bought " + qty + "x " + item.id()
                + " for " + NumberFormatter.exactMoney(total));
    }

    private void deny(Player player, String message) {
        player.sendMessage(mm.deserialize(message));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
        scheduleResync(player);
    }

    /**
     * Space check that accounts for partially filled compatible stacks, so buying
     * 1 stone with 63 stone in a slot succeeds instead of being falsely rejected.
     */
    private boolean hasSpaceFor(Player player, ItemStack stack) {
        int needed = stack.getAmount();
        int max = stack.getMaxStackSize();
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (ItemStack slot : contents) {
            if (slot == null || slot.getType() == Material.AIR) {
                needed -= max;
            } else if (slot.isSimilar(stack)) {
                needed -= Math.max(0, max - slot.getAmount());
            }
            if (needed <= 0) return true;
        }
        return needed <= 0;
    }

    private String pretty(Material m) {
        String n = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    public void debug(String msg) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[creative] " + msg);
        }
    }
}
