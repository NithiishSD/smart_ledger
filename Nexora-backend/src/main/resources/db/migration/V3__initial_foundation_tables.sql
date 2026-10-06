CREATE EXTENSION IF NOT EXISTS pgcrypto;

create table users(
    id UUID primary key default gen_random_uuid(),
    username varchar(200) not null unique,
    password_hash varchar(255) not null,
    display_name varchar(200) not null,
    is_active boolean not null default true,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz default now()
);

create table parties(
    id uuid primary key default gen_random_uuid(),
    name varchar(100) not null,
    address_line varchar(255) not null,
    area varchar(155) not null,
    city varchar(100) not null,
    district varchar(100) not null,
    state varchar(50) not null,
    pincode varchar(20) not null,
    note text,
    created_at timestamptz default now(),
    updated_at timestamptz not null default now()
);

CREATE TABLE party_roles (
    party_id UUID NOT NULL,
    role VARCHAR(30) NOT NULL,

    PRIMARY KEY (party_id, role),

    CONSTRAINT fk_party_roles_party
        FOREIGN KEY (party_id)
        REFERENCES parties(id)
);

CREATE TABLE party_phone_numbers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    party_id UUID NOT NULL,
    phone_number VARCHAR(30) NOT NULL,
    label VARCHAR(50) NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT fk_party_phone_numbers_party
        FOREIGN KEY (party_id)
        REFERENCES parties(id)
);