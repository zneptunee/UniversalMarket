package com.sola.universalmarket.creative;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * All state for one player's Creative-Market browsing session.
 *
 * Deliberately a single object rather than scattered booleans, so that teardown
 * is one call and cannot half-happen. If this object exists in the manager map,
 * the player's CLIENT believes it is in creative; if it does not, the client is
 * survival. There is no third state.
 */
public final class CreativeMarketSession {

    private final UUID playerId;
    private final String playerName;
    private final long startedAt;

    /** The player's REAL server-side gamemode, captured before we lied to the client. */
    private final GameMode originalGameMode;
    private final boolean originalAllowFlight;
    private final boolean originalFlying;
    private final boolean originalInvulnerable;

    /** Guards teardown so cleanup can never run twice (disconnect + close racing). */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Purchase quantity for this session. Spec 13: default is exactly 1. */
    private volatile int purchaseQuantity = 1;

    /** Anti-double-fire: timestamp of last accepted purchase packet. */
    private volatile long lastPurchaseAt = 0L;

    /** Last item the player looked at, for the price readout. */
    private volatile String lastItemId = null;

    private volatile int purchasesThisSession = 0;

    public CreativeMarketSession(Player player) {
        this.playerId = player.getUniqueId();
        this.playerName = player.getName();
        this.startedAt = System.currentTimeMillis();
        this.originalGameMode = player.getGameMode();
        this.originalAllowFlight = player.getAllowFlight();
        this.originalFlying = player.isFlying();
        this.originalInvulnerable = player.isInvulnerable();
    }

    public UUID playerId() { return playerId; }
    public String playerName() { return playerName; }
    public long startedAt() { return startedAt; }
    public long ageMillis() { return System.currentTimeMillis() - startedAt; }

    public GameMode originalGameMode() { return originalGameMode; }
    public boolean originalAllowFlight() { return originalAllowFlight; }
    public boolean originalFlying() { return originalFlying; }
    public boolean originalInvulnerable() { return originalInvulnerable; }

    public int purchaseQuantity() { return purchaseQuantity; }
    public void purchaseQuantity(int q) { this.purchaseQuantity = Math.max(1, Math.min(64, q)); }

    public String lastItemId() { return lastItemId; }
    public void lastItemId(String id) { this.lastItemId = id; }

    public int purchasesThisSession() { return purchasesThisSession; }
    public void incrementPurchases() { this.purchasesThisSession++; }

    /**
     * Rate limit. Returns true if a purchase is allowed right now.
     *
     * Vanilla sends one creative-set-slot packet per affected slot, so a single
     * drag can produce two packets in the same tick (source slot cleared, target
     * slot set). Combined with cancelling every packet and forcing quantity, this
     * closes the double-click duplication vector.
     */
    public boolean tryPurchaseGate(long minIntervalMillis) {
        long now = System.currentTimeMillis();
        if (now - lastPurchaseAt < minIntervalMillis) return false;
        lastPurchaseAt = now;
        return true;
    }

    /** Returns true exactly once, for whichever caller closes the session first. */
    public boolean markClosed() {
        return closed.compareAndSet(false, true);
    }

    public boolean isClosed() {
        return closed.get();
    }
}
