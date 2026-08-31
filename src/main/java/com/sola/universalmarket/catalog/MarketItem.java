package com.sola.universalmarket.catalog;

import org.bukkit.Material;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * One approved entry in the Universal Market catalog.
 *
 * A catalog CANNOT be keyed by Material alone, because several materials carry
 * meaningful component variants (potions, enchanted books, tipped arrows, goat
 * horns). Every entry therefore has a canonical {@link Key}:
 *
 *   minecraft:stone
 *   minecraft:potion#swiftness_long
 *   minecraft:enchanted_book#mending_1
 *   minecraft:tipped_arrow#poison
 *   minecraft:goat_horn#ponder
 *
 * The key is the ONLY thing the market trusts. Client packets are resolved down
 * to a key; anything that does not resolve to an approved key is refused.
 */
public final class MarketItem {

    /** Canonical, immutable identity of a tradeable thing. */
    public record Key(String namespacedMaterial, String variant) {

        public Key {
            namespacedMaterial = namespacedMaterial.toLowerCase(Locale.ROOT);
            variant = (variant == null || variant.isBlank()) ? "" : variant.toLowerCase(Locale.ROOT);
        }

        public static Key of(Material material) {
            return new Key("minecraft:" + material.name().toLowerCase(Locale.ROOT), "");
        }

        public static Key of(Material material, String variant) {
            return new Key("minecraft:" + material.name().toLowerCase(Locale.ROOT), variant);
        }

        /** Parse "minecraft:potion#swiftness_long" back into a key. */
        public static Key parse(String id) {
            if (id == null || id.isBlank()) return null;
            String s = id.trim().toLowerCase(Locale.ROOT);
            int hash = s.indexOf('#');
            if (hash < 0) return new Key(s, "");
            return new Key(s.substring(0, hash), s.substring(hash + 1));
        }

        public boolean hasVariant() {
            return !variant.isEmpty();
        }

        /** The Bukkit Material this key refers to, or null if the server does not know it. */
        public Material material() {
            String name = namespacedMaterial.startsWith("minecraft:")
                    ? namespacedMaterial.substring("minecraft:".length())
                    : namespacedMaterial;
            return Material.getMaterial(name.toUpperCase(Locale.ROOT));
        }

        @Override
        public String toString() {
            return hasVariant() ? namespacedMaterial + "#" + variant : namespacedMaterial;
        }
    }

    // ------------------------------------------------------------------

    private final Key key;
    private final String displayName;
    private final String category;

    /** Price the Universal Market charges the player, per single item, whole dollars. */
    private final BigDecimal umBuyPrice;

    /** Base price the server pays the player, per single item, whole dollars. */
    private final BigDecimal serverBuybackBase;

    /** Suggested range shown to shop owners as guidance only - never enforced. */
    private final BigDecimal suggestedShopMin;
    private final BigDecimal suggestedShopMax;

    // Dynamic pricing (applies to server buyback)
    private final boolean dynamicPricing;
    private final BigDecimal priceFloor;
    private final BigDecimal priceCeiling;

    // Farm / inflation control
    private final int sellLimitTier1;      // units at 100% rate per cycle
    private final int sellLimitTier2;      // next N units at tier2Rate
    private final int sellLimitTier3;
    private final double tier2Rate;
    private final double tier3Rate;
    private final double tierFloorRate;    // everything beyond tier 3

    // Rotation eligibility
    private final boolean dailyDealEligible;
    private final double dailyDealWeight;
    private final double maxDiscount;
    private final boolean highDemandEligible;
    private final double highDemandWeight;
    private final double maxDemandBonus;
    private final boolean contractEligible;

    // Rare goods
    private final boolean rare;
    private final int purchaseLimit;       // <= 0 means unlimited
    private final long purchaseResetTicks; // reset window; 0 uses global default

    private final boolean blacklisted;

    private MarketItem(Builder b) {
        this.key = Objects.requireNonNull(b.key);
        this.displayName = b.displayName;
        this.category = b.category;
        this.umBuyPrice = b.umBuyPrice;
        this.serverBuybackBase = b.serverBuybackBase;
        this.suggestedShopMin = b.suggestedShopMin;
        this.suggestedShopMax = b.suggestedShopMax;
        this.dynamicPricing = b.dynamicPricing;
        this.priceFloor = b.priceFloor;
        this.priceCeiling = b.priceCeiling;
        this.sellLimitTier1 = b.sellLimitTier1;
        this.sellLimitTier2 = b.sellLimitTier2;
        this.sellLimitTier3 = b.sellLimitTier3;
        this.tier2Rate = b.tier2Rate;
        this.tier3Rate = b.tier3Rate;
        this.tierFloorRate = b.tierFloorRate;
        this.dailyDealEligible = b.dailyDealEligible;
        this.dailyDealWeight = b.dailyDealWeight;
        this.maxDiscount = b.maxDiscount;
        this.highDemandEligible = b.highDemandEligible;
        this.highDemandWeight = b.highDemandWeight;
        this.maxDemandBonus = b.maxDemandBonus;
        this.contractEligible = b.contractEligible;
        this.rare = b.rare;
        this.purchaseLimit = b.purchaseLimit;
        this.purchaseResetTicks = b.purchaseResetTicks;
        this.blacklisted = b.blacklisted;
    }

    public Key key() { return key; }
    public String id() { return key.toString(); }
    public String displayName() { return displayName; }
    public String category() { return category; }
    public BigDecimal umBuyPrice() { return umBuyPrice; }
    public BigDecimal serverBuybackBase() { return serverBuybackBase; }
    public BigDecimal suggestedShopMin() { return suggestedShopMin; }
    public BigDecimal suggestedShopMax() { return suggestedShopMax; }
    public boolean dynamicPricing() { return dynamicPricing; }
    public BigDecimal priceFloor() { return priceFloor; }
    public BigDecimal priceCeiling() { return priceCeiling; }
    public int sellLimitTier1() { return sellLimitTier1; }
    public int sellLimitTier2() { return sellLimitTier2; }
    public int sellLimitTier3() { return sellLimitTier3; }
    public double tier2Rate() { return tier2Rate; }
    public double tier3Rate() { return tier3Rate; }
    public double tierFloorRate() { return tierFloorRate; }
    public boolean dailyDealEligible() { return dailyDealEligible && !blacklisted; }
    public double dailyDealWeight() { return dailyDealWeight; }
    public double maxDiscount() { return maxDiscount; }
    public boolean highDemandEligible() { return highDemandEligible && !blacklisted; }
    public double highDemandWeight() { return highDemandWeight; }
    public double maxDemandBonus() { return maxDemandBonus; }
    public boolean contractEligible() { return contractEligible && !blacklisted; }
    public boolean rare() { return rare; }
    public int purchaseLimit() { return purchaseLimit; }
    public long purchaseResetTicks() { return purchaseResetTicks; }
    public boolean blacklisted() { return blacklisted; }

    public Material material() { return key.material(); }

    public static Builder builder(Key key) { return new Builder(key); }

    public static final class Builder {
        private final Key key;
        private String displayName = "";
        private String category = "misc";
        private BigDecimal umBuyPrice = BigDecimal.ZERO;
        private BigDecimal serverBuybackBase = BigDecimal.ZERO;
        private BigDecimal suggestedShopMin = BigDecimal.ZERO;
        private BigDecimal suggestedShopMax = BigDecimal.ZERO;
        private boolean dynamicPricing = true;
        private BigDecimal priceFloor = BigDecimal.ZERO;
        private BigDecimal priceCeiling = BigDecimal.ZERO;
        private int sellLimitTier1 = 128, sellLimitTier2 = 384, sellLimitTier3 = 1024;
        private double tier2Rate = 0.66, tier3Rate = 0.33, tierFloorRate = 0.10;
        private boolean dailyDealEligible = true;
        private double dailyDealWeight = 1.0;
        private double maxDiscount = 0.30;
        private boolean highDemandEligible = true;
        private double highDemandWeight = 1.0;
        private double maxDemandBonus = 0.45;
        private boolean contractEligible = true;
        private boolean rare = false;
        private int purchaseLimit = 0;
        private long purchaseResetTicks = 0L;
        private boolean blacklisted = false;

        Builder(Key key) { this.key = key; }

        public Builder displayName(String v) { this.displayName = v; return this; }
        public Builder category(String v) { this.category = v; return this; }
        public Builder umBuyPrice(BigDecimal v) { this.umBuyPrice = v; return this; }
        public Builder serverBuybackBase(BigDecimal v) { this.serverBuybackBase = v; return this; }
        public Builder suggested(BigDecimal min, BigDecimal max) { this.suggestedShopMin = min; this.suggestedShopMax = max; return this; }
        public Builder dynamicPricing(boolean v) { this.dynamicPricing = v; return this; }
        public Builder floorCeiling(BigDecimal f, BigDecimal c) { this.priceFloor = f; this.priceCeiling = c; return this; }
        public Builder sellTiers(int t1, int t2, int t3) { this.sellLimitTier1 = t1; this.sellLimitTier2 = t2; this.sellLimitTier3 = t3; return this; }
        public Builder tierRates(double r2, double r3, double floor) { this.tier2Rate = r2; this.tier3Rate = r3; this.tierFloorRate = floor; return this; }
        public Builder dailyDeal(boolean eligible, double weight, double maxDisc) { this.dailyDealEligible = eligible; this.dailyDealWeight = weight; this.maxDiscount = maxDisc; return this; }
        public Builder highDemand(boolean eligible, double weight, double maxBonus) { this.highDemandEligible = eligible; this.highDemandWeight = weight; this.maxDemandBonus = maxBonus; return this; }
        public Builder contractEligible(boolean v) { this.contractEligible = v; return this; }
        public Builder rare(boolean v, int limit, long resetTicks) { this.rare = v; this.purchaseLimit = limit; this.purchaseResetTicks = resetTicks; return this; }
        public Builder blacklisted(boolean v) { this.blacklisted = v; return this; }

        public MarketItem build() { return new MarketItem(this); }
    }

    @Override
    public String toString() { return "MarketItem(" + key + ")"; }
}
