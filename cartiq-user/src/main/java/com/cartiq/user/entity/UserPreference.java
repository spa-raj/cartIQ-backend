package com.cartiq.user.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User user;

    @Column(precision = 10, scale = 2)
    private BigDecimal minPricePreference;

    @Column(precision = 10, scale = 2)
    private BigDecimal maxPricePreference;

    @ElementCollection
    @CollectionTable(name = "user_preferred_categories", joinColumns = @JoinColumn(name = "preference_id"))
    @Column(name = "category")
    @Builder.Default
    private List<String> preferredCategories = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "user_preferred_brands", joinColumns = @JoinColumn(name = "preference_id"))
    @Column(name = "brand")
    @Builder.Default
    private List<String> preferredBrands = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private Boolean emailNotifications = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean pushNotifications = true;

    @Column(nullable = false)
    @Builder.Default
    private String currency = "USD";

    @Column(nullable = false)
    @Builder.Default
    private String language = "en";

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
