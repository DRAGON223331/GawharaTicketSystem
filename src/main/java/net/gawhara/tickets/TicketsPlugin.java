package net.gawhara.tickets;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GawharaTickets - /report من داخل اللعبة => Thread جديد في روم الإدارة على ديسكورد.
 * بيستخدم Discord REST API بالـ Bot Token (مفيش مكتبات خارجية).
 */
public final class TicketsPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final String API = "https://discord.com/api/v10";
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    /** تذكرة واحدة. */
    static final class Ticket {
        int id;
        UUID uuid;
        String name = "";
        String message = "";
        String threadId = "";
        String threadName = "";
        String lastMsgId = "";
        boolean open = true;
        String closedBy = "";
        long createdSec;
    }

    /** بيانات اللاعب وقت البلاغ (بتتجمع على الـ main thread). */
    record Snapshot(String world, int x, int y, int z, String gamemode, int ping, int online, String version) {
    }

    static final class DiscordException extends IOException {
        final int status;

        DiscordException(int status, String body) {
            super(describe(status, body));
            this.status = status;
        }

        private static String describe(int status, String body) {
            return switch (status) {
                case 401 -> "HTTP 401: الـ bot-token غلط";
                case 403 -> "HTTP 403: البوت معندوش صلاحية (محتاج View Channel + Send Messages + "
                        + "Create Public Threads + Send Messages in Threads)";
                case 404 -> "HTTP 404: الـ channel-id غلط أو البوت مش شايف الروم";
                default -> "HTTP " + status + ": " + (body == null ? "" : body.substring(0, Math.min(200, body.length())));
            };
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GawharaTickets-IO");
        t.setDaemon(true);
        return t;
    });

    private final Map<Integer, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private volatile int channelType = -1;
    private volatile boolean warnedContent;
    private BukkitTask relayTask;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (String cmd : List.of("report", "tickets")) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(this);
                getCommand(cmd).setTabCompleter(this);
            }
        }
        startRelay();
        if (!discordReady()) {
            getLogger().warning("حط discord.bot-token و discord.channel-id في config.yml ثم /tickets reload");
        }
    }

    @Override
    public void onDisable() {
        if (relayTask != null) {
            relayTask.cancel();
        }
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        saveData();
    }

    // ------------------------------------------------------------------ data

    private File dataFile() {
        return new File(getDataFolder(), "tickets.yml");
    }

    private void loadData() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile());
        nextId.set(Math.max(1, y.getInt("next-id", 1)));
        ConfigurationSection ts = y.getConfigurationSection("tickets");
        if (ts != null) {
            for (String key : ts.getKeys(false)) {
                ConfigurationSection s = ts.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                try {
                    Ticket t = new Ticket();
                    t.id = Integer.parseInt(key);
                    t.uuid = UUID.fromString(s.getString("uuid", ""));
                    t.name = s.getString("name", "");
                    t.message = s.getString("message", "");
                    t.threadId = s.getString("thread-id", "");
                    t.threadName = s.getString("thread-name", "");
                    t.lastMsgId = s.getString("last-msg-id", "");
                    t.open = s.getBoolean("open", true);
                    t.closedBy = s.getString("closed-by", "");
                    t.createdSec = s.getLong("created", 0L);
                    tickets.put(t.id, t);
                } catch (IllegalArgumentException ignored) {
                    getLogger().warning("تذكرة تالفة في tickets.yml: " + key);
                }
            }
        }
        ConfigurationSection ns = y.getConfigurationSection("notices");
        if (ns != null) {
            for (String key : ns.getKeys(false)) {
                try {
                    pending.put(UUID.fromString(key), Collections.synchronizedList(new ArrayList<>(ns.getStringList(key))));
                } catch (IllegalArgumentException ignored) {
                    // تجاهل
                }
            }
        }
    }

    private synchronized void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId.get());
        for (Ticket t : tickets.values()) {
            String p = "tickets." + t.id + ".";
            y.set(p + "uuid", t.uuid.toString());
            y.set(p + "name", t.name);
            y.set(p + "message", t.message);
            y.set(p + "thread-id", t.threadId);
            y.set(p + "thread-name", t.threadName);
            y.set(p + "last-msg-id", t.lastMsgId);
            y.set(p + "open", t.open);
            y.set(p + "closed-by", t.closedBy);
            y.set(p + "created", t.createdSec);
        }
        for (Map.Entry<UUID, List<String>> e : pending.entrySet()) {
            List<String> copy;
            synchronized (e.getValue()) {
                copy = new ArrayList<>(e.getValue());
            }
            if (!copy.isEmpty()) {
                y.set("notices." + e.getKey(), copy);
            }
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile());
        } catch (IOException e) {
            getLogger().warning("تعذر حفظ tickets.yml: " + e.getMessage());
        }
    }

    private void saveAsync() {
        if (isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, this::saveData);
        } else {
            saveData();
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean discordReady() {
        return !getConfig().getString("discord.bot-token", "").isBlank()
                && !getConfig().getString("discord.channel-id", "").isBlank();
    }

    private String channelId() {
        return getConfig().getString("discord.channel-id", "").trim();
    }

    private Component text(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }

    /** بيرجع رسالة من config.messages مع استبدال {key} بالقيم (أزواج مفتاح/قيمة). */
    private String msg(String key, String... kv) {
        String s = getConfig().getString("messages." + key, "");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return s;
    }

    private void tell(CommandSender to, String key, String... kv) {
        to.sendMessage(text(getConfig().getString("messages.prefix", "") + msg(key, kv)));
    }

    private static String truncate(String s, int n) {
        if (s.length() <= n) {
            return s;
        }
        String cut = s.substring(0, n - 1);
        if (Character.isHighSurrogate(cut.charAt(cut.length() - 1))) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    private static String ago(long sec) {
        long d = Math.max(0, System.currentTimeMillis() / 1000L - sec);
        if (d < 3600) {
            return Math.max(1, d / 60) + " د";
        }
        if (d < 86400) {
            return d / 3600 + " س";
        }
        return d / 86400 + " يوم";
    }

    // ------------------------------------------------------------------ discord REST

    private String request(String method, String path, JsonElement body) throws IOException, InterruptedException {
        String token = getConfig().getString("discord.bot-token", "").trim();
        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bot " + token)
                .header("Content-Type", "application/json")
                .header("User-Agent", "DiscordBot (https://gawhara.net, 1.0)")
                .method(method, pub)
                .build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() == 429) {
            long waitMs = 1500;
            try {
                double ra = JsonParser.parseString(r.body()).getAsJsonObject().get("retry_after").getAsDouble();
                waitMs = (long) (ra * 1000);
            } catch (RuntimeException ignored) {
                // نفضل على الافتراضي
            }
            Thread.sleep(Math.max(500, Math.min(6000, waitMs)));
            r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        if (r.statusCode() / 100 != 2) {
            throw new DiscordException(r.statusCode(), r.body());
        }
        return r.body();
    }

    private JsonObject requestObj(String method, String path, JsonElement body) throws IOException, InterruptedException {
        return JsonParser.parseString(request(method, path, body)).getAsJsonObject();
    }

    private int channelType() throws IOException, InterruptedException {
        if (channelType < 0) {
            JsonObject ch = requestObj("GET", "/channels/" + channelId(), null);
            channelType = ch.get("type").getAsInt();
        }
        return channelType;
    }

    private static JsonObject noMentions() {
        JsonObject am = new JsonObject();
        am.add("parse", new JsonArray());
        return am;
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", truncate(value, 1024));
        f.addProperty("inline", inline);
        return f;
    }

    private JsonObject buildEmbed(Ticket t, Snapshot s) {
        JsonObject e = new JsonObject();
        e.addProperty("title", "🎫 تذكرة #" + t.id);
        e.addProperty("description", truncate(t.message, 3500));
        e.addProperty("color", 0xF5A623);
        JsonArray fields = new JsonArray();
        fields.add(field("👤 اللاعب", "**" + t.name + "**", true));
        fields.add(field("🌍 المكان", s.world() + "\n`" + s.x() + ", " + s.y() + ", " + s.z() + "`", true));
        fields.add(field("🎮 الوضع", s.gamemode(), true));
        fields.add(field("📶 Ping", s.ping() + " ms", true));
        fields.add(field("👥 أونلاين", String.valueOf(s.online()), true));
        fields.add(field("🕒 الوقت", "<t:" + t.createdSec + ":R>", true));
        fields.add(field("🆔 UUID", "```\n" + t.uuid + "\n```", false));
        e.add("fields", fields);
        JsonObject thumb = new JsonObject();
        thumb.addProperty("url", "https://mc-heads.net/avatar/" + t.name + "/128");
        e.add("thumbnail", thumb);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Minecraft " + s.version());
        e.add("footer", footer);
        e.addProperty("timestamp", Instant.now().toString());
        return e;
    }

    private String threadTitle(Ticket t) {
        String snippet = t.message.replaceAll("\\s+", " ").trim();
        return truncate("🎫 #" + t.id + " | " + t.name + " — " + snippet, 100);
    }

    /** بيشتغل على thread الـ IO: بيعمل رسالة + Thread (أو Forum post) للتذكرة. */
    private void createDiscordTicket(Ticket t, Snapshot snap) {
        try {
            if (!discordReady()) {
                throw new IOException("bot-token / channel-id غير مضبوط في config.yml");
            }
            String role = getConfig().getString("discord.staff-role-id", "").trim();
            JsonObject message = new JsonObject();
            if (!role.isEmpty()) {
                message.addProperty("content", "<@&" + role + ">");
            }
            JsonArray embeds = new JsonArray();
            embeds.add(buildEmbed(t, snap));
            message.add("embeds", embeds);
            JsonObject am = new JsonObject();
            am.add("parse", new JsonArray());
            if (!role.isEmpty()) {
                JsonArray roles = new JsonArray();
                roles.add(role);
                am.add("roles", roles);
            }
            message.add("allowed_mentions", am);

            String title = threadTitle(t);
            int archive = getConfig().getInt("discord.auto-archive-minutes", 1440);
            String threadId;
            int type = channelType();
            if (type == 15 || type == 16) {
                // Forum / Media: بوست جديد = Thread
                JsonObject body = new JsonObject();
                body.addProperty("name", title);
                body.addProperty("auto_archive_duration", archive);
                body.add("message", message);
                threadId = requestObj("POST", "/channels/" + channelId() + "/threads", body).get("id").getAsString();
            } else {
                // روم عادية: رسالة ثم Thread منها
                String msgId = requestObj("POST", "/channels/" + channelId() + "/messages", message).get("id").getAsString();
                JsonObject tb = new JsonObject();
                tb.addProperty("name", title);
                tb.addProperty("auto_archive_duration", archive);
                threadId = requestObj("POST", "/channels/" + channelId() + "/messages/" + msgId + "/threads", tb)
                        .get("id").getAsString();
            }
            t.threadId = threadId;
            t.threadName = title;
            t.lastMsgId = threadId;

            String intro = msg("thread-intro", "id", String.valueOf(t.id));
            if (!intro.isBlank()) {
                JsonObject ib = new JsonObject();
                ib.addProperty("content", intro);
                ib.add("allowed_mentions", noMentions());
                t.lastMsgId = requestObj("POST", "/channels/" + threadId + "/messages", ib).get("id").getAsString();
            }
            saveAsync();
        } catch (IOException | RuntimeException e) {
            fail(t, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(t, e);
        }
    }

    private void fail(Ticket t, Exception e) {
        getLogger().warning("فشل إنشاء Thread للتذكرة #" + t.id + ": " + e.getMessage());
        tickets.remove(t.id);
        saveAsync();
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, () -> notifyPlayer(t.uuid, getConfig().getString("messages.prefix", "")
                    + msg("send-failed")));
        }
    }

    // ------------------------------------------------------------------ notices

    /** بيوصّل سطر للاعب لو أونلاين، أو بيحفظه لحد ما يدخل. بيتنده من الـ main thread. */
    private void notifyPlayer(UUID uuid, String legacyLine) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            p.sendMessage(text(legacyLine));
        } else {
            pending.computeIfAbsent(uuid, k -> Collections.synchronizedList(new ArrayList<>())).add(legacyLine);
            saveAsync();
        }
    }

    private void notifyPlayerSync(UUID uuid, String legacyLine) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, () -> notifyPlayer(uuid, legacyLine));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            List<String> list = pending.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (list == null || p == null) {
                return;
            }
            List<String> copy;
            synchronized (list) {
                copy = new ArrayList<>(list);
            }
            for (String line : copy) {
                p.sendMessage(text(line));
            }
            saveAsync();
        }, 60L);
    }

    // ------------------------------------------------------------------ relay (Discord -> game)

    private void startRelay() {
        if (relayTask != null) {
            relayTask.cancel();
            relayTask = null;
        }
        if (!getConfig().getBoolean("relay.discord-to-game", true)) {
            return;
        }
        long ticks = Math.max(10, getConfig().getInt("relay.poll-seconds", 20)) * 20L;
        relayTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pollReplies, ticks, ticks);
    }

    private void pollReplies() {
        if (!discordReady()) {
            return;
        }
        List<Ticket> open = new ArrayList<>();
        for (Ticket t : tickets.values()) {
            if (t.open && !t.threadId.isBlank()) {
                open.add(t);
            }
        }
        open.sort(Comparator.comparingInt(t -> t.id));
        int limit = Math.min(open.size(), 25);
        for (int i = 0; i < limit; i++) {
            Ticket t = open.get(i);
            try {
                String after = t.lastMsgId.isBlank() ? t.threadId : t.lastMsgId;
                JsonArray arr = JsonParser.parseString(
                        request("GET", "/channels/" + t.threadId + "/messages?after=" + after + "&limit=25", null))
                        .getAsJsonArray();
                if (arr.isEmpty()) {
                    continue;
                }
                // الـ API بيرجع الأحدث الأول
                t.lastMsgId = arr.get(0).getAsJsonObject().get("id").getAsString();
                for (int k = arr.size() - 1; k >= 0; k--) {
                    JsonObject m = arr.get(k).getAsJsonObject();
                    JsonObject author = m.getAsJsonObject("author");
                    boolean bot = author.has("bot") && author.get("bot").getAsBoolean();
                    int type = m.has("type") ? m.get("type").getAsInt() : 0;
                    if (bot || (type != 0 && type != 19)) {
                        continue;
                    }
                    String content = m.has("content") ? m.get("content").getAsString().trim() : "";
                    if (content.isEmpty()) {
                        boolean hasAttach = m.has("attachments") && !m.getAsJsonArray("attachments").isEmpty();
                        if (!hasAttach && !warnedContent) {
                            warnedContent = true;
                            getLogger().warning("رد من الديسكورد وصل فاضي. فعّل Message Content Intent للبوت "
                                    + "(Developer Portal > Bot > Privileged Gateway Intents).");
                        }
                        continue;
                    }
                    String who = author.has("global_name") && !author.get("global_name").isJsonNull()
                            ? author.get("global_name").getAsString()
                            : author.get("username").getAsString();
                    String line = getConfig().getString("messages.prefix", "")
                            + msg("staff-reply", "id", String.valueOf(t.id), "staff", who,
                            "msg", truncate(content, 400));
                    notifyPlayerSync(t.uuid, line);
                }
                saveAsync();
            } catch (DiscordException e) {
                if (e.status != 403 && e.status != 404) {
                    getLogger().fine("relay تذكرة #" + t.id + ": " + e.getMessage());
                }
            } catch (IOException | RuntimeException e) {
                getLogger().fine("relay تذكرة #" + t.id + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("report")) {
            return handleReport(sender, args);
        }
        return handleTickets(sender, args);
    }

    private boolean handleReport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            tell(sender, "players-only");
            return true;
        }
        if (args.length == 0) {
            tell(p, "usage");
            return true;
        }
        String text = String.join(" ", args).trim();
        int min = getConfig().getInt("tickets.min-length", 8);
        int max = getConfig().getInt("tickets.max-length", 800);
        if (text.length() < min) {
            tell(p, "too-short", "min", String.valueOf(min));
            return true;
        }
        if (text.length() > max) {
            tell(p, "too-long", "max", String.valueOf(max));
            return true;
        }
        long now = System.currentTimeMillis();
        long cd = getConfig().getInt("tickets.cooldown-seconds", 60) * 1000L;
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < cd && !p.hasPermission("gawharatickets.staff")) {
            tell(p, "cooldown", "seconds", String.valueOf((cd - (now - last) + 999) / 1000));
            return true;
        }
        int maxOpen = getConfig().getInt("tickets.max-open-per-player", 3);
        long openCount = tickets.values().stream().filter(t -> t.open && t.uuid.equals(p.getUniqueId())).count();
        if (openCount >= maxOpen) {
            tell(p, "too-many-open", "count", String.valueOf(openCount));
            return true;
        }
        cooldowns.put(p.getUniqueId(), now);

        Ticket t = new Ticket();
        t.id = nextId.getAndIncrement();
        t.uuid = p.getUniqueId();
        t.name = p.getName();
        t.message = text;
        t.createdSec = now / 1000L;
        tickets.put(t.id, t);
        saveAsync();

        var loc = p.getLocation();
        Snapshot snap = new Snapshot(p.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                p.getGameMode().name(), p.getPing(), Bukkit.getOnlinePlayers().size(), Bukkit.getMinecraftVersion());

        tell(p, "sent", "id", String.valueOf(t.id));
        io.execute(() -> createDiscordTicket(t, snap));
        return true;
    }

    private Ticket parseTicket(CommandSender sender, String arg) {
        try {
            Ticket t = tickets.get(Integer.parseInt(arg.replace("#", "")));
            if (t != null) {
                return t;
            }
        } catch (NumberFormatException ignored) {
            // تحت
        }
        sender.sendMessage(text("&cمفيش تذكرة بالرقم ده."));
        return null;
    }

    private boolean handleTickets(CommandSender sender, String[] args) {
        if (!sender.hasPermission("gawharatickets.staff")) {
            tell(sender, "no-permission");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                channelType = -1;
                warnedContent = false;
                startRelay();
                sender.sendMessage(text("&aتم إعادة تحميل الإعدادات."));
            }
            case "list" -> {
                List<Ticket> open = new ArrayList<>();
                for (Ticket t : tickets.values()) {
                    if (t.open) {
                        open.add(t);
                    }
                }
                open.sort(Comparator.comparingInt((Ticket t) -> t.id).reversed());
                if (open.isEmpty()) {
                    sender.sendMessage(text("&aمفيش تذاكر مفتوحة."));
                    return true;
                }
                sender.sendMessage(text("&6التذاكر المفتوحة &7(" + open.size() + "):"));
                for (int i = 0; i < Math.min(open.size(), 15); i++) {
                    Ticket t = open.get(i);
                    sender.sendMessage(text("&e#" + t.id + " &f" + t.name + " &8- &7"
                            + truncate(t.message.replaceAll("\\s+", " "), 50) + " &8(" + ago(t.createdSec) + ")"));
                }
            }
            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(text("&cاستخدم: /tickets info <رقم>"));
                    return true;
                }
                Ticket t = parseTicket(sender, args[1]);
                if (t == null) {
                    return true;
                }
                sender.sendMessage(text("&6تذكرة #" + t.id + " &7- " + (t.open ? "&aمفتوحة" : "&cمقفولة")));
                sender.sendMessage(text("&eاللاعب: &f" + t.name + " &8| &eمن: &f" + ago(t.createdSec)));
                sender.sendMessage(text("&eالرسالة: &f" + t.message));
                if (!t.threadId.isBlank()) {
                    sender.sendMessage(text("&eThread ID: &f" + t.threadId));
                }
            }
            case "reply" -> {
                if (args.length < 3) {
                    sender.sendMessage(text("&cاستخدم: /tickets reply <رقم> <الرد>"));
                    return true;
                }
                Ticket t = parseTicket(sender, args[1]);
                if (t == null) {
                    return true;
                }
                if (!t.open || t.threadId.isBlank()) {
                    sender.sendMessage(text("&cالتذكرة مقفولة أو لسه ما اتعملش لها Thread."));
                    return true;
                }
                String reply = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                String staff = sender.getName();
                io.execute(() -> {
                    try {
                        JsonObject b = new JsonObject();
                        b.addProperty("content", truncate("**" + staff + "** (من داخل السيرفر): " + reply, 1900));
                        b.add("allowed_mentions", noMentions());
                        String id = requestObj("POST", "/channels/" + t.threadId + "/messages", b).get("id").getAsString();
                        t.lastMsgId = id;
                    } catch (IOException | RuntimeException e) {
                        getLogger().warning("فشل إرسال الرد على #" + t.id + ": " + e.getMessage());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                notifyPlayer(t.uuid, getConfig().getString("messages.prefix", "")
                        + msg("staff-reply", "id", String.valueOf(t.id), "staff", staff, "msg", truncate(reply, 400)));
                sender.sendMessage(text("&aاتبعت الرد للاعب وللـ Thread."));
            }
            case "close" -> {
                if (args.length < 2) {
                    sender.sendMessage(text("&cاستخدم: /tickets close <رقم> [السبب]"));
                    return true;
                }
                Ticket t = parseTicket(sender, args[1]);
                if (t == null) {
                    return true;
                }
                if (!t.open) {
                    sender.sendMessage(text("&cالتذكرة مقفولة بالفعل."));
                    return true;
                }
                String reason = args.length > 2
                        ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
                String staff = sender.getName();
                t.open = false;
                t.closedBy = staff;
                saveAsync();
                io.execute(() -> closeThread(t, staff, reason));
                notifyPlayer(t.uuid, getConfig().getString("messages.prefix", "")
                        + msg("closed-notice", "id", String.valueOf(t.id), "staff", staff, "reason", reason));
                sender.sendMessage(text("&aتم إغلاق التذكرة #" + t.id + "."));
            }
            default -> sender.sendMessage(text("&cاستخدم: /tickets <list|info|reply|close|reload>"));
        }
        return true;
    }

    private void closeThread(Ticket t, String staff, String reason) {
        if (t.threadId.isBlank()) {
            return;
        }
        try {
            JsonObject b = new JsonObject();
            b.addProperty("content", "🔒 اتقفلت التذكرة بواسطة **" + staff + "**"
                    + (reason.isBlank() ? "" : "\nالسبب: " + truncate(reason, 1500)));
            b.add("allowed_mentions", noMentions());
            request("POST", "/channels/" + t.threadId + "/messages", b);

            String base = t.threadName.isBlank() ? "تذكرة #" + t.id : t.threadName;
            String newName = truncate(base.startsWith("🎫") ? "✅" + base.substring("🎫".length()) : base, 100);
            JsonObject patch = new JsonObject();
            patch.addProperty("name", newName);
            patch.addProperty("archived", true);
            patch.addProperty("locked", true);
            try {
                request("PATCH", "/channels/" + t.threadId, patch);
            } catch (DiscordException e) {
                // لو مفيش صلاحية Manage Threads: نأرشف بس من غير قفل أو تغيير اسم
                JsonObject lite = new JsonObject();
                lite.addProperty("archived", true);
                request("PATCH", "/channels/" + t.threadId, lite);
            }
        } catch (IOException | RuntimeException e) {
            getLogger().warning("فشل قفل Thread التذكرة #" + t.id + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!command.getName().equalsIgnoreCase("tickets") || !sender.hasPermission("gawharatickets.staff")) {
            return out;
        }
        if (args.length == 1) {
            for (String s : List.of("list", "info", "reply", "close", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && List.of("info", "reply", "close").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Ticket t : tickets.values()) {
                if (t.open && String.valueOf(t.id).startsWith(args[1])) {
                    out.add(String.valueOf(t.id));
                }
            }
        }
        return out;
    }
}
