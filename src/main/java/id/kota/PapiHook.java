package id.kota;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class PapiHook extends PlaceholderExpansion {

    private final KotaOtomatis plugin;

    public PapiHook(KotaOtomatis plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() { return "kota"; }

    @Override
    public String getAuthor() { return "KotaOtomatis"; }

    @Override
    public String getVersion() { return plugin.getPluginMeta().getVersion(); }

    @Override
    public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        switch (params.toLowerCase()) {
            case "desa": return plugin.papiDesa(player.getUniqueId());
            case "pemimpin": return plugin.papiPemimpin(player.getUniqueId());
            case "warga": return plugin.papiWarga(player.getUniqueId());
            case "peran": return plugin.papiPeran(player.getUniqueId());
            case "lokasi":
                if (player instanceof Player p) return plugin.papiLokasi(p);
                return "-";
            default: return null;
        }
    }
}
