package com.nexora.shared.domain;// ths folder pavcage is for shared codes s every class which is extended or is implements ar ehere
import jakarta.persistence.*;          // jakarta, never javax
import jakarta.persistence.Id;

import org.springframework.data.annotation.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;
//one base class that every entity extends, so every row automatically gets an id, created/updated timestamps, a creator and a version number.

@MappedSuperclass 
@EntityListeners (AuditingEntityListener.class)
public abstract class AuditableEntity {
    @Id
    @GeneratedValue(strategy=GenerationType.UUID)
    private UUID id;
    @CreatedDate 
    @Column(name="created_at",nullable=false,updatable=false)
    private Instant createdAt;

    @LastModifiedBy
    @Column(name="updated_at",nullable=false)
    private Instant updatedAt;

    @CreatedBy 
    @Column(name="created_by",nullable=true,updatable=false)
    private UUID createdBy;

    @jakarta.persistence.Version
    @Column(nullable=false)
    private long version;

    public UUID getId(){
        return this.id;
    }
    public Instant getCreatedAt(){
        return this.createdAt;
    }
    public Instant getUpdatedAt(){
        return this.updatedAt;
    }
    public UUID getCreatedBy() { return this.createdBy; }
public long getVersion() { return version; }
}
