package com.fermabets.service;

import com.fermabets.AppClock;
import com.fermabets.domain.Stat;
import com.fermabets.model.Normal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Superbet.gr NBA player props via the public Fastly offer API the site itself uses. Unofficial; may break if the
 * offer endpoints change.
 */
@Component
public class SuperbetProvider implements OddsService.OddsProvider {
    private static final Logger log = LoggerFactory.getLogger(SuperbetProvider.class);
    private static final String OFFER = "https://production-superbet-offer-gr.freetls.fastly.net";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
            + " Chrome/124.0 Safari/537.36";
    private static final int NBA_TOURNAMENT = 164;
    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    static final Map<String, Stat> PLAYER_TOTALS = Map.of(
            "Player - Total Points (incl. overtime)", Stat.PTS,
            "Player - Total Rebounds (incl. overtime)", Stat.REB,
            "Player - Total Assists (incl. overtime)", Stat.AST,
            "Player - Total 3 Pointers Made (incl. overtime)", Stat.FG3M,
            "Player - Total Points + Rebounds + Assists (incl. overtime)", Stat.PRA,
            "Player - Total Points + Rebounds (incl. overtime)", Stat.PR,
            "Player - Total Points + Assists (incl. overtime)", Stat.PA,
            "Player - Total Rebounds + Assists (incl. overtime)", Stat.RA);
    /** N+ ladders (10+, 15+, 20+...) are Superbet's alternative player lines. */
    static final Map<String, Stat> PLAYER_MILESTONES = Map.of(
            "Player Points (incl. overtime)", Stat.PTS,
            "Player Rebounds (incl. overtime)", Stat.REB,
            "Player Assists (incl. overtime)", Stat.AST,
            "Player 3 Pointers Made (incl. overtime)", Stat.FG3M,
            "Player Points + Rebounds + Assists (incl. overtime)", Stat.PRA);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile List<OddsService.GameLine> games = List.of();

    @Override
    public String bookmaker() {
        return "Superbet";
    }

    @Override
    public List<OddsService.GameLine> gameLines() {
        return games;
    }

    @Override
    public List<OddsService.OddsLine> fetch(LocalDate date) throws Exception {
        List<OddsService.OddsLine> out = new ArrayList<>();
        List<OddsService.GameLine> gl = new ArrayList<>();
        List<Long> ids = listNbaEvents(date);
        int events = 0;
        for (Long id : ids) {
            events++;
            if (events > 1) {
                Thread.sleep(300);
            }
            JsonNode payload = readSseJson(OFFER + "/v3/subscription/en-GR/events?events=" + id);
            JsonNode event = payload.isArray() ? payload.get(0) : payload;
            if (event == null || event.isNull()) {
                continue;
            }
            String eventId = String.valueOf(id);
            out.addAll(parseEvent(event, date, eventId));
            parseGame(event, date, eventId).ifPresent(gl::add);
        }
        games = gl;
        log.info("Superbet: {} NBA events on {}, {} lines, {} game markets", events, date, out.size(), gl.size());
        return out;
    }

    List<Long> listNbaEvents(LocalDate date) throws Exception {
        Instant start = date.atStartOfDay(AppClock.NBA_ZONE).toInstant().minus(Duration.ofHours(6));
        Instant end = start.plus(Duration.ofHours(4000));
        Set<Long> ids = new LinkedHashSet<>();
        for (String index : List.of("active-prematch", "active-live")) {
            String url = OFFER + "/v3/en-GR/events?startDate=" + ISO_MILLIS.format(start)
                    + "&endDate=" + ISO_MILLIS.format(end) + "&index=" + index + "&tournaments=" + NBA_TOURNAMENT;
            JsonNode root = mapper.readTree(get(url));
            for (JsonNode ev : root.path("events")) {
                String utc = ev.path("fixture").path("utc_date").asText("");
                if (utc.isEmpty()) {
                    continue;
                }
                LocalDate ny = Instant.parse(utc).atZone(AppClock.NBA_ZONE).toLocalDate();
                if (ny.equals(date)) {
                    ids.add(ev.path("event_id").asLong());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    static List<OddsService.OddsLine> parseEvent(JsonNode event, LocalDate date, String eventId) {
        Map<String, OddsService.OddsLine> lines = new LinkedHashMap<>();
        List<OddsService.OddsLine> milestones = new ArrayList<>();
        for (JsonNode market : event.path("markets")) {
            String name = market.path("name").asText();
            Stat totalStat = PLAYER_TOTALS.get(name);
            Stat mileStat = totalStat == null ? PLAYER_MILESTONES.get(name) : null;
            if (totalStat == null && mileStat == null) {
                continue;
            }
            if (totalStat != null) {
                Map<String, Acc> acc = new LinkedHashMap<>();
                for (JsonNode odd : market.path("odds")) {
                    if (!live(odd)) {
                        continue;
                    }
                    JsonNode spec = odd.path("metadata").path("specifiers");
                    String playerRaw = spec.path("player").asText("");
                    String totalTxt = spec.path("total").asText("");
                    if (playerRaw.isEmpty() || totalTxt.isEmpty()) {
                        continue;
                    }
                    Double line = parseDouble(totalTxt);
                    if (line == null) {
                        continue;
                    }
                    String side = odd.path("metadata").path("name").asText("").toLowerCase(Locale.ROOT);
                    Acc a = acc.computeIfAbsent(playerRaw + "|" + line,
                            k -> new Acc(displayName(playerRaw), line));
                    if (side.contains("over")) {
                        a.over = odd.path("price").asDouble();
                    } else if (side.contains("under")) {
                        a.under = odd.path("price").asDouble();
                    }
                }
                for (Acc a : acc.values()) {
                    if (a.over == null && a.under == null) {
                        continue;
                    }
                    lines.put(a.player + "|" + totalStat + "|" + a.line,
                            new OddsService.OddsLine("Superbet", date, a.player, totalStat, a.line, a.over, a.under,
                                    eventId + "p:" + a.player));
                }
                continue;
            }
            for (JsonNode odd : market.path("odds")) {
                if (!live(odd)) {
                    continue;
                }
                JsonNode spec = odd.path("metadata").path("specifiers");
                String playerRaw = spec.path("player").asText("");
                String mileTxt = spec.path("milestone").asText("");
                if (playerRaw.isEmpty() || mileTxt.isEmpty()) {
                    continue;
                }
                Double mile = parseDouble(mileTxt);
                if (mile == null) {
                    continue;
                }
                double line = mile - 0.5;
                String player = displayName(playerRaw);
                milestones.add(new OddsService.OddsLine("Superbet", date, player, mileStat, line,
                        odd.path("price").asDouble(), null, eventId + "p:" + player));
            }
        }
        for (OddsService.OddsLine ml : milestones) {
            lines.putIfAbsent(ml.playerName() + "|" + ml.stat() + "|" + ml.line(), ml);
        }
        return new ArrayList<>(lines.values());
    }

    private static boolean live(JsonNode odd) {
        return odd.path("status").asInt(1) == 1 && odd.path("price").asDouble() > 1.0;
    }

    static Optional<OddsService.GameLine> parseGame(JsonNode event, LocalDate date, String eventId) {
        Double homeWin = null, margin = null, total = null, homeSpread = null;
        double bestGap = Double.MAX_VALUE, bestTotalGap = Double.MAX_VALUE;
        for (JsonNode market : event.path("markets")) {
            String name = market.path("name").asText();
            if (name.equals("To Win the Match (incl. overtime)")) {
                Double pHome = null, pAway = null;
                for (JsonNode odd : market.path("odds")) {
                    if (odd.path("status").asInt(1) != 1 || odd.path("price").asDouble() <= 1) {
                        continue;
                    }
                    String code = odd.path("metadata").path("code").asText();
                    if ("1".equals(code)) {
                        pHome = odd.path("price").asDouble();
                    } else if ("2".equals(code)) {
                        pAway = odd.path("price").asDouble();
                    }
                }
                if (pHome != null && pAway != null) {
                    homeWin = (1 / pHome) / (1 / pHome + 1 / pAway);
                }
                continue;
            }
            Map<String, Pair> pairs = new LinkedHashMap<>();
            if (name.equals("Points Handicap (incl. overtime)")) {
                for (JsonNode odd : market.path("odds")) {
                    if (odd.path("status").asInt(1) != 1 || odd.path("price").asDouble() <= 1) {
                        continue;
                    }
                    JsonNode meta = odd.path("metadata");
                    String uuid = meta.path("market_line_uuid").asText();
                    Pair p = pairs.computeIfAbsent(uuid, k -> new Pair());
                    if ("1".equals(meta.path("code").asText())) {
                        p.home = odd.path("price").asDouble();
                        p.hcp = parseDouble(meta.path("specifiers").path("hcp").asText());
                    } else if ("2".equals(meta.path("code").asText())) {
                        p.away = odd.path("price").asDouble();
                    }
                }
                for (Pair p : pairs.values()) {
                    if (p.home == null || p.away == null || p.hcp == null) {
                        continue;
                    }
                    double qHome = (1 / p.home) / (1 / p.home + 1 / p.away);
                    double gap = Math.abs(qHome - 0.5);
                    if (gap < bestGap) {
                        bestGap = gap;
                        homeSpread = p.hcp;
                        margin = -p.hcp + 12.0 * Normal.inv(qHome);
                    }
                }
            } else if (name.equals("Total Points (incl. overtime)")) {
                for (JsonNode odd : market.path("odds")) {
                    if (odd.path("status").asInt(1) != 1 || odd.path("price").asDouble() <= 1) {
                        continue;
                    }
                    JsonNode meta = odd.path("metadata");
                    String uuid = meta.path("market_line_uuid").asText();
                    Pair p = pairs.computeIfAbsent(uuid, k -> new Pair());
                    String nm = meta.path("name").asText("").toLowerCase(Locale.ROOT);
                    if (nm.startsWith("over")) {
                        p.home = odd.path("price").asDouble();
                        p.hcp = parseDouble(meta.path("specifiers").path("total").asText());
                    } else if (nm.startsWith("under")) {
                        p.away = odd.path("price").asDouble();
                    }
                }
                for (Pair p : pairs.values()) {
                    if (p.home == null || p.away == null || p.hcp == null) {
                        continue;
                    }
                    double qOver = (1 / p.home) / (1 / p.home + 1 / p.away);
                    double gap = Math.abs(qOver - 0.5);
                    if (gap < bestTotalGap) {
                        bestTotalGap = gap;
                        total = p.hcp + 18.0 * Normal.inv(qOver);
                    }
                }
            }
        }
        if (margin == null && homeWin != null) {
            margin = 12.0 * Normal.inv(homeWin);
        }
        if (margin == null) {
            return Optional.empty();
        }
        return Optional.of(new OddsService.GameLine("Superbet", "NBA", date, eventId + "homeRoster",
                eventId + "awayRoster", margin, total, homeWin, homeSpread));
    }

    static String displayName(String raw) {
        int c = raw.indexOf(',');
        if (c > 0) {
            String last = raw.substring(0, c).trim();
            String first = raw.substring(c + 1).trim();
            if (!first.isEmpty() && !last.isEmpty()) {
                return first + " " + last;
            }
        }
        return raw.trim();
    }

    private JsonNode readSseJson(String url) throws Exception {
        HttpRequest req = request(url).header("Accept", "text/event-stream").build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close();
            throw new IllegalStateException("HTTP " + res.statusCode() + " for " + url);
        }
        try (InputStream in = res.body()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[65536];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buf.write(chunk, 0, n);
                byte[] b = buf.toByteArray();
                int nl = indexOf(b, (byte) '\n');
                if (nl >= 0) {
                    String line = new String(b, 0, nl, StandardCharsets.UTF_8);
                    if (line.startsWith("data:")) {
                        line = line.substring(5);
                    }
                    return mapper.readTree(line);
                }
                if (buf.size() > 8_000_000) {
                    break;
                }
            }
            throw new IllegalStateException("Superbet event stream ended without a payload");
        }
    }

    private String get(String url) throws Exception {
        HttpResponse<String> res = http.send(request(url).timeout(Duration.ofSeconds(40)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " for " + url);
        }
        return res.body();
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-GR,en;q=0.9")
                .header("Origin", "https://superbet.gr")
                .header("Referer", "https://superbet.gr/")
                .GET();
    }

    private static int indexOf(byte[] b, byte target) {
        for (int i = 0; i < b.length; i++) {
            if (b[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static Double parseDouble(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class Acc {
        final String player;
        final double line;
        Double over;
        Double under;

        Acc(String player, double line) {
            this.player = player;
            this.line = line;
        }
    }

    private static final class Pair {
        Double home;
        Double away;
        Double hcp;
    }
}
