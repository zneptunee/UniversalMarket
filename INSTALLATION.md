# Building and installing UniversalMarket

## A. Get a jar (pick one)

### Option 1 — GitHub Actions (recommended, no local setup)
1. Create a new **private** GitHub repo.
2. Upload the contents of this folder, keeping `.github/workflows/build.yml` in place.
3. Push. Open **Actions → Build UniversalMarket**.
4. When it finishes, download the **UniversalMarket-jar** artifact.

The `dependency:tree` step runs before compilation, so if a version pin is wrong
you get a clear resolution error naming the exact artifact rather than a wall of
compile errors.

### Option 2 — Local build
Requires JDK 21 and Maven 3.9+.
```bash
mvn clean package
# -> target/UniversalMarket.jar
```

## B. If a dependency fails to resolve

Every version is pinned in one place: the `<properties>` block of `pom.xml`.

| Property | Value | If it fails |
|---|---|---|
| `paper.version` | `1.21.11-R0.1-SNAPSHOT` | Check the exact string in repo.papermc.io; Paper occasionally lags a patch release |
| `packetevents.version` | `2.13.0` | Must match your installed PacketEvents build exactly |
| `floodgate.version` | `2.2.5-SNAPSHOT` | Floodgate only publishes SNAPSHOTs; this moves |
| `vaultapi.version` | `1.7.1` | Stable, rarely changes |

## C. Server install

**Delete first** (per your request):
- any previous `UniversalMarket*.jar`
- any previous `CreativeMarketBridge*.jar`
- their `plugins/UniversalMarket/` and `plugins/CreativeMarketBridge/` folders

**Keep** (all are integrated with, none are replaced):
NewEconomy 5.1.0, VaultUnlocked 2.20.2, QuickShop-Hikari 6.3.0.1,
PacketEvents 2.13.0, Geyser-Spigot, Floodgate, ViaVersion, LuckPerms,
CoreProtect, HuskHomes, Chunky.

Then:
1. Drop `UniversalMarket.jar` into `plugins/`.
2. Start the server. Watch for the diagnostic block:
```
[UniversalMarket] Paper 1.21.11 detected
[UniversalMarket] Vault economy: New Economy
[UniversalMarket] QuickShop-Hikari 6.3.0.1 hooked
[UniversalMarket] PacketEvents 2.13.0 hooked
[UniversalMarket] Floodgate detected
[UniversalMarket] Loaded N approved market entries
[UniversalMarket] Database ready
```
3. Stop the server, edit configs, start again.

`plugin.yml` declares `depend: [packetevents]`, so Paper guarantees PacketEvents
loads first. Do not remove that.

## D. Files to edit before launch

| File | Why |
|---|---|
| `config.yml` → `number-format.abbreviate-at` | Resolves the §5 vs §20 formatting conflict |
| `config.yml` → `economy.payment-fee-percent` | Defaults to 7.45 |
| `config.yml` → `rare-goods.reset-mode` | `MINECRAFT_DAYS` (default) or `REAL_HOURS` |
| `config.yml` → `terminal.material` | Defaults to `RECOVERY_COMPASS` |
| `market.yml` | Generated on first start; tune prices here, never in Java |

`sqlite-jdbc` is pulled at load time via Paper's `libraries:` block rather than
shaded, so the jar stays small and there is no JDBC classloader conflict. The
server needs outbound internet on first start only.
