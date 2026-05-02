// Package service contains the Gemini streaming proxy logic and rate limiting.
package service

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/artgrid/ai-proxy/internal/config"
	"github.com/artgrid/ai-proxy/internal/domain"
)

const (
	geminiStreamURL    = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:streamGenerateContent"
	maxMessageChars    = 2000
	maxImageB64Bytes   = 1 * 1024 * 1024 // 1MB encoded
)

// RateCounter holds per-device daily message usage.
// Resets at midnight UTC (FL-02: resets also on restart — acceptable for demo).
type RateCounter struct {
	mu       sync.Mutex
	counts   map[string]int    // deviceID → count for current UTC day
	day      string            // "YYYY-MM-DD" of current window
}

func newRateCounter() *RateCounter {
	return &RateCounter{
		counts: make(map[string]int),
		day:    utcDay(),
	}
}

func (rc *RateCounter) check(deviceID string, limit int) bool {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	today := utcDay()
	if today != rc.day {
		// New UTC day — reset all counters.
		rc.counts = make(map[string]int)
		rc.day = today
	}
	if rc.counts[deviceID] >= limit {
		return false
	}
	rc.counts[deviceID]++
	return true
}

func utcDay() string { return time.Now().UTC().Format("2006-01-02") }

// ChatService orchestrates rate-limit checking and Gemini streaming.
type ChatService struct {
	cfg     *config.Config
	counter *RateCounter
	client  *http.Client
}

// NewChatService constructs the service with its dependencies.
func NewChatService(cfg *config.Config) *ChatService {
	return &ChatService{
		cfg:     cfg,
		counter: newRateCounter(),
		client: &http.Client{
			Timeout: 90 * time.Second, // generous timeout for long AI responses
		},
	}
}

// StreamToWriter enforces the rate limit, calls Gemini, and pipes the SSE
// token stream to [w]. The caller must set SSE response headers before calling.
func (s *ChatService) StreamToWriter(
	ctx context.Context,
	deviceID string,
	req *domain.ChatRequest,
	w io.Writer,
	flush func(),
) error {
	if !s.counter.check(deviceID, s.cfg.DailyMessageLimit) {
		return errDailyLimit
	}

	if err := validateRequest(req); err != nil {
		return err
	}

	return s.callGeminiStream(ctx, req, w, flush)
}

// ── Exported sentinel errors ──────────────────────────────────────────────────

var (
	errDailyLimit  = &proxyError{code: 429, msg: "daily_limit_reached"}
	errBadRequest  = &proxyError{code: 400, msg: "invalid_request"}
	errGeminiDown  = &proxyError{code: 503, msg: "gemini_unavailable"}
)

// ProxyError exposes the HTTP code for the handler.
type ProxyError interface {
	error
	HTTPCode() int
	ErrorKey() string
}

type proxyError struct {
	code int
	msg  string
}

func (e *proxyError) Error() string  { return e.msg }
func (e *proxyError) HTTPCode() int  { return e.code }
func (e *proxyError) ErrorKey() string { return e.msg }

// ── Private helpers ───────────────────────────────────────────────────────────

func validateRequest(req *domain.ChatRequest) error {
	if strings.TrimSpace(req.Message) == "" {
		return errBadRequest
	}
	if len(req.Message) > maxMessageChars {
		return errBadRequest
	}
	if req.ImageB64 != nil && len(*req.ImageB64) > maxImageB64Bytes {
		return &proxyError{code: 413, msg: "image_b64_too_large"}
	}
	return nil
}

func (s *ChatService) callGeminiStream(
	ctx context.Context,
	req *domain.ChatRequest,
	w io.Writer,
	flush func(),
) error {
	body, err := buildGeminiRequest(s.cfg.AISystemPrompt, req)
	if err != nil {
		return fmt.Errorf("build_gemini_request: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(
		ctx, http.MethodPost,
		geminiStreamURL+"?key="+s.cfg.GeminiAPIKey+"&alt=sse",
		bytes.NewReader(body),
	)
	if err != nil {
		return errGeminiDown
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := s.client.Do(httpReq)
	if err != nil {
		slog.Warn("gemini.unreachable", "error", err.Error())
		return errGeminiDown
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		slog.Warn("gemini.error_status", "status", resp.StatusCode)
		return errGeminiDown
	}

	return pipeGeminiSSE(resp.Body, w, flush)
}

// buildGeminiRequest constructs the Gemini generateContent JSON body.
func buildGeminiRequest(systemPrompt string, req *domain.ChatRequest) ([]byte, error) {
	type part struct {
		Text       string `json:"text,omitempty"`
		InlineData *struct {
			MimeType string `json:"mimeType"`
			Data     string `json:"data"`
		} `json:"inlineData,omitempty"`
	}
	type content struct {
		Role  string `json:"role"`
		Parts []part `json:"parts"`
	}
	type payload struct {
		SystemInstruction content   `json:"systemInstruction"`
		Contents          []content `json:"contents"`
		GenerationConfig  struct {
			Temperature    float64 `json:"temperature"`
			MaxOutputTokens int    `json:"maxOutputTokens"`
		} `json:"generationConfig"`
	}

	userParts := []part{{Text: req.Message}}
	if req.ImageB64 != nil && *req.ImageB64 != "" {
		// Validate base64 before sending to Gemini.
		if _, err := base64.StdEncoding.DecodeString(*req.ImageB64); err != nil {
			return nil, errBadRequest
		}
		userParts = append(userParts, part{
			InlineData: &struct {
				MimeType string `json:"mimeType"`
				Data     string `json:"data"`
			}{MimeType: "image/jpeg", Data: *req.ImageB64},
		})
	}

	p := payload{
		SystemInstruction: content{Role: "user", Parts: []part{{Text: systemPrompt}}},
		Contents:          []content{{Role: "user", Parts: userParts}},
	}
	p.GenerationConfig.Temperature = 0.7
	p.GenerationConfig.MaxOutputTokens = 1024

	return json.Marshal(p)
}

// pipeGeminiSSE reads Gemini's SSE stream and re-emits it in ArtGrid's SSE format.
func pipeGeminiSSE(body io.Reader, w io.Writer, flush func()) error {
	scanner := bufio.NewScanner(body)
	totalTokens := 0

	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "data: ") {
			continue
		}
		jsonStr := strings.TrimPrefix(line, "data: ")

		// Parse Gemini's SSE payload to extract the text delta.
		var chunk struct {
			Candidates []struct {
				Content struct {
					Parts []struct {
						Text string `json:"text"`
					} `json:"parts"`
				} `json:"content"`
				FinishReason string `json:"finishReason"`
			} `json:"candidates"`
			UsageMetadata struct {
				TotalTokenCount int `json:"totalTokenCount"`
			} `json:"usageMetadata"`
		}
		if err := json.Unmarshal([]byte(jsonStr), &chunk); err != nil {
			continue // skip malformed chunks
		}

		if len(chunk.Candidates) == 0 {
			continue
		}

		c := chunk.Candidates[0]
		totalTokens = chunk.UsageMetadata.TotalTokenCount

		for _, p := range c.Content.Parts {
			if p.Text == "" {
				continue
			}
			// Escape any embedded JSON special chars in the delta text.
			deltaJSON, _ := json.Marshal(p.Text)
			_, _ = fmt.Fprintf(w, "data: {\"delta\":%s,\"done\":false}\n\n", deltaJSON)
			flush()
		}

		if c.FinishReason == "STOP" || c.FinishReason == "MAX_TOKENS" {
			_, _ = fmt.Fprintf(w,
				"data: {\"done\":true,\"finish_reason\":\"stop\",\"total_tokens\":%d}\n\n",
				totalTokens,
			)
			flush()
			return nil
		}
	}

	if err := scanner.Err(); err != nil {
		_, _ = fmt.Fprintf(w, "data: {\"error\":\"stream_read_error\",\"done\":true}\n\n")
		flush()
		return fmt.Errorf("scanner: %w", err)
	}

	// Stream ended without STOP — emit done anyway.
	_, _ = fmt.Fprintf(w, "data: {\"done\":true,\"finish_reason\":\"stop\",\"total_tokens\":%d}\n\n", totalTokens)
	flush()
	return nil
}
