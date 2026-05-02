// Package config reads and validates environment variables for the AI proxy.
package config

import (
	"fmt"
	"os"
	"strings"

	"github.com/joho/godotenv"
)

// Config is the typed configuration for artgrid-ai-proxy.
type Config struct {
	Port               string
	DemoMode           bool
	GeminiAPIKey       string
	ProxySharedSecret  string
	AISystemPrompt     string
	LogLevel           string
	// DailyMessageLimit is the per-device per-day SSE message cap.
	DailyMessageLimit  int
}

// Load reads .env (if present) and environment, validates required keys, returns Config.
func Load() *Config {
	_ = godotenv.Load()

	demoMode := strings.ToLower(getEnv("DEMO_MODE", "true")) == "true"

	return &Config{
		Port:              getEnv("PORT", "8082"),
		DemoMode:          demoMode,
		GeminiAPIKey:      mustGetEnv("GEMINI_API_KEY"),
		ProxySharedSecret: mustGetEnv("PROXY_SHARED_SECRET"),
		AISystemPrompt:    getEnv("AI_SYSTEM_PROMPT", "You are an expert artist assistant. Help with painting, colour theory, and composition."),
		LogLevel:          getEnv("LOG_LEVEL", "info"),
		DailyMessageLimit: 50,
	}
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
