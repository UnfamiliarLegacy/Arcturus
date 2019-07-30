package com.eu.habbo.habbohotel.wired.highscores;

import com.eu.habbo.Emulator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WiredHighscoreManager {
    private final HashMap<Integer, List<WiredHighscoreDataEntry>> data = new HashMap<>();

    private final static DayOfWeek firstDayOfWeek = WeekFields.of(new Locale(System.getProperty("user.language"), System.getProperty("user.country"))).getFirstDayOfWeek();
    private final static DayOfWeek lastDayOfWeek = DayOfWeek.of(((firstDayOfWeek.getValue() + 5) % DayOfWeek.values().length) + 1);
    private final static ZoneId zoneId = ZoneId.systemDefault();

    public static ScheduledFuture midnightUpdater = null;

    public void load() {
        long millis = System.currentTimeMillis();

        this.data.clear();
        this.loadHighscoreData();

        if (midnightUpdater != null) {
            midnightUpdater.cancel(true);
        }
        midnightUpdater = Emulator.getThreading().run(new WiredHighscoreMidnightUpdater(), WiredHighscoreMidnightUpdater.getNextUpdaterRun());

        Emulator.getLogging().logStart("Highscore Manager -> Loaded! (" + (System.currentTimeMillis() - millis) + " MS, " + this.data.size() + " items)");
    }

    public void dispose() {
        if (midnightUpdater != null) {
            midnightUpdater.cancel(true);
        }

        this.data.clear();
    }

    private void loadHighscoreData() {
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM items_highscore_data")) {
            try (ResultSet set = statement.executeQuery()) {
                while (set.next()) {
                    WiredHighscoreDataEntry entry = new WiredHighscoreDataEntry(set);

                    if (!this.data.containsKey(entry.getItemId())) {
                        this.data.put(entry.getItemId(), new ArrayList<>());
                    }

                    this.data.get(entry.getItemId()).add(entry);
                }
            }
        } catch (SQLException e) {
            Emulator.getLogging().logSQLException(e);
        }
    }

    public void addHighscoreData(WiredHighscoreDataEntry entry) {
        if (!this.data.containsKey(entry.getItemId())) {
            this.data.put(entry.getItemId(), new ArrayList<>());
        }

        this.data.get(entry.getItemId()).add(entry);

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO `items_highscore_data` (`item_id`, `user_ids`, `score`, `is_win`, `timestamp`) VALUES (?, ?, ?, ?, ?)")) {
            statement.setInt(1, entry.getItemId());
            statement.setString(2, String.join(",", entry.getUserIds().stream().map(Object::toString).collect(Collectors.toList())));
            statement.setInt(3, entry.getScore());
            statement.setInt(4, entry.isWin() ? 1 : 0);
            statement.setInt(5, entry.getTimestamp());

            statement.execute();
        } catch (SQLException e) {
            Emulator.getLogging().logSQLException(e);
        }
    }

    public List<WiredHighscoreRow> getHighscoreRowsForItem(int itemId, WiredHighscoreClearType clearType, WiredHighscoreScoreType scoreType) {
        if (!this.data.containsKey(itemId)) return null;

        Stream<WiredHighscoreRow> highscores = this.data.get(itemId).stream()
                .filter(entry -> this.timeMatchesEntry(entry, clearType) && (scoreType != WiredHighscoreScoreType.MOSTWIN || entry.isWin()))
                .map(entry -> new WiredHighscoreRow(
                        entry.getUserIds().stream()
                                .map(id -> Emulator.getGameEnvironment().getHabboManager().getHabboInfo(id).getUsername())
                                .collect(Collectors.toList()),
                        entry.getScore()
                ));

        if (scoreType == WiredHighscoreScoreType.CLASSIC) {
            return highscores.sorted(WiredHighscoreRow::compareTo).collect(Collectors.toList());
        }

        if (scoreType == WiredHighscoreScoreType.PERTEAM) {
            return highscores
                    .collect(Collectors.groupingBy(h -> h.getUsers().hashCode()))
                    .entrySet()
                    .stream()
                    .map(e -> e.getValue().stream()
                            .sorted(WiredHighscoreRow::compareTo)
                            .collect(Collectors.toList())
                            .get(0)
                    )
                    .sorted(WiredHighscoreRow::compareTo)
                    .collect(Collectors.toList());
        }

        if (scoreType == WiredHighscoreScoreType.MOSTWIN) {
            return highscores
                    .collect(Collectors.groupingBy(h -> h.getUsers().hashCode()))
                    .entrySet()
                    .stream()
                    .map(e -> new WiredHighscoreRow(e.getValue().get(0).getUsers(), e.getValue().size()))
                    .sorted(WiredHighscoreRow::compareTo)
                    .collect(Collectors.toList());
        }

        return null;
    }

    private boolean timeMatchesEntry(WiredHighscoreDataEntry entry, WiredHighscoreClearType timeType) {
        switch (timeType) {
            case DAILY:
                return entry.getTimestamp() > this.getTodayStartTimestamp() && entry.getTimestamp() < this.getTodayEndTimestamp();
            case WEEKLY:
                return entry.getTimestamp() > this.getWeekStartTimestamp() && entry.getTimestamp() < this.getWeekEndTimestamp();
            case MONTHLY:
                return entry.getTimestamp() > this.getMonthStartTimestamp() && entry.getTimestamp() < this.getMonthEndTimestamp();
            case ALLTIME:
                return true;
        }

        return false;
    }

    private long getTodayStartTimestamp() {
        return LocalDateTime.now().with(LocalTime.MIDNIGHT).atZone(zoneId).toEpochSecond();
    }

    private long getTodayEndTimestamp() {
        return LocalDateTime.now().with(LocalTime.MIDNIGHT).plusDays(1).plusSeconds(-1).atZone(zoneId).toEpochSecond();
    }

    private long getWeekStartTimestamp() {
        return LocalDateTime.now().with(LocalTime.MIDNIGHT).with(TemporalAdjusters.previousOrSame(firstDayOfWeek)).atZone(zoneId).toEpochSecond();
    }

    private long getWeekEndTimestamp() {
        return LocalDateTime.now().with(LocalTime.MIDNIGHT).plusDays(1).plusSeconds(-1).with(TemporalAdjusters.nextOrSame(lastDayOfWeek)).atZone(zoneId).toEpochSecond();
    }

    private long getMonthStartTimestamp() {
        return LocalDateTime.now().with(LocalTime.MIDNIGHT).with(TemporalAdjusters.firstDayOfMonth()).atZone(zoneId).toEpochSecond();
    }

    private long getMonthEndTimestamp() {
        return LocalDateTime.now().with(LocalTime.MIDNIGHT).plusDays(1).plusSeconds(-1).with(TemporalAdjusters.lastDayOfMonth()).atZone(zoneId).toEpochSecond();
    }
}
