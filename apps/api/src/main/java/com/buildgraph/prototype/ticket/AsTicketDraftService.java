package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AsTicketDraftService {
    private final JdbcTemplate jdbcTemplate;

    public AsTicketDraftService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> draft(String draftId, CurrentUserService.CurrentUser user) {
        return jdbcTemplate.queryForList("""
                        SELECT d.public_id::text AS draft_id,
                               lu.public_id::text AS log_upload_id,
                               d.title,
                               d.detail_description,
                               d.symptom_type,
                               d.symptom,
                               d.detected_at,
                               d.incident_window,
                               d.support_request_kind,
                               d.status,
                               d.created_at
                        FROM as_ticket_drafts d
                        JOIN agent_log_uploads lu ON lu.id = d.log_upload_id
                        WHERE d.public_id = ?::uuid
                          AND d.user_id = ?
                          AND d.status = 'DRAFT'
                          AND d.expires_at > now()
                        """, draftId, user.internalId())
                .stream()
                .findFirst()
                .map(this::draftMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 접수 초안을 찾을 수 없습니다."));
    }

    private Map<String, Object> draftMap(Map<String, Object> row) {
        return MockData.map(
                "draftId", DbValueMapper.string(row, "draft_id"),
                "logUploadId", DbValueMapper.string(row, "log_upload_id"),
                "title", DbValueMapper.string(row, "title"),
                "detailDescription", DbValueMapper.string(row, "detail_description"),
                "symptomType", DbValueMapper.string(row, "symptom_type"),
                "symptom", DbValueMapper.string(row, "symptom"),
                "detectedAt", DbValueMapper.timestamp(row, "detected_at"),
                "incidentWindow", DbValueMapper.json(row, "incident_window", Map.of()),
                "supportRequestKind", DbValueMapper.string(row, "support_request_kind"),
                "status", DbValueMapper.string(row, "status"),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }
}
