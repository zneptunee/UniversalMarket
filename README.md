# UniversalMarket

Paper 1.21.11 / Java 21 market plugin for a long-term survival server.

---

## 1. Read this first: there is no jar in this delivery

You asked for a compiled `.jar`. I could not produce one, and I want to be exact
about why rather than hand you something that looks finished and breaks.

Building a Paper plugin requires downloading the real API artifacts at compile
time. My build environment allows outbound network access to a fixed allowlist
(GitHub raw, PyPI, npm, Ubuntu archives). **Every Maven repository this project
needs is blocked.** Tested directly:

| Repository | Needed for | Result |
|---|---|---|
| `repo.papermc.io` | paper-api 1.21.11 | `403 host_not_allowed` |
| `repo1.maven.org` | sqlite-jdbc, transitives | `403 host_not_allowed` |
| `repo.codemc.io` | packetevents 2.13.0 | `403 host_not_allowed` |
| `repo.opencollab.dev` | Floodgate / Cumulus | `403 host_not_allowed` |
| `jitpack.io` | VaultAPI | `403 host_not_allowed` |

The only way to produce a jar here would be to compile against hand-written
Bukkit stubs. That is **precisely** the thing that caused your three previous
`NoSuchMethodError` crashes (`Economy.getBalance`, `ItemStack.setItemMeta`,
`PlayerInventory.addItem`). A stub-built jar would install cleanly, go green in
`/plugins`, and then explode the first time a player clicked something. Shipping
that would have been worse than shipping nothing.

**What you get instead:** a source project whose build is pinned to the correct
repositories and versions, plus a GitHub Actions workflow that compiles it
against the genuine APIs and hands you the jar. See `INSTALLATION.md` — it takes
about three minutes and needs no local Java install.

---

## 2. What I did verify

GitHub raw *was* reachable, so rather than guessing at signatures I pulled the
actual API source at the exact versions you run and read the declarations.
Everything below is copied from real source, not from memory:

**Vault** (`MilkBowl/VaultAPI`, `Economy.java`)
```java
double         getBalance(OfflinePlayer player);          // line 127
EconomyResponse withdrawPlayer(OfflinePlayer player, double amount);  // line 189
EconomyResponse depositPlayer(OfflinePlayer player, double amount);   // line 220
```
There is **no** `getBalance(Player)` overload. Your old plugin compiled one
against a stub. Because `Player extends OfflinePlayer`, calling
`getBalance(player)` against the real artifact binds correctly to the
`OfflinePlayer` descriptor — which is why compiling against the real jar fixes
this class of bug permanently.

**PacketEvents** (tag `v2.13.0`, verified — not `master`)
```java
PacketListenerAbstract(PacketListenerPriority priority)
PacketReceiveEvent#getPlayer()  /  #setCancelled(boolean)
WrapperPlayClientCreativeInventoryAction(PacketReceiveEvent)
  ...#getSlot() : int      ...#getItemStack() : protocol ItemStack
WrapperPlayServerChangeGameState(Reason reason, float value)   // Reason.CHANGE_GAME_MODE
WrapperPlayServerPlayerAbilities(boolean godMode, boolean flying,
        boolean flightAllowed, boolean creativeMode, float flySpeed, float fovModifier)
PacketEvents.getAPI().getPlayerManager().sendPacket(Object player, PacketWrapper<?>)
PacketEvents.getAPI().getEventManager().registerListener(PacketListenerCommon)
```
This uses a proper typed `PacketListenerAbstract` subclass — no dynamic proxies,
which is what produced your `IllegalAccessException` and the
`PacketListenerCommon.getPriority()` null crash.

**Floodgate / Cumulus**
```java
static FloodgateApi getInstance()
boolean isFloodgatePlayer(UUID uuid)
boolean sendForm(UUID uuid, Form form)
SimpleForm.builder().title(..).content(..).button(String, Consumer<SimpleFormResponse>)
```

**QuickShop-Hikari — an important finding.** I checked `master` first and found
`Shop` declared as:
```java
public interface Shop<U, L> extends Locatable<L>, ShopInventory, ShopMeta<U>,
        ShopTrading, ShopDisplay, ShopPermission, ShopExtraHolder
```
That generic split shape is **already present at tag `6.3.0.1`**, and it differs
from 6.2.x, with further movement toward 6.4.0.0 (several methods are marked
`@Deprecated(forRemoval = true, since = "6.3.0.0")`, including `getPrice()`).

Because of that, `QuickShopAdapter` is the **one** deliberate exception to your
"prefer typed APIs" rule: it binds reflectively at runtime, logs exactly which
methods it resolved, and cleanly disables Player Shops if the shape changes —
instead of hard-failing with `NoSuchMethodError` after a QuickShop update. It
only ever *reads* (`getAllShops`, `getItem`, `getPrice`, `getRemainingStock`,
`getOwner`, `bukkitLocation`). It never performs a transaction. Everything else
in the plugin is typed.

---

## 3. Honest status of the code

This is a large specification — realistically several weeks of professional
plugin work. What is in this repo is the **verified foundation plus the two
highest-risk subsystems written in full**, not the entire 45-class plugin.

**Complete and reviewed:**

| File | What it covers |
|---|---|
| `pom.xml` | Correct repos + pinned versions; nothing shaded |
| `util/NumberFormatter.java` | Spec §5 formatter — **50/50 unit tests pass** |
| `catalog/MarketItem.java` | Canonical `Key` system (§16) + all §21 price fields |
| `creative/CreativeMarketSession.java` | Session state object (§62) |
| `creative/CreativePacketListener.java` | Packet interception + security model (§13) |
| `creative/CreativeMarketService.java` | Client gamemode spoof, flight suppression, HUD, purchase chain |
| `terminal/TerminalService.java` | Terminal with the duplication bug fixed at the root (§7) |
| `plugin.yml`, `config.yml`, `messages.yml` | Full configuration surface |
| `.github/workflows/build.yml` | CI that produces the jar |

**Specified in config and referenced by the above, but not yet written:**
`EconomyService`, `MarketCatalog` + `CatalogGenerator`, `ItemValidator`,
`PricingService`, `SellLimitService`, `RareGoodsService`, `ContractService`,
`LeaderboardService`, `TransactionService`, `StorageService`,
`QuickShopAdapter`, `PlayerShopService`, `BedrockService`, the GUI layer,
`UMCommand`, and the Bukkit safety listeners.

**So this repo will not compile as-is.** The written classes reference the
unwritten ones. I would rather tell you that plainly than pad it with 4,000
lines of unverified filler and let you find out during a build.

The two files I prioritised are the ones where a mistake is expensive and where
the design reasoning matters most: the creative-market security model, and the
terminal anti-duplication fix.

---

## 4. Hard vanilla limitations (§51 — not implementation gaps)

Four things in your spec are impossible server-side. You explicitly asked me not
to pretend otherwise:

1. **The server cannot force-open the creative screen.** No clientbound packet
   exists. The player must press `E`. Anything that claims to auto-open it is
   showing you a fake chest GUI. Handled per §10: enter the mode, then
   `Press E to browse the Universal Market`.

2. **The server cannot detect when the creative screen opens or closes.** It is
   purely client-side and sends no window open/close packet. Sessions therefore
   end on explicit exit, timeout, death, disconnect or world change — not on
   screen close.

3. **The server cannot rewrite native creative catalog tooltips.** The catalog is
   client-generated. Prices go in the action bar HUD instead (§12).

4. **The vanilla hearts/hunger bar cannot be shown while the client believes it
   is creative.** The client hides it. Rebuilt in the action bar (§11):
   `♥ 18/20 | 🍖 17/20 | $18.42M`

**One genuinely good piece of news:** vanilla's own
`handleSetCreativeModeSlot` already ignores creative slot packets from a player
whose *server-side* gamemode is not creative. Since we never change the real
gamemode, even a total failure of our packet listener cannot grant a free item —
it could only cause a visual desync, which the forced resync corrects. The
security model has a vanilla backstop underneath it.

---

## 5. Spec conflict you should decide

§5 says abbreviate at `100,000`, which renders 70,000 as `$70,000`.
§20 writes that same value as `$70K`. Both cannot hold.
Default follows §5 (the explicit rule); set `number-format.abbreviate-at: 10000`
in `config.yml` for the §20 look. The formatter is tested against both.

---

## 6. Architecture

```
UniversalMarketPlugin          bootstrap, diagnostics, service wiring
├── util/NumberFormatter       THE money formatter - nothing else formats currency
├── catalog/                   MarketItem + Key, catalog generation, item validation
├── economy/EconomyService     Vault adapter; BigDecimal in, whole dollars to Vault
├── market/                    pricing, deals, demand, sell limits, rare goods, contracts
├── transaction/               atomic ops + history
├── storage/                   async SQLite
├── shops/                     reflective QuickShop adapter + cached shop index
├── terminal/                  soulbound terminal
├── creative/                  fake-creative session, packet listener, safety, HUD
├── bedrock/                   Floodgate detection + Cumulus forms
├── ui/                        Java chest GUIs
└── command/                   /um and admin subcommands
```

Money rule enforced throughout: `BigDecimal` for all arithmetic, rounded **down**
to whole dollars immediately before any Vault call, so the plugin never
overcharges through rounding.
