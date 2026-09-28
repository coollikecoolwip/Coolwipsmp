# CoolWips Economy

Simple Paper 26.2 economy plugin for CoolWips SMP.

Requirements:
- Paper 26.2
- Java 25+
- DiscordSRV 1.30.5+
- UnbelievaBoat API token

Install:
1. Build with build.bat.
2. Put the built `target/CoolWipsEconomy-1.0.10.jar` in `plugins/`.
3. Start the server once.
4. Open plugins/CoolWipsEconomy/config.yml.
5. Put your UnbelievaBoat API token in api-token.
6. Confirm guild-id is 1025178490481418280.
7. Restart the server.

Commands:
- /sell <item> [amount]
- /sellall <item>
- /prices [page]
- /balance
- /history
- /buy <item> [amount]
- /shop [page]
- /pay <player> <item> [amount]
- /cweconomy reload
- /cweconomy status

Players must already be linked through DiscordSRV. The plugin uses the linked Discord ID for UnbelievaBoat.

Important: never commit a real API token to a public GitHub repository.

Sales and purchases are serialized per player. The configured buy tax is applied to shop purchases. Player trades preserve the full ItemStack metadata. Items are removed only after UnbelievaBoat confirms payment. If the items disappear before removal, the plugin attempts to reverse the payment.

Edit prices.txt in this folder on GitHub, then run /cweconomy reload.
