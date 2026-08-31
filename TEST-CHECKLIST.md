# UniversalMarket test checklist

Run on a test server first. Items marked **[C]** are the ones I consider
genuinely risky and would test before letting anyone else on.

## Startup
- [ ] Plugin green in `/plugins`
- [ ] All six hook lines present in console, no warnings
- [ ] `/um debug economy` reports `New Economy`
- [ ] `/um debug packetevents` reports ENABLED + listener registered
- [ ] `market.yml` generated; entry count looks sane

## Market Terminal (spec §7)
- [ ] Receive exactly one on first join
- [ ] **[C]** Pick it up with the mouse and hold it for 30+ seconds — no duplicate appears
- [ ] **[C]** While holding it on the cursor, run `/um terminal` — still exactly one
- [ ] Move it between hotbar slots freely
- [ ] Cannot drop (Q, Ctrl+Q), place, or put in chest/barrel/shulker/hopper/furnace
- [ ] Survives death; still present after respawn
- [ ] Cannot be used as a QuickShop product
- [ ] Anvil rename / grindstone / smithing rejected
- [ ] Right-click opens the market

## Creative Market — security (spec §50)
- [ ] `/gamemode` query shows **SURVIVAL** throughout
- [ ] **[C]** Cannot fly (double-tap space) at any point during the session
- [ ] **[C]** Not invulnerable — take fall damage normally
- [ ] Cannot instantly break blocks
- [ ] **[C]** Middle-click clone grants nothing
- [ ] **[C]** Number-key hotbar swap grants nothing and charges nothing
- [ ] Shift-click from catalog grants exactly the paid quantity, never a stack
- [ ] Cannot drop creative items
- [ ] **[C]** Disconnect mid-selection → reconnect with no free item and normal survival client
- [ ] `/um close` restores client state
- [ ] Kill the plugin (`/reload confirm`) mid-session → nobody stuck in fake creative

## Creative Market — purchasing
- [ ] Press E → real vanilla creative screen, real tabs, working search, working scrollbar
- [ ] Selecting Stone buys exactly **1**, not 64
- [ ] Charged the exact catalog price
- [ ] Item received is server-generated and clean (no odd NBT)
- [ ] Action bar balance updates instantly
- [ ] Spawn Egg / Bedrock / Command Block → no item, no charge
- [ ] Full inventory → purchase rejected, **nothing charged**, nothing dropped on the floor
- [ ] Insufficient funds → nothing taken, nothing granted
- [ ] Spam-click one item rapidly → rate gate holds, no double charge

## Selling
- [ ] Valid item sells at correct buyback
- [ ] Renamed dirt rejected
- [ ] Illegally enchanted item rejected
- [ ] Terminal can never be sold
- [ ] Diminishing tiers apply and message fires on tier change
- [ ] Relog does **not** reset sell-limit usage

## QuickShop
- [ ] Create a chest shop; it appears in Find Item within the refresh interval
- [ ] Price and stock correct
- [ ] Sorted cheapest first, player shops listed above UM when cheaper
- [ ] Delete the shop mid-browse → no stale listing, no error spam

## Payments (spec §34)
- [ ] Send $1,000,000 → recipient receives exactly $1,000,000
- [ ] Sender pays exactly $1,074,500 (7.45% fee, charged **once**)
- [ ] Verify NewEconomy did not also apply its own fee
- [ ] Self-payment blocked; negative and junk amounts rejected

## Cycles
- [ ] Daily deal shows discounted price and charges the discounted amount
- [ ] High demand boosts buyback
- [ ] **[C]** Deal + high demand on the same item cannot make buyback exceed buy price
- [ ] Rare item: buy Elytra, second purchase blocked, timer displayed

## Persistence
- [ ] Restart: transactions, sell limits, rare cooldowns and dynamic prices all persist
- [ ] No player left in a broken client gamemode after restart

## Bedrock / PlayStation
- [ ] Floodgate player gets the **form** menu, never the packet workflow
- [ ] Buy / sell / search / shops / pay / leaderboard / account all reachable
- [ ] Controller navigation works throughout
- [ ] **[C]** No Bedrock inventory corruption after 15 minutes of use
