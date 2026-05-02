// Package config reads and validates all environment variables on startup.
// Fails fast with a descriptive panic if any required variable is missing.
package config

import (
	"fmt"
	"os"
	"strings"

	"github.com/joho/godotenv"
)

// Config is the single typed config object for the auth service.
// All code reads from this struct — never from os.Getenv() directly.
type Config struct {
	Port             string
	DemoMode         bool
	JWTSecret        string
	GoogleClientID   string   // empty in demo mode
	PostgresURL      string   // empty in demo mode
	PIIEncryptionKey string   // 32-byte AES-256 key (hex-encoded, 64 chars)
	LogLevel         string
}

// Load reads .env (if present) then environment, validates, and returns Config.
// Panics with a descriptive message if any required variable is absent.
func Load() *Config {
	// Load .env file silently — in Docker the vars are already in the environment.
	_ = godotenv.Load()

	demoMode := strings.ToLower(getEnv("DEMO_MODE", "true")) == "true"

	cfg := &Config{
		Port:     getEnv("PORT", "8080"),
		DemoMode: demoMode,
		// JWT_SECRET is always required — used to sign tokens in both modes.
		JWTSecret: mustGetEnv("JWT_SECRET"),
		LogLevel:  getEnv("LOG_LEVEL", "info"),
	}

	if !demoMode {
		cfg.GoogleClientID   = mustGetEnv("GOOGLE_CLIENT_ID")
		cfg.PostgresURL      = mustGetEnv("POSTGRES_URL")
		cfg.PIIEncryptionKey = mustGetEnv("PII_ENCRYPTION_KEY")

		if len(cfg.PIIEncryptionKey) != 64 {
			panic("PII_ENCRYPTION_KEY must be 64 hex characters (32 bytes AES-256)")
		}
	}

	return cfg
}

// MaskedPostgresURL returns the Postgres URL with credentials redacted for logging.
func (c *Config) MaskedPostgresURL() string {
	if c.PostgresURL == "" {
		return ""
	}
	// Replace everything between :// and @ with [redacted].
	parts := strings.SplitN(c.PostgresURL, "@", 2)
	if len(parts) != 2 {
		return "[postgres_url]"
	}
	return fmt.Sprintf("postgres://[redacted]@%s", parts[1])
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func mustGetEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		panic(fmt.Sprintf("required environment variable %q is not set", key))
	}
	return v
}
