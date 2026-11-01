package id.kota;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class KotaOtomatis extends JavaPlugin implements Listener {

    static class Village {
        String id, name, world;
        int x, z, radius;
        UUID leader;
        Set<UUID> residents = new LinkedHashSet<>();
        boolean pvp = false, explosion = false, guest = false;
        Map<UUID, UUID> votes = new HashMap<>();
        long voteStart = System.currentTimeMillis();

        boolean contains(Location l) {
            return l.getWorld() != null && l.getWorld().getName().equals(world)
                    && Math.abs(l.getBlockX() - x) <= radius
                    && Math.abs(l.getBlockZ() - z) <= radius;
        }
    }

    private final Map<String, Village> villages = new LinkedHashMap<>();
    private final Map<String, Integer> builds = new HashMap<>();
    private final Map<UUID, String> lastVillage = new HashMap<>();
    private File dataFile;

    private static final String[] NAMA_A = {"Batu", "Air", "Angin", "Pelangi", "Senja", "Fajar", "Karang", "Embun", "Cahaya", "Hutan"};
    private static final String[] NAMA_B = {"Jaya", "Sari", "Indah", "Makmur", "Baru", "Damai", "Asri", "Mulya", "Sentosa", "Raya"};

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PapiHook(this).register();
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Village v : new ArrayList<>(villages.values())) finalizeVoteIfDue(v);
        }, 20L * 60, 20L * 60 * 10);
        Bukkit.getScheduler().runTaskTimer(this, this::saveData, 20L * 300, 20L * 300);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection b = y.getConfigurationSection("builds");
        if (b != null) for (String k : b.getKeys(false)) builds.put(k, b.getInt(k));
        ConfigurationSection vs = y.getConfigurationSection("villages");
        if (vs != null) for (String id : vs.getKeys(false)) {
            ConfigurationSection s = vs.getConfigurationSection(id);
            if (s == null) continue;
            Village v = new Village();
            v.id = id;
            v.name = s.getString("name", id);
            v.world = s.getString("world", "world");
            v.x = s.getInt("x");
            v.z = s.getInt("z");
            v.radius = s.getInt("radius", 64);
            String ld = s.getString("leader");
            if (ld != null) v.leader = UUID.fromString(ld);
            for (String r : s.getStringList("residents")) v.residents.add(UUID.fromString(r));
            v.pvp = s.getBoolean("rules.pvp");
            v.explosion = s.getBoolean("rules.explosion");
            v.guest = s.getBoolean("rules.guest");
            v.voteStart = s.getLong("voteStart", System.currentTimeMillis());
            ConfigurationSection vt = s.getConfigurationSection("votes");
            if (vt != null) for (String voter : vt.getKeys(false))
                v.votes.put(UUID.fromString(voter), UUID.fromString(vt.getString(voter)));
            villages.put(id, v);
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Integer> e : builds.entrySet()) y.set("builds." + e.getKey(), e.getValue());
        for (Village v : villages.values()) {
            String p = "villages." + v.id + ".";
            y.set(p + "name", v.name);
            y.set(p + "world", v.world);
            y.set(p + "x", v.x);
            y.set(p + "z", v.z);
            y.set(p + "radius", v.radius);
            y.set(p + "leader", v.leader == null ? null : v.leader.toString());
            List<String> rs = new ArrayList<>();
            for (UUID u : v.residents) rs.add(u.toString());
            y.set(p + "residents", rs);
            y.set(p + "rules.pvp", v.pvp);
            y.set(p + "rules.explosion", v.explosion);
            y.set(p + "rules.guest", v.guest);
            y.set(p + "voteStart", v.voteStart);
            for (Map.Entry<UUID, UUID> e : v.votes.entrySet())
                y.set(p + "votes." + e.getKey(), e.getValue().toString());
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Gagal menyimpan data: " + ex.getMessage());
        }
    }

    private Village villageAt(Location l) {
        for (Village v : villages.values()) if (v.contains(l)) return v;
        return null;
    }

    private Village villageOf(UUID u) {
        for (Village v : villages.values()) if (v.residents.contains(u)) return v;
        return null;
    }

    private String nameOf(UUID u) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return op.getName() == null ? u.toString().substring(0, 8) : op.getName();
    }

    private void msg(CommandSender s, String text) {
        s.sendMessage(Component.text("[Desa] ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.YELLOW)));
    }

    private void finalizeVoteIfDue(Village v) {
        long period = getConfig().getLong("hari-voting", 7) * 24L * 3600L * 1000L;
        if (System.currentTimeMillis() - v.voteStart < period) return;
        tally(v);
    }

    private void tally(Village v) {
        Map<UUID, Integer> count = new HashMap<>();
        for (UUID c : v.votes.values()) if (v.residents.contains(c)) count.merge(c, 1, Integer::sum);
        UUID best = null;
        int max = 0;
        for (Map.Entry<UUID, Integer> e : count.entrySet()) {
            if (e.getValue() > max) { max = e.getValue(); best = e.getKey(); }
            else if (e.getValue() == max && e.getKey().equals(v.leader)) best = e.getKey();
        }
        if (best != null) {
            v.leader = best;
            broadcastVillage(v, "Voting selesai! Pemimpin baru " + v.name + ": " + nameOf(best));
        }
        v.votes.clear();
        v.voteStart = System.currentTimeMillis();
    }

    private void broadcastVillage(Village v, String text) {
        for (UUID u : v.residents) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) msg(p, text);
        }
    }

    // ---------- Untuk PlaceholderAPI / TAB ----------
    String papiDesa(UUID u) {
        Village v = villageOf(u);
        return v == null ? "-" : v.name;
    }

    String papiPemimpin(UUID u) {
        Village v = villageOf(u);
        return v == null || v.leader == null ? "-" : nameOf(v.leader);
    }

    String papiWarga(UUID u) {
        Village v = villageOf(u);
        return v == null ? "0" : String.valueOf(v.residents.size());
    }

    String papiPeran(UUID u) {
        Village v = villageOf(u);
        if (v == null) return "-";
        return u.equals(v.leader) ? "Pemimpin" : "Warga";
    }

    String papiLokasi(Player p) {
        Village v = villageAt(p.getLocation());
        return v == null ? "-" : v.name;
    }

    private void leaveVillage(Village v, UUID u) {
        v.residents.remove(u);
        v.votes.remove(u);
        v.votes.values().removeIf(c -> c.equals(u));
        builds.keySet().removeIf(k -> {
            String[] s = k.split(";");
            if (!s[0].equals(v.world) || !s[3].equals(u.toString())) return false;
            int cx = Integer.parseInt(s[1]) * 16 + 8, cz = Integer.parseInt(s[2]) * 16 + 8;
            return Math.abs(cx - v.x) <= v.radius && Math.abs(cz - v.z) <= v.radius;
        });
        if (v.residents.isEmpty()) {
            villages.remove(v.id);
        } else if (u.equals(v.leader)) {
            v.leader = v.residents.iterator().next();
            broadcastVillage(v, nameOf(u) + " keluar. Pemimpin sementara: " + nameOf(v.leader));
        } else {
            broadcastVillage(v, nameOf(u) + " keluar dari desa.");
        }
        saveData();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlockPlaced();
        String key = b.getWorld().getName() + ";" + (b.getX() >> 4) + ";" + (b.getZ() >> 4) + ";" + p.getUniqueId();
        int n = builds.merge(key, 1, Integer::sum);

        Village here = villageAt(b.getLocation());
        int minBlok = getConfig().getInt("min-blok", 50);
        if (here != null) {
            if (!here.residents.contains(p.getUniqueId()) && villageOf(p.getUniqueId()) == null
                    && totalBuildsInVillage(here, p.getUniqueId()) >= minBlok) {
                here.residents.add(p.getUniqueId());
                msg(p, "Kamu sekarang warga desa " + here.name + "!");
                broadcastVillage(here, p.getName() + " bergabung sebagai warga.");
            }
            return;
        }
        if (n % 10 != 0) return;
        tryFormVillage(p, b.getLocation());
    }

    private int totalBuildsInVillage(Village v, UUID u) {
        int total = 0;
        for (Map.Entry<String, Integer> e : builds.entrySet()) {
            String[] k = e.getKey().split(";");
            if (!k[0].equals(v.world) || !k[3].equals(u.toString())) continue;
            int cx = Integer.parseInt(k[1]) * 16 + 8, cz = Integer.parseInt(k[2]) * 16 + 8;
            if (Math.abs(cx - v.x) <= v.radius && Math.abs(cz - v.z) <= v.radius) total += e.getValue();
        }
        return total;
    }

    private void tryFormVillage(Player trigger, Location loc) {
        int minPemain = getConfig().getInt("min-pemain", 3);
        int minBlok = getConfig().getInt("min-blok", 50);
        int jarak = getConfig().getInt("jarak-chunk", 6);
        int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
        String world = loc.getWorld().getName();

        Map<UUID, Integer> near = new HashMap<>();
        for (Map.Entry<String, Integer> e : builds.entrySet()) {
            String[] k = e.getKey().split(";");
            if (!k[0].equals(world)) continue;
            if (Math.abs(Integer.parseInt(k[1]) - cx) > jarak || Math.abs(Integer.parseInt(k[2]) - cz) > jarak) continue;
            near.merge(UUID.fromString(k[3]), e.getValue(), Integer::sum);
        }
        List<UUID> eligible = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : near.entrySet())
            if (e.getValue() >= minBlok && villageOf(e.getKey()) == null) eligible.add(e.getKey());
        if (eligible.size() < minPemain) return;

        Village v = new Village();
        v.id = "desa" + (villages.size() + 1) + "_" + System.currentTimeMillis() % 10000;
        v.name = "Desa " + NAMA_A[new Random().nextInt(NAMA_A.length)] + " " + NAMA_B[new Random().nextInt(NAMA_B.length)];
        v.world = world;
        v.x = loc.getBlockX();
        v.z = loc.getBlockZ();
        v.radius = getConfig().getInt("radius-desa", 64);
        v.residents.addAll(eligible);
        v.leader = Collections.max(eligible, Comparator.comparingInt(near::get));
        villages.put(v.id, v);

        broadcastVillage(v, "Desa baru terbentuk: " + v.name + "! Pemimpin sementara: " + nameOf(v.leader)
                + ". Pakai /desa calon & /desa vote untuk memilih pemimpin.");
        Bukkit.broadcast(Component.text("Sebuah desa baru lahir: " + v.name, NamedTextColor.GOLD));
        saveData();
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() >> 4 == e.getTo().getBlockX() >> 4
                && e.getFrom().getBlockZ() >> 4 == e.getTo().getBlockZ() >> 4) return;
        Player p = e.getPlayer();
        Village v = villageAt(e.getTo());
        String now = v == null ? null : v.id;
        String before = lastVillage.get(p.getUniqueId());
        if (Objects.equals(now, before)) return;
        if (now == null) lastVillage.remove(p.getUniqueId()); else lastVillage.put(p.getUniqueId(), now);
        if (v != null) {
            p.showTitle(Title.title(Component.text("Memasuki " + v.name, NamedTextColor.GOLD),
                    Component.text("Pemimpin: " + (v.leader == null ? "-" : nameOf(v.leader)), NamedTextColor.YELLOW)));
        } else if (before != null && villages.containsKey(before)) {
            p.showTitle(Title.title(Component.text("Meninggalkan " + villages.get(before).name, NamedTextColor.GRAY),
                    Component.empty()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player a) attacker = a;
        else if (e.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player a) attacker = a;
        if (attacker == null) return;
        Village v = villageAt(victim.getLocation());
        if (v != null && !v.pvp) {
            e.setCancelled(true);
            msg(attacker, "PvP dimatikan di " + v.name + ".");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> {
            Village v = villageAt(b.getLocation());
            return v != null && !v.explosion;
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> {
            Village v = villageAt(b.getLocation());
            return v != null && !v.explosion;
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Material m = e.getClickedBlock().getType();
        if (m == Material.ENDER_CHEST) return;
        if (!(e.getClickedBlock().getState() instanceof org.bukkit.block.Container)) return;
        Village v = villageAt(e.getClickedBlock().getLocation());
        if (v == null || v.guest) return;
        if (!v.residents.contains(e.getPlayer().getUniqueId()) && !e.getPlayer().hasPermission("desa.admin")) {
            e.setCancelled(true);
            msg(e.getPlayer(), "Hanya warga " + v.name + " yang boleh membuka ini.");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) { msg(sender, "Hanya untuk pemain."); return true; }
        if (a.length == 0) { msg(p, "Pakai: /desa <info|daftar|nama|calon|vote|hasil|aturan|keluar>"); return true; }

        switch (a[0].toLowerCase()) {
            case "daftar" -> {
                if (villages.isEmpty()) { msg(p, "Belum ada desa."); break; }
                for (Village v : villages.values())
                    msg(p, v.name + " | warga: " + v.residents.size() + " | pemimpin: " + (v.leader == null ? "-" : nameOf(v.leader)));
            }
            case "info" -> {
                Village v = villageAt(p.getLocation());
                if (v == null) v = villageOf(p.getUniqueId());
                if (v == null) { msg(p, "Kamu tidak berada di desa mana pun."); break; }
                msg(p, v.name + " | pusat: " + v.x + ", " + v.z + " | radius: " + v.radius);
                msg(p, "Pemimpin: " + (v.leader == null ? "-" : nameOf(v.leader)));
                StringBuilder sb = new StringBuilder();
                for (UUID u : v.residents) sb.append(nameOf(u)).append(", ");
                msg(p, "Warga: " + sb);
                msg(p, "Aturan: PvP=" + (v.pvp ? "on" : "off") + ", ledakan=" + (v.explosion ? "on" : "off")
                        + ", tamu buka chest=" + (v.guest ? "boleh" : "tidak"));
            }
            case "nama" -> {
                Village v = villageOf(p.getUniqueId());
                if (v == null) { msg(p, "Kamu belum jadi warga desa."); break; }
                if (!p.getUniqueId().equals(v.leader)) { msg(p, "Hanya pemimpin yang bisa ganti nama."); break; }
                if (a.length < 2) { msg(p, "Pakai: /desa nama <nama baru>"); break; }
                v.name = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
                broadcastVillage(v, "Desa kini bernama " + v.name);
                saveData();
            }
            case "calon" -> {
                Village v = villageOf(p.getUniqueId());
                if (v == null) { msg(p, "Kamu belum jadi warga desa."); break; }
                StringBuilder sb = new StringBuilder();
                for (UUID u : v.residents) {
                    long votes = v.votes.values().stream().filter(c -> c.equals(u)).count();
                    sb.append(nameOf(u)).append(" (").append(votes).append(" suara), ");
                }
                msg(p, "Calon: " + sb);
            }
            case "vote" -> {
                Village v = villageOf(p.getUniqueId());
                if (v == null) { msg(p, "Kamu belum jadi warga desa."); break; }
                if (a.length < 2) { msg(p, "Pakai: /desa vote <nama warga>"); break; }
                finalizeVoteIfDue(v);
                UUID target = null;
                for (UUID u : v.residents) if (nameOf(u).equalsIgnoreCase(a[1])) target = u;
                if (target == null) { msg(p, "Warga itu tidak ditemukan di desamu."); break; }
                v.votes.put(p.getUniqueId(), target);
                msg(p, "Suaramu untuk " + nameOf(target) + " tercatat. Bisa diganti kapan saja sebelum voting ditutup.");
                saveData();
            }
            case "hasil" -> {
                Village v = villageOf(p.getUniqueId());
                if (v == null) { msg(p, "Kamu belum jadi warga desa."); break; }
                long period = getConfig().getLong("hari-voting", 7) * 24L * 3600L * 1000L;
                long left = Math.max(0, period - (System.currentTimeMillis() - v.voteStart));
                msg(p, "Voting ditutup dalam " + (left / 3600000L) + " jam. Suara masuk: " + v.votes.size() + "/" + v.residents.size());
            }
            case "aturan" -> {
                Village v = villageOf(p.getUniqueId());
                if (v == null) { msg(p, "Kamu belum jadi warga desa."); break; }
                if (!p.getUniqueId().equals(v.leader)) { msg(p, "Hanya pemimpin yang bisa ubah aturan."); break; }
                if (a.length < 3) { msg(p, "Pakai: /desa aturan <pvp|ledakan|tamu> <on|off>"); break; }
                boolean on = a[2].equalsIgnoreCase("on");
                switch (a[1].toLowerCase()) {
                    case "pvp" -> v.pvp = on;
                    case "ledakan" -> v.explosion = on;
                    case "tamu" -> v.guest = on;
                    default -> { msg(p, "Aturan tidak dikenal."); return true; }
                }
                broadcastVillage(v, "Aturan " + a[1] + " diubah menjadi " + (on ? "on" : "off") + " oleh " + p.getName());
                saveData();
            }
            case "keluar" -> {
                Village v = villageOf(p.getUniqueId());
                if (v == null) { msg(p, "Kamu belum jadi warga desa."); break; }
                String nama = v.name;
                leaveVillage(v, p.getUniqueId());
                msg(p, "Kamu keluar dari desa " + nama + ".");
            }
            default -> msg(p, "Perintah tidak dikenal.");
        }
        return true;
    }
                    }
