# Horizen Bank 1.0.0

A custom Paper 26.3 banking plugin for Horizen SMP. It uses the server's existing Vault economy for wallet deposits, withdrawals, transfers, and approved loan payouts.

## Requirements
- Paper 26.3
- Java 25
- Vault
- An economy provider registered with Vault (for example EssentialsX Economy)

## Build
From this folder, run:

```bash
mvn clean package
```

The SQLite JDBC driver is bundled into the plugin by Maven Shade. Vault and Paper remain provided by the server.\n\nThe plugin JAR is created at:

```text
target/HorizenBank-1.0.0.jar
```

Use JDK 25 and Maven. The build downloads Paper API and VaultAPI dependencies.

## Install
1. Install Vault and your economy plugin.
2. Copy `target/HorizenBank-1.0.0.jar` into `plugins/`.
3. Restart the server.
4. Edit `plugins/HorizenBank/config.yml` as desired, then run `/bankadmin reload`.

## Player commands
- `/bank` — open bank GUI (create/deposit/withdraw/loan/repay actions can also be started from the GUI)
- `/bank create <name>` — create another account
- `/bank deposit <amount>` — deposit wallet money into the selected account
- `/bank withdraw <amount>` — withdraw from the selected account into the wallet
- `/bank transfer <player> <amount>` — transfer from the selected bank account to another player's Vault wallet
- `/bank vault` — open your private 54-slot item vault
- `/bank history` — view recent transactions
- `/bank loan <amount>` — request an admin-approved loan
- `/bank repay <amount>` — repay an active loan from wallet funds
- `/bank upgrade` — purchase an extra account allowance

## Admin commands
- `/bankadmin pending`
- `/bankadmin approve <loan-id>`
- `/bankadmin deny <loan-id>`
- `/bankadmin setbalance <player> <account-id> <amount>`
- `/bankadmin reload`

Permission: `horizenbank.admin`.

## Important implementation notes
- SQLite database: `plugins/HorizenBank/horizenbank.db`.
- Daily interest is scheduled on a persisted 24-hour wall-clock interval. Active loan balances also accrue their configured daily rate.
- Loan requests are admin-approved. Approval pays the loan to the player's Vault wallet.
- The private item vault is one shared 54-slot vault per player, not one per bank account. Its inventory is saved to SQLite when closed.
- Transfers are paid into the recipient's Vault wallet.
- The GUI supports account selection, account creation, deposits, withdrawals, vault, history, loan requests and repayments. Transfer is available with `/bank transfer <player> <amount>`.
- Keep regular backups of `horizenbank.db`. Do not edit the database while the server is running.
- Test on a staging server before production use.

## Build compatibility
The project is configured for Paper 26.3 API and Java 25. Paper 26.3 is a moving build line; if the configured API build is no longer available in your Maven repository, update the Paper API version in `pom.xml` to a published 26.3 build.


## Admin dashboard
Administrators with `horizenbank.admin` can run `/bankadmin` in-game to open the admin dashboard.
It displays the combined bank balance, total account count, and players who have bank accounts.
Click a player to inspect their accounts and balances, then click an account to view its details
and recent transaction history. Console administrators can continue using `/bankadmin pending`,
`approve`, `deny`, and `setbalance`.

## Number display
Balances use compact suffixes when `settings.compact-numbers: true`:
- 1,000 = 1k
- 1,000,000 = 1m
- 1,000,000,000 = 1b
- 1,000,000,000,000 = 1t

All messages and GUI names use standard Minecraft `&` color codes only. No RGB/hex formatting is used.
