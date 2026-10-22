# CoolWips Economy 1.5.0

Simple Paper 26.2 economy plugin for CoolWips SMP.

Requirements:
- Paper 26.2
- Java 25+
- DiscordSRV 1.30.5+
- UnbelievaBoat API token

Install:
1. Build with build.bat.
2. Replace the old economy JAR in `plugins/` with `target/CoolWipsEconomy-1.5.0.jar` (keep only one version).
3. Start the server once.
4. Open plugins/CoolWipsEconomy/config.yml.
5. Put your UnbelievaBoat API token in api-token.
6. Confirm guild-id is 1025178490481418280.
7. Restart the server.

Commands:
- /sell (opens a chest menu that automatically sells deposited items)
- /sell <item> [amount]
- /sellall <item>
- /prices [page]
- /balance
- /history
- /buy <item> [amount]
- /buymob <mob> [amount] (alias: /mobshop)
- /rename <new name> (paid; supports `&` color codes)
- /repair (paid; repairs damaged item in main hand)
- /shop [page]
- /pay <player> <item> [amount]
- /sellchest (also opens the sell menu; requires the sell permission)
- /sellchest create|remove|status (physical chest management)
- /cweconomy reload
- /cweconomy status
- /cweconomy stats
- /cweconomy audit
- /cweconomy market <player> [item] (operator)
- /cweconomy resetmarket <player> [item] (operator)
- /cweconomy maintenance on|off|block <item>|unblock <item>|status (operator)
- `/cweco block` and `/cweco unblock` are shortcuts for blocking/unblocking all selling; `/cweco block <item>` and `/cweco unblock <item>` control one item.

Players must already be linked through DiscordSRV. The plugin uses the linked Discord ID for UnbelievaBoat.

Important: never commit a real API token to a public GitHub repository.

Paid item services: `/rename <new name>` renames the item in your main hand (supports `&` color codes), and `/repair` fully repairs a damaged item in your main hand. Both start at $10,000 for normal items, with higher fees for valuable items ($15,000), diamond items ($20,000), rare items such as netherite gear, elytra-adjacent gear, tridents, totems, beacons, and shulker boxes ($30,000), and legendary items such as elytra, maces, dragon eggs, enchanted golden apples, heavy cores, and the Silence trim template ($50,000). Base prices are configurable with `settings.rename-cost` and `settings.repair-cost`; rarity tier prices are configurable under `settings.item-service-costs`. On upgrade, old default fees are migrated to $10,000 automatically while custom values are preserved. Successful purchases are recorded in the economy ledger, and the plugin reverses payment if the held item changes before the service is applied.

Mob purchases give custom, tagged paper tokens for later use. Right-click a block to spawn one purchased mob at that spot; the token is consumed only after a successful spawn. All tokens use paper rather than real spawn eggs, so players cannot turn mob tokens into spawner types for unlimited spawning. The plugin also rejects token use directly on normal or trial spawners, including older tokens issued as spawn eggs. Purchases use the same UnbelievaBoat balance verification, per-player transaction lock, and refund path as item purchases. Mob prices are configured under `mob-shop`; `/buymob` lists available mobs, and purchases are recorded in the economy statistics and ledger.

Sales and purchases are serialized per player. The configured buy tax is applied to shop purchases. Player trades preserve the full ItemStack metadata. Items are removed only after UnbelievaBoat confirms payment. If the items disappear before removal, the plugin attempts to reverse the payment.

Edit `prices.txt` and `shop.txt` in this folder on GitHub, then run `/cweconomy reload`. The legacy `prices/` YAML folder is no longer used and has been removed.

Sell chests: use `/sellchest create` while looking at a chest. Put items with a sell price inside, then close the chest. The priced items are automatically sold for the normal `/sell` price after tax; items without a sell price stay in the chest. Only the owner can open or break the sell chest (operators can manage it).

Sell menu: `/sell` or `/sellchest` opens a private 27-slot chest. Place, shift-click, or drag the items you want to sell into it; the plugin automatically sells batches after the movement finishes. It uses your normal market prices, tax, blocked-item settings, Discord link, and per-sale limits. There is no confirmation button: putting an item in the menu authorizes its sale. Existing `/sell <item> [amount]`, `/sell confirm`, and `/sellall <item>` commands still work. Unpriced or blocked items, batches below $1 after tax, and items beyond the batch limits are returned on close. If the inventory is full, returned items drop at the player; if a payment fails after logout, they drop at the menu's opening location. Plugin shutdown returns items still in open menus without starting payments.

Menu payment recovery: each batch writes its exact ItemStacks, player/account IDs, payout and sale ID into `plugins/CoolWipsEconomy/pending-menu-sales/` before contacting the bank. Normal confirmed completion removes this record. If a payment is ambiguous or shutdown interrupts it, the record remains and startup warns staff. Check the bank transaction (including the sale ID in its reason) before restoring any listed items; do not automatically replay these records or retry payments. A `bank-result: APPLIED` record means the bank confirmed payment; `UNKNOWN` or no result requires checking the bank. Back up these records along with the plugin data.

Version 1.5.0 also prevents server-thread commands from reentering an in-flight transaction, keeps player-trade items in escrow until the seller payment result is known, and fixes connected-chest ownership and cancelled-break handling. Build workflows now test branch/PR code and publish JARs only from current `main` source.

Pending player-trade escrow is also saved in `plugins/CoolWipsEconomy/pending-trades/` before the seller credit starts. If shutdown interrupts delivery, staff must check both accounts and the recorded seller payment result before delivering escrow or issuing any refund. Resolved delivery/rollback removes the record. These recovery records are for staff reconciliation, not automatic replay.

Development checks: run `mvn clean verify` with Java 25. Regression tests cover sell-menu lifecycle, transaction exclusion, physical chest protection and trade escrow. Test the menu on a staging Paper server with DiscordSRV and a test bank account before replacing a production plugin.

### Economy integrity

The dynamic market is tracked independently for each player and item. One player's high-volume farm cannot lower another player's sell price. The plugin keeps a high-water mark for each player/item so failed rollbacks cannot raise a farm's price unexpectedly during the same day. Crafting, stonecutting, and cooking conversions are audited at startup/reload so configured sell prices cannot create a simple conversion loop; shop prices are also checked with exact decimal arithmetic.

Confirmed server money creation/removal and player-to-player transfers are recorded in the daily economy statistics and a bounded economy-ledger.log file in the plugin data folder. Use /cweconomy stats and /cweconomy audit for routine checks.

Performance note: economy statistics, transaction ledger, and dynamic-market persistence use batched asynchronous disk writes during gameplay; shutdown still performs a final synchronous save.
