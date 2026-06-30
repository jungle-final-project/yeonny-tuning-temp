package com.buildgraph.prototype.price;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.NaverShoppingOfferService;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceJobWorker {
    private final JdbcTemplate jdbcTemplate;
    private final NaverShoppingOfferService naverShoppingOfferService;
    private final DanawaBackupCollector danawaBackupCollector;
    private final PriceAlertEvaluator priceAlertEvaluator;

    public PriceJobWorker(
            JdbcTemplate jdbcTemplate,
            NaverShoppingOfferService naverShoppingOfferService,
            DanawaBackupCollector danawaBackupCollector,
            PriceAlertEvaluator priceAlertEvaluator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.naverShoppingOfferService = naverShoppingOfferService;
        this.danawaBackupCollector = danawaBackupCollector;
        this.priceAlertEvaluator = priceAlertEvaluator;
    }

    /** Runs one queued price job through refresh, fallback, alert evaluation, and final status. */
    public void runJob(long jobId) {
        if (!markRunning(jobId)) {
            return;
        }
        try {
            Map<String, Object> refreshResult = naverShoppingOfferService.refreshDailyOffers();
            Map<String, Object> backupResult = needsBackup(refreshResult)
                    ? danawaBackupCollector.collectBackupPrices()
                    : MockData.map("source", "DANAWA_BACKUP", "attempted", 0, "updated", 0, "skipped", 0, "failed", 0);
            Map<String, Object> alertResult = priceAlertEvaluator.evaluateActiveAlerts();
            String summary = summary(refreshResult, backupResult, alertResult);
            finish(jobId, "SUCCEEDED", summary);
        } catch (RuntimeException error) {
            finish(jobId, "FAILED", safeReason(error));
        }
    }

    /** Moves a queued job to running exactly once. */
    @Transactional
    protected boolean markRunning(long jobId) {
        return jdbcTemplate.update("""
                UPDATE price_jobs
                SET status = 'RUNNING',
                    started_at = now()
                WHERE id = ?
                  AND status = 'QUEUED'
                  AND deleted_at IS NULL
                """, jobId) == 1;
    }

    /** Stores the final job state and optional processing summary. */
    @Transactional
    protected void finish(long jobId, String status, String summary) {
        jdbcTemplate.update("""
                UPDATE price_jobs
                SET status = ?,
                    finished_at = now(),
                    error_summary = ?
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND deleted_at IS NULL
                """, status, limited(summary), jobId);
    }

    /** Creates a compact summary for admin job history. */
    private static String summary(Map<String, Object> refreshResult, Map<String, Object> backupResult, Map<String, Object> alertResult) {
        boolean configured = Boolean.TRUE.equals(refreshResult.get("configured"));
        String prefix = configured ? "NAVER refresh completed" : "NAVER refresh skipped; saved prices used";
        return prefix + ": " + MockData.map(
                "attempted", refreshResult.get("attempted"),
                "updated", refreshResult.get("updated"),
                "skipped", refreshResult.get("skipped"),
                "failed", refreshResult.get("failed"),
                "danawaBackupUpdated", backupResult.get("updated"),
                "danawaBackupFailed", backupResult.get("failed"),
                "alertsTriggered", alertResult.get("triggered"),
                "alertSendFailed", alertResult.get("failed"),
                "message", DbValueMapper.string(refreshResult, "message")
        );
    }

    /** Decides when the price job must fall back to the Danawa backup source. */
    private static boolean needsBackup(Map<String, Object> refreshResult) {
        return !Boolean.TRUE.equals(refreshResult.get("configured"))
                || number(refreshResult.get("updated")) == 0
                || number(refreshResult.get("failed")) > 0;
    }

    /** Reads integer counters from result maps. */
    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** Converts an exception into a short job error summary. */
    private static String safeReason(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /** Truncates error summaries to avoid noisy admin rows. */
    private static String limited(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }
}
