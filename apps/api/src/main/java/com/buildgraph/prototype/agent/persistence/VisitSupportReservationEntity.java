package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "visit_support_reservations")
public class VisitSupportReservationEntity extends PublicIdEntity {
    @Column(name = "as_ticket_id", nullable = false)
    private Long asTicketId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "preferred_date", nullable = false)
    private LocalDate preferredDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_slot", nullable = false)
    private VisitTimeSlot timeSlot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private VisitReservationStatus status;

    @Column(name = "address_snapshot")
    private String addressSnapshot;

    @Column(name = "technician_note")
    private String technicianNote;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected VisitSupportReservationEntity() {
    }
}
