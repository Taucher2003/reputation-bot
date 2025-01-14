package de.chojo.repbot.data;

import de.chojo.repbot.analyzer.ThankType;
import de.chojo.repbot.data.wrapper.GuildRanking;
import de.chojo.repbot.data.wrapper.GuildReputationStats;
import de.chojo.repbot.data.wrapper.ReputationLogAccess;
import de.chojo.repbot.data.wrapper.ReputationLogEntry;
import de.chojo.repbot.data.wrapper.ReputationUser;
import de.chojo.repbot.util.LogNotify;
import de.chojo.sqlutil.base.QueryFactoryHolder;
import de.chojo.sqlutil.exceptions.ExceptionTransformer;
import de.chojo.sqlutil.wrapper.QueryBuilderConfig;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.slf4j.LoggerFactory.getLogger;

public class ReputationData extends QueryFactoryHolder {
    private static final Logger log = getLogger(ReputationData.class);

    public ReputationData(DataSource dataSource) {
        super(dataSource, QueryBuilderConfig.builder().withExceptionHandler(e ->
                        log.error(LogNotify.NOTIFY_ADMIN, ExceptionTransformer.prettyException("Query execution failed", e), e))
                .build());
    }

    /**
     * Log reputation for a user.
     *
     * @param guild      guild to log for
     * @param donor      donator of the reputation
     * @param receiver   receiver of the reputation
     * @param message    message to log
     * @param refMessage reference message if available
     * @param type       type of reputation
     * @return true if the statement was logged.
     */
    public boolean logReputation(Guild guild, @Nullable User donor, @NotNull User receiver, @NotNull Message message, @Nullable Message refMessage, ThankType type) {
        var success = builder()
                              .query("""
                                      INSERT INTO
                                      reputation_log(guild_id, donor_id, receiver_id, message_id, ref_message_id, channel_id, cause) VALUES(?,?,?,?,?,?,?)
                                          ON CONFLICT(guild_id, donor_id, receiver_id, message_id)
                                              DO NOTHING;
                                      """)
                              .paramsBuilder(b -> b.setLong(guild.getIdLong()).setLong(donor == null ? 0 : donor.getIdLong()).setLong(receiver.getIdLong())
                                      .setLong(message.getIdLong()).setLong(refMessage == null ? null : refMessage.getIdLong())
                                      .setLong(message.getChannel().getIdLong()).setString(type.name()))
                              .insert().executeSync() > 0;
        if (success) {
            log.debug("{} received one reputation from {} for message {}", receiver.getName(), donor != null ? donor.getName() : "unkown", message.getIdLong());
        }
        return success;
    }

    /**
     * Get the last time where the donor gave reputation to the receiver on this guild
     *
     * @param guild    guild
     * @param donor    donor
     * @param receiver receiver
     * @return last timestamp as instant
     */
    public Optional<Instant> getLastRated(Guild guild, User donor, User receiver) {
        return builder(Instant.class).
                query("""
                        SELECT
                            received
                        FROM
                            reputation_log
                        WHERE
                            guild_id = ?
                            AND donor_id = ?
                            AND receiver_id = ?
                        ORDER BY received DESC
                        LIMIT  1;
                        """)
                .paramsBuilder(stmt -> stmt.setLong(guild.getIdLong()).setLong(donor.getIdLong()).setLong(receiver.getIdLong()))
                .readRow(row -> row.getTimestamp("received").toInstant())
                .firstSync();
    }

    /**
     * Get the reputation user.
     *
     * @param guild guild
     * @param user  user
     * @return the reputation user
     */
    public Optional<ReputationUser> getReputation(Guild guild, User user) {
        return builder(ReputationUser.class)
                .query("""
                        SELECT rank, user_id, reputation FROM user_reputation WHERE guild_id = ? AND user_id = ?;
                        """)
                .paramsBuilder(stmt -> stmt.setLong(guild.getIdLong()).setLong(user.getIdLong()))
                .readRow(this::buildUser).firstSync();
    }

    private ReputationUser buildUser(ResultSet rs) throws SQLException {
        return new ReputationUser(
                rs.getLong("rank"),
                rs.getLong("user_id"),
                rs.getLong("reputation")
        );
    }

    /**
     * Removes all reputations associated with the message
     *
     * @param messageId message id
     */
    public void removeMessage(long messageId) {
        builder()
                .query("DELETE FROM reputation_log WHERE message_id = ?;")
                .paramsBuilder(stmt -> stmt.setLong(messageId))
                .update().execute();
    }

    /**
     * Get the time since the last reputation was given from the donator to the receiver on this guild in the requested
     * time unit.
     *
     * @param guild    guild
     * @param donor    donor
     * @param receiver receiver
     * @param unit     time unit
     * @return the time since the last vote in the requested time unit or  {@link Long#MAX_VALUE} if no entry was found.
     */
    public Long getLastRatedDuration(Guild guild, User donor, User receiver, ChronoUnit unit) {
        return getLastRated(guild, donor, receiver).map(i -> i.until(Instant.now(), unit)).orElse(Long.MAX_VALUE);
    }

    /**
     * Get the log entries for a message.
     *
     * @param message message
     * @return a log entry if found
     */
    public Optional<ReputationLogEntry> getLogEntry(Message message) {
        return builder(ReputationLogEntry.class)
                .query("""
                        SELECT
                            guild_id,
                            donor_id,
                            receiver_id,
                            message_id,
                            received,
                            ref_message_id,
                            channel_id,
                            cause
                        FROM
                            reputation_log
                        WHERE
                            message_id = ?;
                        """)
                .params(stmt -> stmt.setLong(1, message.getIdLong()))
                .readRow(this::buildLogEntry).firstSync();
    }

    /**
     * Get the last log entries for reputation received by the user.
     *
     * @param user  user
     * @param guild guild
     * @return sorted list of entries. the most recent first.
     */
    public ReputationLogAccess getUserReceivedLog(User user, Guild guild, int pageSize) {
        return new ReputationLogAccess(() -> getUserReceivedLogPages(user, guild, pageSize), page -> getUserReceivedLogPage(user, guild, pageSize, page));
    }

    public ReputationLogAccess getUserDonatedLog(User user, Guild guild, int pageSize) {
        return new ReputationLogAccess(() -> getUserDonatedLogPages(user, guild, pageSize), page -> getUserDonatedLogPage(user, guild, pageSize, page));
    }

    /**
     * Get the last log entries for reputation received by the user.
     *
     * @param user  user
     * @param guild guild
     * @return sorted list of entries. the most recent first.
     */
    private List<ReputationLogEntry> getUserReceivedLogPage(User user, Guild guild, int pageSize, int page) {
        return getLog(guild, "receiver_id", user.getIdLong(), pageSize, page);
    }

    /**
     * Get the last log entries for reputation donated by the user.
     *
     * @param user  user
     * @param guild guild
     * @return sorted list of entries. the most recent first.
     */
    private List<ReputationLogEntry> getUserDonatedLogPage(User user, Guild guild, int pageSize, int page) {
        return getLog(guild, "donor_id", user.getIdLong(), pageSize, page);
    }

    /**
     * Get the log entried for a message
     *
     * @param messageId message id
     * @param guild     guild
     * @return sorted list of entries. the most recent first.
     */
    public List<ReputationLogEntry> getMessageLog(long messageId, Guild guild, int count) {
        return getLog(guild, "message_id", messageId, count, 0);
    }

    public List<ReputationLogEntry> getLog(Guild guild, String column, long id, int pageSize, int page) {
        return builder(ReputationLogEntry.class)
                .query("""
                        SELECT
                            guild_id,
                            donor_id,
                            receiver_id,
                            message_id,
                            received,
                            ref_message_id,
                            channel_id,
                            cause
                        FROM
                            reputation_log
                        WHERE
                            %s = ?
                            AND guild_id = ?
                        ORDER BY received DESC
                        OFFSET ?
                        LIMIT ?;
                        """, column)
                .paramsBuilder(stmt -> stmt.setLong(id).setLong(guild.getIdLong()).setInt(page * pageSize).setInt(pageSize))
                .readRow(this::buildLogEntry)
                .allSync();
    }

    private int getUserDonatedLogPages(User user, Guild guild, int pageSize) {
        return getLogPages(guild, "donor_id", user.getIdLong(), pageSize);
    }

    private int getUserReceivedLogPages(User user, Guild guild, int pageSize) {
        return getLogPages(guild, "receiver_id", user.getIdLong(), pageSize);
    }

    private int getLogPages(Guild guild, String column, long id, int pageSize) {
        return builder(Integer.class)
                .query("""
                        SELECT
                            CEIL(COUNT(1)::NUMERIC / ?) AS count
                        FROM
                            reputation_log
                        WHERE guild_id = ?
                            AND %s = ?;
                        """, column)
                .paramsBuilder(stmt -> stmt.setInt(pageSize).setLong(guild.getIdLong()).setLong(id))
                .readRow(row -> row.getInt("count"))
                .firstSync()
                .orElse(1);
    }

    private ReputationLogEntry buildLogEntry(ResultSet rs) throws SQLException {
        return new ReputationLogEntry(
                rs.getLong("guild_id"),
                rs.getLong("channel_id"),
                rs.getLong("donor_id"),
                rs.getLong("receiver_id"),
                rs.getLong("message_id"),
                rs.getLong("ref_message_id"),
                ThankType.valueOf(rs.getString("cause")),
                rs.getTimestamp("received").toLocalDateTime());
    }

    /**
     * Remove reputation of a type from a message.
     *
     * @param user    user
     * @param message message
     * @param type    type
     * @return true if at least one entry was removed
     */
    public boolean removeReputation(long user, long message, ThankType type) {
        return builder()
                       .query("""
                               DELETE FROM
                                   reputation_log
                               WHERE
                                   message_id = ?
                                   AND donor_id = ?
                                   AND cause = ?;
                               """)
                       .paramsBuilder(stmt -> stmt.setLong(message).setLong(user).setString(type.name()))
                       .update()
                       .executeSync() > 0;
    }

    public Optional<GuildReputationStats> getGuildReputationStats(Guild guild) {
        return builder(GuildReputationStats.class)
                .query("SELECT total_reputation, week_reputation, today_reputation, top_channel FROM get_guild_stats(?)")
                .paramsBuilder(stmt -> stmt.setLong(guild.getIdLong()))
                .readRow(rs -> new GuildReputationStats(
                        rs.getInt("total_reputation"),
                        rs.getInt("week_reputation"),
                        rs.getInt("today_reputation"),
                        rs.getLong("top_channel")
                )).firstSync();
    }

    public Optional<ReputationLogEntry> getLatestReputation(Guild guild) {
        return builder(ReputationLogEntry.class)
                .query("""
                        SELECT
                            guild_id,
                            donor_id,
                            receiver_id,
                            message_id,
                            received,
                            ref_message_id,
                            channel_id,
                            cause
                        FROM reputation_log
                        WHERE guild_id = ?
                        ORDER BY received DESC
                        LIMIT 1;
                        """).paramsBuilder(stmt -> stmt.setLong(guild.getIdLong()))
                .readRow(this::buildLogEntry)
                .firstSync();
    }

    private int getRankingPageCount(Guild guild, int pageSize) {
        return getPageCount(guild, pageSize, "user_reputation");
    }

    private Integer getWeekRankingPageCount(Guild guild, int pageSize) {
        return getPageCount(guild, pageSize, "user_reputation_week");
    }

    private Integer getMonthRankingPageCount(Guild guild, int pageSize) {
        return getPageCount(guild, pageSize, "user_reputation_month");
    }

    private Integer getPageCount(Guild guild, int pageSize, String table) {
        return builder(Integer.class)
                .query("""
                        SELECT
                            CEIL(COUNT(1)::NUMERIC / ?) AS count
                        FROM
                            %s
                        WHERE guild_id = ?
                            AND reputation != 0;
                        """, table)
                .paramsBuilder(stmt -> stmt.setInt(pageSize).setLong(guild.getIdLong()))
                .readRow(row -> row.getInt("count"))
                .firstSync()
                .orElse(1);
    }

    /**
     * Get the ranking of the guild.
     *
     * @param guild    guild
     * @param pageSize the size of a page
     * @return a sorted list of reputation users
     */
    public GuildRanking getRanking(Guild guild, int pageSize) {
        return new GuildRanking(() -> getRankingPageCount(guild, pageSize), page -> getRankingPage(guild, pageSize, page));
    }

    /**
     * Get the weekly ranking of the guild.
     *
     * @param guild    guild
     * @param pageSize the size of a page
     * @return a sorted list of reputation users
     */
    public GuildRanking getWeekRanking(Guild guild, int pageSize) {
        return new GuildRanking(() -> getWeekRankingPageCount(guild, pageSize), page -> getWeekRankingPage(guild, pageSize, page));
    }

    /**
     * Get the monthly ranking of the guild.
     *
     * @param guild    guild
     * @param pageSize the size of a page
     * @return a sorted list of reputation users
     */
    public GuildRanking getMonthRanking(Guild guild, int pageSize) {
        return new GuildRanking(() -> getMonthRankingPageCount(guild, pageSize), page -> getMonthRankingPage(guild, pageSize, page));
    }

    /**
     * Get the ranking of the guild.
     *
     * @param guild    guild
     * @param pageSize the size of a page
     * @param page     the number of the page. zero based
     * @return a sorted list of reputation users
     */
    private List<ReputationUser> getRankingPage(Guild guild, int pageSize, int page) {
        return getRankingPage(guild, pageSize, page, "user_reputation");
    }

    private List<ReputationUser> getWeekRankingPage(Guild guild, int pageSize, int page) {
        return getRankingPage(guild, pageSize, page, "user_reputation_week");
    }

    private List<ReputationUser> getMonthRankingPage(Guild guild, int pageSize, int page) {
        return getRankingPage(guild, pageSize, page, "user_reputation_month");
    }

    private List<ReputationUser> getRankingPage(Guild guild, int pageSize, int page, String table) {
        return builder(ReputationUser.class)
                .query("""
                        SELECT
                            rank,
                            user_id,
                            reputation
                        FROM
                            %s
                        WHERE guild_id = ?
                            AND reputation != 0
                        ORDER BY reputation DESC
                        OFFSET ?
                        LIMIT ?;
                        """, table)
                .paramsBuilder(stmt -> stmt.setLong(guild.getIdLong()).setInt(page * pageSize).setInt(pageSize))
                .readRow(row -> new ReputationUser(row.getLong("rank"), row.getLong("user_id"), row.getLong("reputation")))
                .allSync();
    }
}
