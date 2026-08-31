Upstream API sources fetched from GitHub at the exact versions this server runs,
used to verify every signature in the plugin instead of guessing:

  vault_Economy.java   MilkBowl/VaultAPI @ master
  pe_*.java            retrooper/packetevents @ tag v2.13.0
  qs_*.java            Ghost-chu/QuickShop-Hikari @ tag 6.3.0.1

Key confirmations:
  * Vault has getBalance(OfflinePlayer), NOT getBalance(Player).
  * PacketEvents 2.13.0 wrapper accessors are getSlot():int, getItemStack():ItemStack.
  * WrapperPlayServerPlayerAbilities(godMode, flying, flightAllowed, creativeMode, flySpeed, fov).
  * QuickShop 6.3.0.1 Shop is already the generic Shop<U,L> split-interface form,
    which is why QuickShopAdapter binds reflectively.
