// Package service_test contains unit tests for ChatService.
package service_test

import (
	"context"
	"fmt"
	"io"
	"strings"
	"testing"

	"github.com/artgrid/ai-proxy/internal/config"
	"github.com/artgrid/ai-proxy/internal/domain"
	"github.com/artgrid/ai-proxy/internal/service"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func testConfig() *config.Config {
	return &config.Config{
		DemoMode:          true,
		GeminiAPIKey:      "test-key",
		ProxySharedSecret: "test-secret",
		DailyMessageLimit: 50,
		AISystemPrompt:    "You are an artist assistant.",
	}
}

// ── Rate counter ──────────────────────────────────────────────────────────────

func TestRateLimit_AllowsUpToLimit(t *testing.T) {
	tests := []struct {
		limit    int
		messages int
		wantOK   bool
	}{
		{limit: 3, messages: 3, wantOK: true},
		{limit: 3, messages: 4, wantOK: false},
		{limit: 1, messages: 1, wantOK: true},
	}

	for _, tc := range tests {
		t.Run(fmt.Sprintf("limit=%d/messages=%d", tc.limit, tc.messages), func(t *testing.T) {
			cfg := testConfig()
			cfg.DailyMessageLimit = tc.limit
			svc := service.NewChatService(cfg)

			var lastOK bool
			for i := 0; i < tc.messages; i++ {
				var buf strings.Builder
				err := svc.StreamToWriter(
					context.Background(),
					"device-test-"+t.Name(),
					&domain.ChatRequest{Message: "hello", SessionID: "s1"},
					&buf,
					func() {},
				)
				// Real Gemini call will fail (test key), but rate counter increments first.
				// We only care about the LAST call's rate-limit result.
				lastOK = (err == nil || !strings.Contains(err.Error(), "daily_limit"))
			}
			assert.Equal(t, tc.wantOK, lastOK)
		})
	}
}

// ── Request validation ────────────────────────────────────────────────────────

func TestValidation_EmptyMessageRejected(t *testing.T) {
	svc := service.NewChatService(testConfig())
	err := svc.StreamToWriter(
		context.Background(), "d1",
		&domain.ChatRequest{Message: "   ", SessionID: "s1"},
		io.Discard, func() {},
	)
	require.Error(t, err)
	var proxyErr service.ProxyError
	require.ErrorAs(t, err, &proxyErr)
	assert.Equal(t, 400, proxyErr.HTTPCode())
}

func TestValidation_ImageTooLarge(t *testing.T) {
	svc := service.NewChatService(testConfig())
	bigImg := strings.Repeat("A", 1*1024*1024+1) // > 1MB
	err := svc.StreamToWriter(
		context.Background(), "d1",
		&domain.ChatRequest{Message: "hi", ImageB64: &bigImg, SessionID: "s1"},
		io.Discard, func() {},
	)
	require.Error(t, err)
	var proxyErr service.ProxyError
	require.ErrorAs(t, err, &proxyErr)
	assert.Equal(t, 413, proxyErr.HTTPCode())
}
