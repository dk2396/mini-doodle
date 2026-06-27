package com.minidoodle.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "meetings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Meeting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @OneToMany(mappedBy = "meeting", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<Slot> slots = new HashSet<>();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizer_id", nullable = false)
    private User organizer;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @OneToMany(mappedBy = "meeting", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private Set<MeetingParticipant> participants = new HashSet<>();


    public void addSlot(Slot slot) {
        slot.setMeeting(this);
        slots.add(slot);
    }

    public void addParticipant(User user, MeetingParticipant.ResponseStatus status) {
        MeetingParticipant p = MeetingParticipant.builder()
                .meeting(this)
                .user(user)
                .responseStatus(status)
                .build();
        participants.add(p);
    }
}
