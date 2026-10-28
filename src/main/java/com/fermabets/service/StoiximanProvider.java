package com.fermabets.service;

import com.fermabets.domain.Stat;
import com.fermabets.model.Normal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort Stoiximan.gr scraper (NBA, Euroleague, EuroCup player props). It reads the public league pages and the
 * public bet-builder JSON the site itself uses. It is unofficial and may break whenever the site changes.
 */
@Component
public class StoiximanProvider implements OddsService.OddsProvider {
    private static final Logger log = LoggerFactory.getLogger(StoiximanProvider.class);
    private static final String BASE = "https://www.stoiximan.gr";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
            + " Chrome/124.0 Safari/537.36";
    private static final String STATE_MARK = "window[\"initial_state\"]=";

    private record League(String code, String path, ZoneId zone, double marginSd, double totalSd) {
    }

    private static final List<League> LEAGUES = List.of(
            new League("NBA", "/sport/basket/nba/441g/", ZoneId.of("America/New_York"), 12.0, 18.0),
            new League("EL", "/sport/basket/euroleague/euroleague/439g/", ZoneId.of("Europe/Athens"), 10.5, 14.0),
            new League("EC", "/sport/basket/eurocup/eurocup/455g/", ZoneId.of("Europe/Athens"), 10.5, 14.0));

    private static final Map<Integer, Stat> OVER_UNDER = Map.of(
            1970, Stat.PTS, 1973, Stat.REB, 1965, Stat.AST, 1964, Stat.FG3M);
    private static final Map<Integer, Stat> MILESTONE = Map.of(
            1856, Stat.PTS, 1858, Stat.REB, 1853, Stat.AST, 1852, Stat.FG3M);
    private static final Pattern BRACKET = Pattern.compile("\\[(.+)]");
    private static final Pattern PLUS = Pattern.compile("^(\\d+)\\+$");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Override
    public String bookmaker() {
        return "Stoiximan";
    }

    private volatile List<OddsService.GameLine> games = List.of();

    @Override
    public List<OddsService.GameLine> gameLines() {
        return games;
    }

    @Override
    public List<OddsService.OddsLine> fetch(LocalDate date) throws Exception {
        List<OddsService.OddsLine> out = new ArrayList<>();
        List<OddsService.GameLine> gl = new ArrayList<>();
        int events = 0;
        for (League league : LEAGUES) {
            JsonNode state;
            try {
                state = initialState(get(BASE + league.path()));
            } catch (Exception e) {
                log.warn("Stoiximan league page {} failed: {}", league.path(), e.getMessage());
                continue;
            }
            Map<Long, Long> found = new LinkedHashMap<>();
            collectEvents(state, found);
            Map<Long, String> eventNames = new HashMap<>();
            collectNames(state, eventNames);
            for (var e : found.entrySet()) {
                LocalDate eventDate = Instant.ofEpochMilli(e.getValue()).atZone(league.zone()).toLocalDate();
                if (!eventDate.equals(date)) {
                    continue;
                }
                events++;
                try {
                    Thread.sleep(600);
                    String json = get(BASE + "/api/betbuilderplus/event?id=" + e.getKey() + "&sportCode=BASK");
                    JsonNode root = mapper.readTree(json);
                    parseEvent(root, date, league.code() + "|" + e.getKey()).forEach(out::add);
                    parseGame(root, date, String.valueOf(e.getKey()), league.code(), eventNames.get(e.getKey()),
                            league.marginSd(), league.totalSd()).ifPresent(gl::add);
                } catch (Exception ex) {
                    log.warn("Stoiximan event {} failed: {}", e.getKey(), ex.getMessage());
                }
            }
        }
        games = gl;
        log.info("Stoiximan: {} events on {}, {} lines, {} game markets", events, date, out.size(), gl.size());
        return out;
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40))
                .header("User-Agent", UA).header("Accept-Language", "el,en;q=0.8").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " for " + url);
        }
        return res.body();
    }

    JsonNode initialState(String html) throws Exception {
        int i = html.indexOf(STATE_MARK);
        if (i < 0) {
            throw new IllegalStateException("initial_state not found (site layout changed or access blocked)");
        }
        return mapper.readTree(html.substring(i + STATE_MARK.length()));
    }

    /** Events are objects with startTime, id and a url containing /apodoseis/. */
    static void collectEvents(JsonNode n, Map<Long, Long> out) {
        if (n.isObject()) {
            if (n.has("startTime") && n.has("id") && n.path("url").asText("").contains("/apodoseis/")) {
                out.put(n.get("id").asLong(), n.get("startTime").asLong());
            }
            n.elements().forEachRemaining(c -> collectEvents(c, out));
        } else if (n.isArray()) {
            n.elements().forEachRemaining(c -> collectEvents(c, out));
        }
    }

    static void collectNames(JsonNode n, Map<Long, String> out) {
        if (n.isObject()) {
            if (n.has("startTime") && n.has("id") && n.has("name")
                    && n.path("url").asText("").contains("/apodoseis/")) {
                out.put(n.get("id").asLong(), n.get("name").asText());
            }
            n.elements().forEachRemaining(c -> collectNames(c, out));
        } else if (n.isArray()) {
            n.elements().forEachRemaining(c -> collectNames(c, out));
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /**
     * Game-level view: the handicap and total whose two prices are closest to even are the "main" lines. Removing the
     * margin and shifting by the price imbalance gives the expected home margin and total points.
     * The home team is the first one in the event name ("Home - Away").
     */
    static Optional<OddsService.GameLine> parseGame(JsonNode root, LocalDate date, String eventId, String league,
                                                    String eventName, double marginSd, double totalSd) {
        JsonNode data = root.path("data");
        JsonNode selections = data.path("selections");
        String homeName = eventName == null || !eventName.contains(" - ") ? null
                : norm(eventName.substring(0, eventName.indexOf(" - ")));
        Double margin = null, total = null, homeWin = null;
        double bestGap = Double.MAX_VALUE, bestTotalGap = Double.MAX_VALUE;
        for (JsonNode m : data.path("markets")) {
            int type = m.path("typeId").asInt();
            if (type != 155 && type != 156 && type != 157) {
                continue;
            }
            List<JsonNode> sel = new ArrayList<>();
            for (JsonNode id : m.path("selectionIdList")) {
                sel.add(selections.path(id.asText()));
            }
            if (sel.size() != 2 || !sel.get(0).has("price") || !sel.get(1).has("price")) {
                continue;
            }
            double p0 = sel.get(0).get("price").asDouble(), p1 = sel.get(1).get("price").asDouble();
            double q0 = (1 / p0) / (1 / p0 + 1 / p1);
            if (type == 157) {
                JsonNode over = sel.get(0).path("name").asText("").toLowerCase(Locale.ROOT).startsWith("over")
                        ? sel.get(0) : sel.get(1);
                JsonNode under = over == sel.get(0) ? sel.get(1) : sel.get(0);
                if (!over.has("handicap") || !under.path("name").asText("").toLowerCase(Locale.ROOT)
                        .startsWith("under")) {
                    continue;
                }
                double qOver = (1 / over.get("price").asDouble())
                        / (1 / over.get("price").asDouble() + 1 / under.get("price").asDouble());
                double gap = Math.abs(qOver - 0.5);
                if (gap < bestTotalGap) {
                    bestTotalGap = gap;
                    total = over.get("handicap").asDouble() + totalSd * Normal.inv(qOver);
                }
                continue;
            }
            boolean firstIsHome = homeName != null && norm(sel.get(0).path("name").asText()).startsWith(homeName);
            boolean secondIsHome = homeName != null && norm(sel.get(1).path("name").asText()).startsWith(homeName);
            if (firstIsHome == secondIsHome) {
                continue;
            }
            JsonNode home = firstIsHome ? sel.get(0) : sel.get(1);
            double qHome = firstIsHome ? q0 : 1 - q0;
            if (type == 155) {
                homeWin = qHome;
            } else if (home.has("handicap")) {
                double gap = Math.abs(qHome - 0.5);
                if (gap < bestGap) {
                    bestGap = gap;
                    margin = -home.get("handicap").asDouble() + marginSd * Normal.inv(qHome);
                }
            }
        }
        if (margin == null && homeWin != null) {
            margin = marginSd * Normal.inv(homeWin);
        }
        if (margin == null) {
            return Optional.empty();
        }
        return Optional.of(new OddsService.GameLine("Stoiximan", league, date, league + "|" + eventId + "homeRoster",
                league + "|" + eventId + "awayRoster", margin, total, homeWin));
    }

    /** Parses one bet-builder payload into prop lines. Over/under markets win over milestone (N+) ladders. */
    static List<OddsService.OddsLine> parseEvent(JsonNode root, LocalDate date) {
        return parseEvent(root, date, "0");
    }

    static List<OddsService.OddsLine> parseEvent(JsonNode root, LocalDate date, String eventId) {
        JsonNode data = root.path("data");
        Map<String, String> names = new HashMap<>();
        Map<String, String> groups = new HashMap<>();
        JsonNode roster = data.path("roster");
        for (String side : List.of("homeRoster", "awayRoster")) {
            roster.path(side).path("players").elements().forEachRemaining(p -> {
                names.put(p.path("id").asText(), p.path("name").asText());
                groups.put(p.path("id").asText(), eventId + side);
            });
        }
        JsonNode selections = data.path("selections");
        Map<String, OddsService.OddsLine> lines = new LinkedHashMap<>();
        List<OddsService.OddsLine> milestones = new ArrayList<>();
        for (JsonNode m : data.path("markets")) {
            int type = m.path("typeId").asInt();
            Stat stat = OVER_UNDER.get(type);
            boolean ou = stat != null;
            if (!ou) {
                stat = MILESTONE.get(type);
            }
            if (stat == null) {
                continue;
            }
            String player = names.get(m.path("playerId").asText());
            if (player == null) {
                Matcher bm = BRACKET.matcher(m.path("name").asText());
                player = bm.find() ? bm.group(1) : null;
            }
            if (player == null) {
                continue;
            }
            String group = groups.get(m.path("playerId").asText());
            if (ou) {
                Double over = null, under = null;
                double line = Double.NaN;
                for (JsonNode id : m.path("selectionIdList")) {
                    JsonNode s = selections.path(id.asText());
                    String nm = s.path("name").asText("").toLowerCase(Locale.ROOT);
                    if (!s.has("handicap") || !s.has("price")) {
                        continue;
                    }
                    line = s.get("handicap").asDouble();
                    if (nm.startsWith("over")) {
                        over = s.get("price").asDouble();
                    } else if (nm.startsWith("under")) {
                        under = s.get("price").asDouble();
                    }
                }
                if (!Double.isNaN(line) && (over != null || under != null)) {
                    lines.put(player + "|" + stat + "|" + line,
                            new OddsService.OddsLine("Stoiximan", date, player, stat, line, over, under, group));
                }
            } else {
                for (JsonNode id : m.path("selectionIdList")) {
                    JsonNode s = selections.path(id.asText());
                    Matcher pm = PLUS.matcher(s.path("shortName").asText(""));
                    if (pm.matches() && s.has("price")) {
                        double line = Integer.parseInt(pm.group(1)) - 0.5;
                        milestones.add(new OddsService.OddsLine("Stoiximan", date, player, stat, line,
                                s.get("price").asDouble(), null, group));
                    }
                }
            }
        }
        for (OddsService.OddsLine ml : milestones) {
            lines.putIfAbsent(ml.playerName() + "|" + ml.stat() + "|" + ml.line(), ml);
        }
        return new ArrayList<>(lines.values());
    }
}
