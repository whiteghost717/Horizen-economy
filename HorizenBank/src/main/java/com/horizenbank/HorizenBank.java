package com.horizenbank;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.sql.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

public final class HorizenBank extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Economy economy;
    private Connection db;
    private final Map<UUID, Long> selectedAccount = new HashMap<>();
    private final Map<UUID, String> chatAction = new ConcurrentHashMap<>();
    private final Map<UUID, Long> vaultOwner = new HashMap<>();
    private final Map<UUID, Long> vaultAccount = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) {
            getLogger().severe("Vault economy provider not found. Install Vault and an economy plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try { openDatabase(); }
        catch (SQLException e) {
            getLogger().severe("Could not open bank database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Objects.requireNonNull(getCommand("bank")).setExecutor(this);
        Objects.requireNonNull(getCommand("bank")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("bankadmin")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        if (getConfig().getBoolean("interest.enabled", true))
            scheduleDailyInterest();
        getLogger().info("Horizen Bank enabled. Vault economy connected.");
    }

    @Override public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof BankHolder h && h.type.equals("VAULT"))
                saveVault(h.owner, h.account, p.getOpenInventory().getTopInventory());
        }
        try { if (db != null && !db.isClosed()) db.close(); } catch (SQLException ignored) {}
    }

    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    private void openDatabase() throws SQLException {
        File file = new File(getDataFolder(), "horizenbank.db");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        db = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS accounts (id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, name TEXT NOT NULL, balance REAL NOT NULL DEFAULT 0, created INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL, type TEXT NOT NULL, amount REAL NOT NULL, note TEXT, created INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS vaults (owner TEXT PRIMARY KEY, contents TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS loans (id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, amount REAL NOT NULL, balance REAL NOT NULL, rate REAL NOT NULL, status TEXT NOT NULL, requested INTEGER NOT NULL, approved_by TEXT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS bank_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_accounts_owner ON accounts(owner)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_transactions_account ON transactions(account_id)");
        }
    }

    private String msg(String path) {
        return color(getConfig().getString("messages.prefix", "&8[&6Horizen Bank&8] &r") +
                getConfig().getString("messages." + path, path));
    }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private void tell(CommandSender s, String text) { s.sendMessage(msg(text)); }
    private String money(double n) {
        String sym = getConfig().getString("settings.currency-symbol", "रू");
        if (getConfig().getBoolean("settings.compact-numbers", true)) {
            double a = Math.abs(n);
            if (a >= 1_000_000_000_000d) return sym + compact(n / 1_000_000_000_000d) + "t";
            if (a >= 1_000_000_000d) return sym + compact(n / 1_000_000_000d) + "b";
            if (a >= 1_000_000d) return sym + compact(n / 1_000_000d) + "m";
            if (a >= 1_000d) return sym + compact(n / 1_000d) + "k";
        }
        return sym + new DecimalFormat("#,##0.##").format(n);
    }
    private String compact(double n) { return new DecimalFormat("0.##").format(n); }

    private long createAccount(UUID owner, String name) throws SQLException {
        double cost = getConfig().getDouble("settings.account-creation-cost", 0);
        if (economy.getBalance(Bukkit.getOfflinePlayer(owner)) < cost) return -1;
        int max = getConfig().getInt("settings.max-accounts-per-player", 3);
        try (PreparedStatement p = db.prepareStatement("SELECT COUNT(*) FROM accounts WHERE owner=?")) {
            p.setString(1, owner.toString());
            try (ResultSet r = p.executeQuery()) {
                int extra = 0;
                try (Statement st = db.createStatement()) {
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS upgrades(owner TEXT PRIMARY KEY, extra_accounts INTEGER NOT NULL DEFAULT 0)");
                }
                try (PreparedStatement up = db.prepareStatement("SELECT extra_accounts FROM upgrades WHERE owner=?")) {
                    up.setString(1, owner.toString());
                    try (ResultSet ur = up.executeQuery()) { if (ur.next()) extra = ur.getInt(1); }
                }
                if (r.next() && r.getInt(1) >= max + extra) return -2;
            }
        }
        if (cost > 0 && !economy.withdrawPlayer(Bukkit.getOfflinePlayer(owner), cost).transactionSuccess()) return -1;
        try (PreparedStatement p = db.prepareStatement("INSERT INTO accounts(owner,name,balance,created) VALUES(?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, owner.toString()); p.setString(2, name);
            p.setDouble(3, getConfig().getDouble("settings.starting-balance", 0)); p.setLong(4, System.currentTimeMillis());
            p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) { if (r.next()) return r.getLong(1); }
        }
        return -1;
    }

    private List<Account> accounts(UUID owner) throws SQLException {
        List<Account> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT id,name,balance,created FROM accounts WHERE owner=? ORDER BY id")) {
            p.setString(1, owner.toString());
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new Account(r.getLong(1), r.getString(2), r.getDouble(3), r.getLong(4)));
            }
        }
        return out;
    }
    private Account account(long id, UUID owner) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT id,name,balance,created FROM accounts WHERE id=? AND owner=?")) {
            p.setLong(1,id); p.setString(2,owner.toString());
            try (ResultSet r=p.executeQuery()) { if(r.next()) return new Account(r.getLong(1),r.getString(2),r.getDouble(3),r.getLong(4)); }
        }
        return null;
    }
    private void setBalance(long id, double amount) throws SQLException {
        try (PreparedStatement p=db.prepareStatement("UPDATE accounts SET balance=? WHERE id=?")) { p.setDouble(1,amount); p.setLong(2,id); p.executeUpdate(); }
    }
    private void transaction(long id, String type, double amount, String note) throws SQLException {
        try (PreparedStatement p=db.prepareStatement("INSERT INTO transactions(account_id,type,amount,note,created) VALUES(?,?,?,?,?)")) {
            p.setLong(1,id); p.setString(2,type); p.setDouble(3,amount); p.setString(4,note); p.setLong(5,System.currentTimeMillis()); p.executeUpdate();
        }
        int limit=getConfig().getInt("settings.transaction-history-limit",100);
        try (PreparedStatement p=db.prepareStatement("DELETE FROM transactions WHERE account_id=? AND id NOT IN (SELECT id FROM transactions WHERE account_id=? ORDER BY id DESC LIMIT ?)")) {
            p.setLong(1,id); p.setLong(2,id); p.setInt(3,limit); p.executeUpdate();
        }
    }
    private boolean deposit(Player player, Account a, double amount) throws SQLException {
        if (!validAmount(amount)) return false;
        if (!economy.withdrawPlayer(player,amount).transactionSuccess()) return false;
        double next=a.balance+amount, max=getConfig().getDouble("settings.max-account-balance",-1);
        if(max>0 && next>max) { economy.depositPlayer(player,amount); return false; }
        setBalance(a.id,next); transaction(a.id,"DEPOSIT",amount,"Wallet deposit"); return true;
    }
    private boolean withdraw(Player player, Account a, double amount) throws SQLException {
        if (!validAmount(amount) || a.balance<amount) return false;
        setBalance(a.id,a.balance-amount);
        if (!economy.depositPlayer(player,amount).transactionSuccess()) { setBalance(a.id,a.balance); return false; }
        transaction(a.id,"WITHDRAW",amount,"Bank withdrawal"); return true;
    }
    private boolean validAmount(double n) {
        double min=getConfig().getDouble("settings.min-transaction",1), max=getConfig().getDouble("settings.max-transaction",-1);
        return Double.isFinite(n) && n>=min && (max<0 || n<=max);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(command.getName().equalsIgnoreCase("bankadmin")) return admin(sender,args);
        if(!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if(!p.hasPermission("horizenbank.use")) { p.sendMessage(msg("no-permission")); return true; }
        if(args.length==0) { openMain(p); return true; }
        try {
            switch(args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> {
                    if(args.length<2) { p.sendMessage(color("&eUsage: /bank create <name>")); return true; }
                    long id=createAccount(p.getUniqueId(),String.join(" ",Arrays.copyOfRange(args,1,args.length)));
                    p.sendMessage(color(id>0 ? "&aAccount created. ID: &e"+id : "&cCould not create account. Check your wallet or account limit."));
                }
                case "deposit", "withdraw" -> {
                    if(args.length<2) { p.sendMessage(color("&eUsage: /bank "+args[0]+" <amount>")); return true; }
                    Account a=primary(p); if(a==null) return true;
                    double amount=Double.parseDouble(args[1]);
                    boolean ok=args[0].equalsIgnoreCase("deposit")?deposit(p,a,amount):withdraw(p,a,amount);
                    p.sendMessage(color(ok?"&aTransaction successful: "+money(amount):"&cTransaction failed. Check balance and limits."));
                }
                case "transfer" -> {
                    if(args.length<3) { p.sendMessage(color("&eUsage: /bank transfer <player> <amount>")); return true; }
                    Account a=primary(p); if(a==null) return true;
                    OfflinePlayer target=Bukkit.getOfflinePlayer(args[1]); double amount=Double.parseDouble(args[2]);
                    if(!target.hasPlayedBefore() && !target.isOnline()) { p.sendMessage(color("&cThat player has never joined.")); return true; }
                    if(a.balance<amount || !validAmount(amount)) { p.sendMessage(color("&cInvalid amount or insufficient bank balance.")); return true; }
                    setBalance(a.id,a.balance-amount); transaction(a.id,"TRANSFER_OUT",amount,"To "+target.getName());
                    economy.depositPlayer(target,amount);
                    p.sendMessage(color("&aTransferred "+money(amount)+" to "+target.getName()+"'s wallet."));
                    if(target.isOnline()) target.getPlayer().sendMessage(color("&aYou received "+money(amount)+" from "+p.getName()+"."));
                }
                case "vault" -> openVault(p);
                case "history" -> openHistory(p);
                case "loan" -> {
                    if(args.length<2) { p.sendMessage(color("&eUsage: /bank loan <amount>")); return true; }
                    requestLoan(p,Double.parseDouble(args[1]));
                }
                case "repay" -> {
                    if(args.length<2) { p.sendMessage(color("&eUsage: /bank repay <amount>")); return true; }
                    repay(p,Double.parseDouble(args[1]));
                }
                case "upgrade" -> upgrade(p);
                default -> { p.sendMessage(color("&e/bank | create | deposit | withdraw | transfer | vault | history | loan | repay | upgrade")); }
            }
        } catch(NumberFormatException ex) { p.sendMessage(color("&cEnter a valid number.")); }
          catch(SQLException ex) { getLogger().severe("Database error: "+ex.getMessage()); p.sendMessage(color("&cBank database error. Contact an administrator.")); }
        return true;
    }

    private Account primary(Player p) throws SQLException {
        List<Account> list=accounts(p.getUniqueId());
        if(list.isEmpty()) { p.sendMessage(msg("no-account")); return null; }
        long selected=selectedAccount.getOrDefault(p.getUniqueId(),list.get(0).id);
        Account a=account(selected,p.getUniqueId()); return a==null?list.get(0):a;
    }
    private void openMain(Player p) {
        try {
            Inventory inv=gui(27,"&8[ &6&lHorizen Bank &8]",new BankHolder("MAIN",p.getUniqueId(),-1));
            fill(inv);
            List<Account> list=accounts(p.getUniqueId());
            int[] slots={10,11,12,13,14,15,16};
            for(int i=0;i<list.size()&&i<slots.length;i++){
                Account a=list.get(i); inv.setItem(slots[i],item(Material.GOLD_INGOT,"&6"+a.name,
                        "&7Account ID: &f"+a.id,"&7Balance: &a"+money(a.balance),"&eClick to select"));
            }
            inv.setItem(18,item(Material.BOOK,"&eCreate Account","&7Click, then type an account name"));
            inv.setItem(20,item(Material.HOPPER,"&aDeposit","&7Click, then enter an amount"));
            inv.setItem(21,item(Material.CHEST,"&cWithdraw","&7Click, then enter an amount"));
            inv.setItem(22,item(Material.ENDER_CHEST,"&dPrivate Vault","&7Click to open your vault"));
            inv.setItem(23,item(Material.PAPER,"&bHistory","&7View recent transactions"));
            inv.setItem(24,item(Material.WRITABLE_BOOK,"&6Request Loan","&7Click, then enter an amount"));
            inv.setItem(25,item(Material.GOLD_NUGGET,"&eRepay Loan","&7Click, then enter an amount"));
            inv.setItem(26,item(Material.BARRIER,"&cClose"));
            p.openInventory(inv);
        } catch(SQLException e){p.sendMessage(color("&cCould not load accounts."));}
    }
    private void beginChat(Player p, String action) {
        p.closeInventory();
        chatAction.put(p.getUniqueId(), action);
        String prompt = switch (action) {
            case "CREATE" -> "&eType the new account name in chat. Type cancel to stop.";
            case "TRANSFER" -> "&eType: player amount. Type cancel to stop.";
            default -> "&eEnter the amount in chat. Type cancel to stop.";
        };
        p.sendMessage(color(prompt));
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        String action = chatAction.remove(p.getUniqueId());
        if (action == null) return;
        event.setCancelled(true);
        String input = event.getMessage().trim();
        Bukkit.getScheduler().runTask(this, () -> {
            if (input.equalsIgnoreCase("cancel")) {
                p.sendMessage(color("&7Bank action cancelled."));
                openMain(p);
                return;
            }
            try {
                switch (action) {
                    case "CREATE" -> {
                        long id = createAccount(p.getUniqueId(), input);
                        p.sendMessage(color(id > 0 ? "&aAccount created. ID: &e" + id : "&cCould not create account. Check your wallet or account limit."));
                    }
                    case "DEPOSIT", "WITHDRAW" -> {
                        double amount = Double.parseDouble(input);
                        Account a = primary(p);
                        if (a == null) return;
                        boolean ok = action.equals("DEPOSIT") ? deposit(p, a, amount) : withdraw(p, a, amount);
                        p.sendMessage(color(ok ? "&aTransaction successful: " + money(amount) : "&cTransaction failed. Check balance and limits."));
                    }
                    case "LOAN" -> requestLoan(p, Double.parseDouble(input));
                    case "REPAY" -> repay(p, Double.parseDouble(input));
                    case "TRANSFER" -> {
                        String[] parts = input.split("\\s+");
                        if (parts.length != 2) { p.sendMessage(color("&cUse: player amount")); return; }
                        transfer(p, parts[0], Double.parseDouble(parts[1]));
                    }
                }
            } catch (NumberFormatException ex) {
                p.sendMessage(color("&cEnter a valid number."));
            } catch (Exception ex) {
                p.sendMessage(color("&cBank action failed: " + ex.getMessage()));
                getLogger().warning("Chat bank action failed: " + ex.getMessage());
            }
            openMain(p);
        });
    }

    private void transfer(Player sender, String targetName, double amount) throws SQLException {
        Account a = primary(sender);
        if (a == null) return;
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if ((!target.hasPlayedBefore() && !target.isOnline()) || target.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(color("&cChoose another player who has joined the server.")); return;
        }
        if (!validAmount(amount) || a.balance < amount) {
            sender.sendMessage(color("&cInvalid amount or insufficient bank balance.")); return;
        }
        setBalance(a.id, a.balance - amount);
        transaction(a.id, "TRANSFER_OUT", amount, "To " + targetName);
        economy.depositPlayer(target, amount);
        sender.sendMessage(color("&aTransferred " + money(amount) + " to " + targetName + "'s wallet."));
        if (target.isOnline()) target.getPlayer().sendMessage(color("&aYou received " + money(amount) + " from " + sender.getName() + "."));
    }

    private void openVault(Player p) {
        try {
            Account a=primary(p); if(a==null)return;
            int size=Math.max(9,Math.min(54,getConfig().getInt("settings.vault-size",54)));
            size=(size/9)*9;
            Inventory inv=Bukkit.createInventory(new BankHolder("VAULT",p.getUniqueId(),a.id),size,color("&8[ &6&lPrivate Vault &8]"));
            loadVault(p.getUniqueId(),inv);
            vaultOwner.put(p.getUniqueId(),a.id); vaultAccount.put(p.getUniqueId(),a.id);
            p.openInventory(inv);
        } catch(Exception e){p.sendMessage(color("&cCould not open vault."));}
    }
    private void openHistory(Player p) {
        try {
            Account a=primary(p); if(a==null)return;
            Inventory inv=gui(54,"&8[ &6&lTransaction History &8]",new BankHolder("HISTORY",p.getUniqueId(),a.id));
            fill(inv);
            try(PreparedStatement q=db.prepareStatement("SELECT type,amount,note,created FROM transactions WHERE account_id=? ORDER BY id DESC LIMIT 45")){
                q.setLong(1,a.id); try(ResultSet r=q.executeQuery()){
                    int slot=10;
                    while(r.next()&&slot<53){
                        inv.setItem(slot++,item(Material.PAPER,"&e"+r.getString(1),
                                "&7Amount: &f"+money(r.getDouble(2)),
                                "&7"+Objects.toString(r.getString(3),""),
                                "&8"+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(r.getLong(4)))));
                    }
                }
            }
            inv.setItem(49,item(Material.ARROW,"&eBack"));
            p.openInventory(inv);
        }catch(SQLException e){p.sendMessage(color("&cCould not load history."));}
    }

    private void openAdminDashboard(Player admin) {
        try {
            Inventory inv = gui(54, "&8[ &6&lHorizen Admin &8]", new BankHolder("ADMIN_HOME", null, -1));
            fill(inv);
            double total = 0;
            int accountCount = 0;
            Set<String> owners = new LinkedHashSet<>();
            try (Statement st = db.createStatement();
                 ResultSet rs = st.executeQuery("SELECT owner, balance FROM accounts ORDER BY owner")) {
                while (rs.next()) {
                    owners.add(rs.getString("owner"));
                    total += rs.getDouble("balance");
                    accountCount++;
                }
            }
            inv.setItem(4, item(Material.GOLD_BLOCK, "&6&lServer Bank Overview",
                    "&7Players with accounts: &f" + owners.size(),
                    "&7Total accounts: &f" + accountCount,
                    "&7Total bank balance: &a" + money(total)));
            inv.setItem(49, item(Material.BARRIER, "&cClose"));
            int slot = 10;
            for (String ownerText : owners) {
                if (slot == 17 || slot == 26 || slot == 35 || slot == 44) slot += 2;
                if (slot >= 45) break;
                UUID owner = UUID.fromString(ownerText);
                OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
                List<Account> list = accounts(owner);
                double balance = 0;
                for (Account a : list) balance += a.balance;
                String playerName = op.getName() == null ? owner.toString().substring(0, 8) : op.getName();
                inv.setItem(slot++, item(Material.PLAYER_HEAD, "&e" + playerName,
                        "&7Accounts: &f" + list.size(),
                        "&7Combined balance: &a" + money(balance),
                        "&eClick to inspect"));
            }
            admin.openInventory(inv);
        } catch (Exception ex) {
            admin.sendMessage(color("&cCould not load admin dashboard."));
            getLogger().warning("Admin dashboard error: " + ex.getMessage());
        }
    }

    private void openAdminPlayer(Player admin, UUID owner) {
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
            List<Account> list = accounts(owner);
            Inventory inv = gui(54, "&8[ &6&lPlayer Accounts &8]", new BankHolder("ADMIN_PLAYER", owner, -1));
            fill(inv);
            String name = op.getName() == null ? owner.toString().substring(0, 8) : op.getName();
            inv.setItem(4, item(Material.PLAYER_HEAD, "&e" + name,
                    "&7UUID: &f" + owner,
                    "&7Accounts: &f" + list.size()));
            int[] slots = {19, 21, 23, 25, 29, 31, 33};
            for (int i = 0; i < list.size() && i < slots.length; i++) {
                Account a = list.get(i);
                inv.setItem(slots[i], item(Material.GOLD_INGOT, "&6" + a.name,
                        "&7Account ID: &f" + a.id,
                        "&7Balance: &a" + money(a.balance),
                        "&eClick to inspect"));
            }
            inv.setItem(49, item(Material.ARROW, "&eBack to dashboard"));
            admin.openInventory(inv);
        } catch (SQLException ex) {
            admin.sendMessage(color("&cCould not load player accounts."));
        }
    }

    private void openAdminAccount(Player admin, UUID owner, long accountId) {
        try {
            Account a = account(accountId, owner);
            if (a == null) { admin.sendMessage(color("&cAccount not found.")); return; }
            OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
            String name = op.getName() == null ? owner.toString().substring(0, 8) : op.getName();
            Inventory inv = gui(27, "&8[ &6&lAccount Details &8]", new BankHolder("ADMIN_ACCOUNT", owner, accountId));
            fill(inv);
            inv.setItem(11, item(Material.PLAYER_HEAD, "&e" + name, "&7Owner UUID: &f" + owner));
            inv.setItem(13, item(Material.GOLD_INGOT, "&6" + a.name,
                    "&7Account ID: &f" + a.id,
                    "&7Balance: &a" + money(a.balance),
                    "&7Created: &f" + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(a.created))));
            inv.setItem(15, item(Material.WRITABLE_BOOK, "&bRecent transactions", "&7Click to view account history"));
            inv.setItem(22, item(Material.ARROW, "&eBack to player accounts"));
            admin.openInventory(inv);
        } catch (SQLException ex) {
            admin.sendMessage(color("&cCould not load account details."));
        }
    }

    private void openAdminHistory(Player admin, UUID owner, long accountId) {
        try {
            Inventory inv = gui(54, "&8[ &6&lAdmin Transaction Log &8]", new BankHolder("ADMIN_HISTORY", owner, accountId));
            fill(inv);
            try (PreparedStatement q = db.prepareStatement(
                    "SELECT type,amount,note,created FROM transactions WHERE account_id=? ORDER BY id DESC LIMIT 45")) {
                q.setLong(1, accountId);
                try (ResultSet r = q.executeQuery()) {
                    int slot = 10;
                    while (r.next() && slot < 45) {
                        inv.setItem(slot++, item(Material.PAPER, "&e" + r.getString(1),
                                "&7Amount: &f" + money(r.getDouble(2)),
                                "&7Note: &f" + Objects.toString(r.getString(3), ""),
                                "&8" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(r.getLong(4)))));
                    }
                }
            }
            inv.setItem(49, item(Material.ARROW, "&eBack to account"));
            admin.openInventory(inv);
        } catch (SQLException ex) {
            admin.sendMessage(color("&cCould not load transaction history."));
        }
    }

    private Inventory gui(int size,String title,InventoryHolder holder){return Bukkit.createInventory(holder,size,color(title));}
    private void fill(Inventory inv){
        Material m=Material.matchMaterial(getConfig().getString("gui.filler","GRAY_STAINED_GLASS_PANE"));
        if(m==null)m=Material.GRAY_STAINED_GLASS_PANE;
        ItemStack pane=item(m,"&7 ");
        for(int i=0;i<inv.getSize();i++)inv.setItem(i,pane);
    }
    private ItemStack item(Material material,String name,String... lore){
        ItemStack it=new ItemStack(material); ItemMeta meta=it.getItemMeta();
        meta.setDisplayName(color(name)); meta.setLore(Arrays.stream(lore).map(this::color).toList()); it.setItemMeta(meta); return it;
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p))return;
        if(!(e.getView().getTopInventory().getHolder() instanceof BankHolder h))return;
        if(h.type.equals("VAULT")) {
            if (e.isShiftClick() && e.getClickedInventory() == e.getView().getBottomInventory()) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        if(e.getClickedInventory()!=e.getView().getTopInventory())return;
        int slot=e.getRawSlot();
        try {
            if(h.type.equals("MAIN")){
                if(slot>=10&&slot<=16){
                    List<Account> list=accounts(p.getUniqueId()); int idx=slot-10;
                    if(idx<list.size()){selectedAccount.put(p.getUniqueId(),list.get(idx).id);p.sendMessage(color("&aSelected account: &e"+list.get(idx).name));}
                } else if(slot==18)beginChat(p,"CREATE");
                else if(slot==20)beginChat(p,"DEPOSIT");
                else if(slot==21)beginChat(p,"WITHDRAW");
                else if(slot==22)openVault(p);
                else if(slot==23)openHistory(p);
                else if(slot==24)beginChat(p,"LOAN");
                else if(slot==25)beginChat(p,"REPAY");
                else if(slot==26)p.closeInventory();
            } else if(h.type.equals("HISTORY")&&slot==49)openMain(p);
            else if (h.type.equals("ADMIN_HOME")) {
                if (slot == 49) p.closeInventory();
                else {
                    ItemStack clicked = e.getCurrentItem();
                    if (clicked != null && clicked.getType() == Material.PLAYER_HEAD) {
                        String display = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
                        OfflinePlayer target = Bukkit.getOfflinePlayer(display);
                        // Resolve by the displayed player name, with UUID fallback handled by stored account owners.
                        try (PreparedStatement q = db.prepareStatement(
                                "SELECT DISTINCT owner FROM accounts")) {
                            try (ResultSet r = q.executeQuery()) {
                                while (r.next()) {
                                    UUID id = UUID.fromString(r.getString(1));
                                    String n = Bukkit.getOfflinePlayer(id).getName();
                                    if (n != null && n.equalsIgnoreCase(display)) {
                                        openAdminPlayer(p, id);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (h.type.equals("ADMIN_PLAYER")) {
                if (slot == 49) openAdminDashboard(p);
                else {
                    List<Account> list = accounts(h.owner);
                    int[] slots = {19, 21, 23, 25, 29, 31, 33};
                    for (int i = 0; i < slots.length && i < list.size(); i++) {
                        if (slot == slots[i]) { openAdminAccount(p, h.owner, list.get(i).id); break; }
                    }
                }
            } else if (h.type.equals("ADMIN_ACCOUNT")) {
                if (slot == 22) openAdminPlayer(p, h.owner);
                else if (slot == 15) openAdminHistory(p, h.owner, h.account);
            } else if (h.type.equals("ADMIN_HISTORY") && slot == 49) {
                openAdminAccount(p, h.owner, h.account);
            }
        }catch(SQLException ex){p.sendMessage(color("&cDatabase error."));}
    }
    @EventHandler public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof BankHolder h)) return;
        if (!h.type.equals("VAULT")) {
            for (int raw : e.getRawSlots()) if (raw < e.getView().getTopInventory().getSize()) {
                e.setCancelled(true); return;
            }
        }
    }

    @EventHandler public void onClose(InventoryCloseEvent e) {
        if(!(e.getPlayer() instanceof Player p))return;
        if(e.getInventory().getHolder() instanceof BankHolder h && h.type.equals("VAULT"))
            saveVault(h.owner,h.account,e.getInventory());
    }
    private void saveVault(UUID owner,long account,Inventory inv) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(BukkitObjectOutputStream out=new BukkitObjectOutputStream(bytes)){out.writeObject(inv.getContents());}
            String data=Base64.getEncoder().encodeToString(bytes.toByteArray());
            try(PreparedStatement p=db.prepareStatement("INSERT INTO vaults(owner,contents) VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET contents=excluded.contents")){
                p.setString(1,owner.toString());p.setString(2,data);p.executeUpdate();
            }
        }catch(Exception e){getLogger().warning("Could not save vault for "+owner+": "+e.getMessage());}
    }
    private void loadVault(UUID owner,Inventory inv) {
        try(PreparedStatement p=db.prepareStatement("SELECT contents FROM vaults WHERE owner=?")){
            p.setString(1,owner.toString());try(ResultSet r=p.executeQuery()){if(r.next()){
                try(BukkitObjectInputStream in=new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(r.getString(1))))){
                    Object obj=in.readObject(); if(obj instanceof ItemStack[] items)inv.setContents(Arrays.copyOf(items,inv.getSize()));
                }
            }}
        }catch(Exception e){getLogger().warning("Could not load vault for "+owner+": "+e.getMessage());}
    }

    private void requestLoan(Player p,double amount) throws SQLException {
        if(!getConfig().getBoolean("loans.enabled",true)||amount<=0||amount>getConfig().getDouble("loans.maximum-amount",1_000_000)){
            p.sendMessage(color("&cLoan amount is unavailable or outside the configured limit."));return;
        }
        try(PreparedStatement q=db.prepareStatement("SELECT COUNT(*) FROM loans WHERE owner=? AND status IN ('PENDING','ACTIVE')")){
            q.setString(1,p.getUniqueId().toString());try(ResultSet r=q.executeQuery()){if(r.next()&&r.getInt(1)>=getConfig().getInt("loans.maximum-active-loans",1)){
                p.sendMessage(color("&cYou already have a pending or active loan."));return;
            }}
        }
        try(PreparedStatement q=db.prepareStatement("INSERT INTO loans(owner,amount,balance,rate,status,requested) VALUES(?,?,?,?,'PENDING',?)")){
            q.setString(1,p.getUniqueId().toString());q.setDouble(2,amount);q.setDouble(3,amount);
            q.setDouble(4,getConfig().getDouble("loans.daily-interest-rate",.02));q.setLong(5,System.currentTimeMillis());q.executeUpdate();
        }
        p.sendMessage(color("&aLoan request submitted for "+money(amount)+". An administrator must approve it."));
        for(Player admin:Bukkit.getOnlinePlayers())if(admin.hasPermission("horizenbank.admin"))admin.sendMessage(color("&6[Horizen Bank] &e"+p.getName()+" requested "+money(amount)+". Use /bankadmin pending."));
    }
    private boolean repay(Player p,double amount) throws SQLException {
        if(amount<=0||!getConfig().getBoolean("loans.allow-player-repayment",true))return false;
        try(PreparedStatement q=db.prepareStatement("SELECT id,balance FROM loans WHERE owner=? AND status='ACTIVE' ORDER BY id LIMIT 1")){
            q.setString(1,p.getUniqueId().toString());try(ResultSet r=q.executeQuery()){
                if(!r.next()){p.sendMessage(color("&cYou have no active loan."));return false;}
                long id=r.getLong(1);double owed=r.getDouble(2);double pay=Math.min(amount,owed);
                if(!economy.withdrawPlayer(p,pay).transactionSuccess()){p.sendMessage(color("&cNot enough money in your wallet."));return false;}
                double left=owed-pay;
                try(PreparedStatement u=db.prepareStatement("UPDATE loans SET balance=?,status=? WHERE id=?")){
                    u.setDouble(1,left);u.setString(2,left<=0.000001?"PAID":"ACTIVE");u.setLong(3,id);u.executeUpdate();
                }
                p.sendMessage(color("&aLoan repayment: "+money(pay)+". Remaining: "+money(left)));
                return true;
            }
        }
    }
    private boolean admin(CommandSender s,String[] args) {
        if(!s.hasPermission("horizenbank.admin")){s.sendMessage(msg("no-permission"));return true;}
        if (args.length == 0 && s instanceof Player adminPlayer) {
            openAdminDashboard(adminPlayer);
            return true;
        }
        try{
            if(args.length==0||args[0].equalsIgnoreCase("pending")){
                try(Statement q=db.createStatement();ResultSet r=q.executeQuery("SELECT id,owner,amount FROM loans WHERE status='PENDING' ORDER BY id")){
                    s.sendMessage(color("&6&lHorizen Bank - Pending Loans"));
                    while(r.next()){
                        OfflinePlayer op=Bukkit.getOfflinePlayer(UUID.fromString(r.getString(2)));
                        s.sendMessage(color("&e#"+r.getLong(1)+" &f"+op.getName()+" &a"+money(r.getDouble(3))+" &7/bankadmin approve "+r.getLong(1)));
                    }
                }
            }else if(args[0].equalsIgnoreCase("approve")&&args.length>=2){
                long id=Long.parseLong(args[1]);
                try(PreparedStatement q=db.prepareStatement("SELECT owner,amount FROM loans WHERE id=? AND status='PENDING'")){
                    q.setLong(1,id);try(ResultSet r=q.executeQuery()){
                        if(!r.next()){s.sendMessage(color("&cPending loan not found."));return true;}
                        UUID owner=UUID.fromString(r.getString(1));double amount=r.getDouble(2);
                        if (!economy.depositPlayer(Bukkit.getOfflinePlayer(owner),amount).transactionSuccess()) {
                            s.sendMessage(color("&cVault could not pay out this loan. It remains pending."));
                            return true;
                        }
                        try(PreparedStatement u=db.prepareStatement("UPDATE loans SET status='ACTIVE',approved_by=? WHERE id=?")){
                            u.setString(1,s.getName());u.setLong(2,id);u.executeUpdate();
                        }
                        s.sendMessage(color("&aApproved loan #"+id+" for "+money(amount)+"."));
                        Player online=Bukkit.getPlayer(owner);if(online!=null)online.sendMessage(color("&aYour loan of "+money(amount)+" was approved and paid to your wallet."));
                    }
                }
            }else if(args[0].equalsIgnoreCase("deny")&&args.length>=2){
                try(PreparedStatement u=db.prepareStatement("UPDATE loans SET status='DENIED',approved_by=? WHERE id=? AND status='PENDING'")){
                    u.setString(1,s.getName());u.setLong(2,Long.parseLong(args[1]));
                    s.sendMessage(color(u.executeUpdate()>0?"&aLoan denied.":"&cPending loan not found."));
                }
            }else if(args[0].equalsIgnoreCase("reload")){
                reloadConfig();s.sendMessage(color("&aHorizen Bank config reloaded."));
            }else if(args[0].equalsIgnoreCase("setbalance")&&args.length>=4){
                UUID owner=Bukkit.getOfflinePlayer(args[1]).getUniqueId();long id=Long.parseLong(args[2]);double value=Double.parseDouble(args[3]);
                Account a=account(id,owner);if(a==null){s.sendMessage(color("&cAccount not found."));return true;}
                setBalance(id,value);s.sendMessage(color("&aBalance updated."));
            }else s.sendMessage(color("&e/bankadmin pending | approve <id> | deny <id> | setbalance <player> <accountId> <amount> | reload"));
        }catch(Exception e){s.sendMessage(color("&cAdmin action failed: "+e.getMessage()));}
        return true;
    }
    private void upgrade(Player p) throws SQLException {
        double cost=getConfig().getDouble("settings.account-upgrade-cost",250000);
        if(!economy.withdrawPlayer(p,cost).transactionSuccess()){p.sendMessage(color("&cYou need "+money(cost)+" to upgrade your account limit."));return;}
        // Persist the extra account allowance in player metadata table.
        try(Statement s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS upgrades(owner TEXT PRIMARY KEY, extra_accounts INTEGER NOT NULL DEFAULT 0)");}
        try(PreparedStatement q=db.prepareStatement("INSERT INTO upgrades(owner,extra_accounts) VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET extra_accounts=extra_accounts+excluded.extra_accounts")){
            q.setString(1,p.getUniqueId().toString());q.setInt(2,getConfig().getInt("settings.account-upgrade-increase",1));q.executeUpdate();
        }
        p.sendMessage(color("&aAccount limit upgraded. Paid "+money(cost)+"."));
    }
    private void scheduleDailyInterest() {
        long dayTicks = 20L * 60L * 60L * 24L;
        long dayMillis = 24L * 60L * 60L * 1000L;
        long last = 0L;
        try (PreparedStatement p = db.prepareStatement("SELECT value FROM bank_meta WHERE key='last_daily_interest'");
             ResultSet r = p.executeQuery()) {
            if (r.next()) last = Long.parseLong(r.getString(1));
        } catch (Exception ignored) {}
        if (last == 0L) last = System.currentTimeMillis();
        long elapsed = Math.max(0L, System.currentTimeMillis() - last);
        long delay = Math.max(1L, (dayMillis - elapsed) / 50L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            payDailyInterest();
            try (PreparedStatement p = db.prepareStatement("INSERT INTO bank_meta(key,value) VALUES('last_daily_interest',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                p.setString(1, Long.toString(System.currentTimeMillis())); p.executeUpdate();
            } catch (SQLException ex) { getLogger().warning("Could not save daily interest timestamp: " + ex.getMessage()); }
            Bukkit.getScheduler().runTaskTimer(this, this::payDailyInterestAndSaveTimestamp, dayTicks, dayTicks);
        }, delay);
    }

    private void payDailyInterestAndSaveTimestamp() {
        payDailyInterest();
        try (PreparedStatement p = db.prepareStatement("INSERT INTO bank_meta(key,value) VALUES('last_daily_interest',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            p.setString(1, Long.toString(System.currentTimeMillis())); p.executeUpdate();
        } catch (SQLException ex) { getLogger().warning("Could not save daily interest timestamp: " + ex.getMessage()); }
    }

    private void payDailyInterest() {
        try(Statement q=db.createStatement();ResultSet r=q.executeQuery("SELECT id,balance,owner FROM accounts")){
            List<Object[]> rows=new ArrayList<>();while(r.next())rows.add(new Object[]{r.getLong(1),r.getDouble(2),r.getString(3)});
            double rate=getConfig().getDouble("interest.daily-rate",.005),min=getConfig().getDouble("interest.minimum-balance",1000),
                    cap=getConfig().getDouble("interest.maximum-daily-payout",50000);
            for(Object[] row:rows){
                long id=(long)row[0];double bal=(double)row[1];if(bal<min)continue;
                double payout=bal*rate;if(cap>0)payout=Math.min(payout,cap);if(payout<=0)continue;
                setBalance(id,bal+payout);transaction(id,"DAILY_INTEREST",payout,"Daily compound interest");
                Player online=Bukkit.getPlayer(UUID.fromString((String)row[2]));
                if(online!=null)online.sendMessage(color("&aHorizen Bank paid "+money(payout)+" daily interest to your account."));
            }
            try (PreparedStatement loans = db.prepareStatement("UPDATE loans SET balance=balance*(1+rate) WHERE status='ACTIVE'")) {
                loans.executeUpdate();
            }
        }catch(SQLException e){getLogger().severe("Daily interest failed: "+e.getMessage());}
    }

    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){
        if(c.getName().equalsIgnoreCase("bankadmin"))return args.length==1?List.of("pending","approve","deny","setbalance","reload"):Collections.emptyList();
        if(args.length==1)return List.of("create","deposit","withdraw","transfer","vault","history","loan","repay","upgrade");
        return Collections.emptyList();
    }
    private record Account(long id,String name,double balance,long created){}
    private static final class BankHolder implements InventoryHolder {
        final String type; final UUID owner; final long account;
        BankHolder(String type,UUID owner,long account){this.type=type;this.owner=owner;this.account=account;}
        @Override public Inventory getInventory(){return null;}
    }
}
