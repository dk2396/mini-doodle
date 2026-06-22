package com.minidoodle.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Calendar is a pure domain concept. It exists only inside the service:
 * - one calendar per user (created lazily when the user is created)
 * - never exposed in DTOs or REST endpoints
 * - the unit-of-work for "no overlap" guarantees on slots
 */
@Entity
@Table(name = "calendars")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Calendar extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, length = 64)
    @Builder.Default
    private String timezone = "UTC";
}
