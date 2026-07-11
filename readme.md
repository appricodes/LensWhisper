# Lense Whisper - Secure PHP Backend (Login & AI Processing)

This backend handles Google Sign-In verification and securely proxies AI requests to x.AI for the Android app "LensWhisper".

## Database Schema

```sql
CREATE DATABASE IF NOT EXISTS lense CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE lense;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    google_id VARCHAR(128) NOT NULL,
    verified TINYINT(1) NOT NULL DEFAULT 0,
    free_credits INT NOT NULL DEFAULT 0,
    purchased_credits INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_email (email),
    INDEX idx_google_id (google_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS purchase_log (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    prompt_id VARCHAR(50) NOT NULL,
    cost_free INT NOT NULL DEFAULT 0,
    cost_purchased INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_email (email)
) ENGINE=InnoDB;