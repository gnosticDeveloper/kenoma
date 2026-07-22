CREATE TABLE IF NOT EXISTS users (
                                     id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                     name               varchar(255) NOT NULL,
                                     last_name          varchar(255) NOT NULL,
                                     email              varchar(255) NOT NULL UNIQUE,
                                     username           varchar(255) NOT NULL UNIQUE,
                                     password           varchar(255) NOT NULL,
                                     roles              text NOT NULL DEFAULT '{}',
                                     modification_lock  bool DEFAULT false,
                                     locked_at          timestamp,
                                     created_at         timestamp DEFAULT current_timestamp,
                                     modified_at        timestamp DEFAULT current_timestamp,
                                     stopped_at         timestamp DEFAULT null,
                                     is_ready           bool default false,
                                     locale             varchar(5) NOT NULL DEFAULT 'en'
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS locale varchar(5) NOT NULL DEFAULT 'en';

CREATE TABLE IF NOT EXISTS pending_verifications (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users(id),
    token_hash  varchar(64) NOT NULL,
    expires_at  timestamp NOT NULL,
    used        boolean NOT NULL DEFAULT false,
    type        varchar(32) NOT NULL DEFAULT 'ACCOUNT_SETUP'
);