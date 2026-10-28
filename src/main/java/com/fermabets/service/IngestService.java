package com.fermabets.service;

import com.fermabets.AppProperties;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the bundled Python ingestion scripts and exposes their live log to the admin UI. */
@Service
public class IngestService {
    public enum Source {
        NBA("NBA", "nba_ingest.py", List.of()),
        EUROLEAGUE("EL", "euroleague_ingest.py", List.of("--competition", "E")),
        EUROCUP("EC", "euroleague_ingest.py", List.of("--competition", "U"));

        final String league;
        final String script;
        final List<String> extraArgs;

        Source(String league, String script, List<String> extraArgs) {
            this.league = league;
            this.script = script;
            this.extraArgs = extraArgs;
        }
    }

    public record Status(boolean running, String source, List<String> log) {
    }

    public record Run(long id, String league, String startedAt, String finishedAt, String status, String detail) {
    }

    private static final int MAX_LOG = 400;

    private final AppProperties props;
    private final DataRepository repo;
    private final ModelService models;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final LinkedList<String> log = new LinkedList<>();
    private volatile String current = "";
    private volatile Process proc;

    /** Kills the running ingest process (and its children). Data already committed stays. */
    public boolean cancel() {
        Process p = proc;
        if (p == null || !p.isAlive()) {
            return false;
        }
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        append("Cancelled by user");
        return true;
    }

    public IngestService(AppProperties props, DataRepository repo, ModelService models) {
        this.props = props;
        this.repo = repo;
        this.models = models;
    }

    public synchronized Status status() {
        return new Status(running.get(), current, new ArrayList<>(log));
    }

    public List<Run> recentRuns() {
        return repo.jdbc().query("SELECT id, league, started_at, finished_at, status, detail FROM ingest_run"
                        + " ORDER BY id DESC LIMIT 10",
                (rs, i) -> new Run(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)));
    }

    public boolean start(Source source) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        current = source.name();
        synchronized (this) {
            log.clear();
        }
        executor.submit(() -> run(source));
        return true;
    }

    private void run(Source source) {
        long id = insertRun(source.league);
        String status = "OK";
        String detail = "";
        try {
            Path dir = Path.of(props.ingestDir()).toAbsolutePath();
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExe(dir));
            cmd.add(source.script);
            cmd.add("--db");
            cmd.add(Path.of(props.dbPath()).toAbsolutePath().toString());
            cmd.addAll(source.extraArgs);
            append("> " + String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
            pb.environment().put("PYTHONUNBUFFERED", "1");
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process proc = pb.start();
            this.proc = proc;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank() && !line.contains("it/s]") && !line.contains("s/it]")) {
                        append(line);
                    }
                }
            }
            int code = proc.waitFor();
            if (code != 0) {
                status = "FAILED";
                detail = "exit code " + code;
            }
        } catch (Exception e) {
            status = "FAILED";
            detail = e.getMessage();
            append("ERROR: " + e.getMessage());
        } finally {
            models.invalidate();
            repo.jdbc().update("UPDATE ingest_run SET finished_at=?, status=?, detail=? WHERE id=?",
                    Instant.now().toString(), status, detail, id);
            append("Finished: " + status);
            running.set(false);
        }
    }

    String pythonExe(Path ingestDir) {
        if (props.python() != null && !props.python().isBlank()) {
            return props.python();
        }
        Path win = ingestDir.resolve(".venv/Scripts/python.exe");
        Path nix = ingestDir.resolve(".venv/bin/python");
        if (Files.exists(win)) {
            return win.toString();
        }
        if (Files.exists(nix)) {
            return nix.toString();
        }
        return "python";
    }

    private long insertRun(String league) {
        repo.jdbc().update("INSERT INTO ingest_run(league, started_at, status) VALUES (?,?,'RUNNING')", league,
                Instant.now().toString());
        Long id = repo.jdbc().queryForObject("SELECT MAX(id) FROM ingest_run", Long.class);
        return id == null ? 0 : id;
    }

    private synchronized void append(String line) {
        log.add(line);
        while (log.size() > MAX_LOG) {
            log.removeFirst();
        }
    }

    public List<DataSummary> dataSummary() {
        return repo.jdbc().query(
                "SELECT league, COUNT(*), COUNT(DISTINCT game_id), COUNT(DISTINCT player_id), MIN(game_date),"
                        + " MAX(game_date) FROM player_game GROUP BY league ORDER BY league",
                (rs, i) -> new DataSummary(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6)));
    }

    public int officialsCount() {
        Integer n = repo.jdbc().queryForObject("SELECT COUNT(DISTINCT game_id) FROM game_official", Integer.class);
        return n == null ? 0 : n;
    }

    public record DataSummary(String league, int rows, int games, int players, String from, String to) {
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
