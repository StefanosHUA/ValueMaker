# Fermabets Bet Assistant

Offline basketball player-prop value finder (NBA, Euroleague, EuroCup). Java 17 + Spring Boot, SQLite, with bundled
Python scripts (`nba_api`, `euroleague_api`) that fill the database from an admin button.

The previous Python/Google-Sheets version lives in `legacy/` as a reference.

## Setup (once)

```powershell
python -m venv ingest\.venv
ingest\.venv\Scripts\pip install nba_api euroleague-api pandas
mvn spring-boot:run
```

Open http://localhost:8080 .

## Daily flow

1. **Admin** -> *Update NBA* / *Update Euroleague* (incremental; the first Euroleague run is slow).
2. **Odds & context** -> paste the day's lines (`date,bookmaker,player,stat,line,over,under`), mark confirmed absences,
   optionally enter the referee crew.
3. **Value bets** -> top 3 picks with stake and explanation. "I placed this bet" records it.
4. **Bet tracker** -> after games are ingested, *Settle pending bets* (auto from box scores), watch ROI and the monthly target.
5. **Model** -> backtest per league/stat; the model must beat the naive baseline and be calibrated.

## How the model works

- Projection: ridge regression per stat on recent form, minutes, home/away, back-to-back, rest, opponent allowed
  stats, pace, missing rotation players (minutes/points) and the referee whistle factor learned from history.
- Distribution: Poisson / negative binomial around the projection -> P(over), P(under), P(push).
- Baseline: port of the legacy credibility (Buhlmann) hit-rate model; ensemble 70% regression / 30% baseline.
- Usage/role: projected usage %, minutes and a team usage budget (redistributed when rotation players are out);
  games with a different supporting cast count less. Shown on pick cards and the Matches tab.
- Game script: the Stoiximan handicap/total (de-vigged) gives the expected margin and total. Blowout risk (starters
  sit), close games (stars play more) and the total are features; without a market price our own team ratings are used.
- Value: bookmaker margin removed, model blended with the market (default 50%), EV, fractional Kelly (15% of bankroll max, no euro cap by default).
  Large model-vs-market gaps are flagged "check injury/news" because they usually mean missing information.

## Limits

- Only a Stoiximan adapter ships (unofficial, may break); implement `OddsService.OddsProvider` for more.
- Injuries/absences for upcoming games are entered manually (no free feed in the APIs).
- Referee effects need crews ingested (NBA: up to 1500 missing games per update run).
- Results are never guaranteed; the app is for disciplined staking and tracking, not a promise of profit.
