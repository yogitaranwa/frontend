// Package handler contains the HTTP handler for POST /api/v1/chat.
package handler

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/artgrid/ai-proxy/internal/config"
	"github.com/artgrid/ai-proxy/internal/domain"
	"github.com/artgrid/ai-proxy/internal/service"
	"github.com/go-chi/chi/v5/middleware"
)

// ChatHandler handles POST /api/v1/chat and GET /health.
type ChatHandler struct {
	cfg     *config.Config
	chatSvc *service.ChatService
}

// NewChatHandler constructs the handler.
func NewChatHandler(cfg *config.Config, chatSvc *service.ChatService) *ChatHandler {
	return &ChatHandler{cfg: cfg, chatSvc: chatSvc}
}

// Health handles GET /health.
func (h *ChatHandler) Health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok", "service": "artgrid-proxy"})
}

// Chat handles POST /api/v1/chat — validates device token, decodes body, streams SSE.
func (h *ChatHandler) Chat(w http.ResponseWriter, r *http.Request) {
	traceID := middleware.GetReqID(r.Context())

	// Step 1: validate the X-Device-Token HMAC.
	deviceID, err := h.validateDeviceToken(r.Header.Get("X-Device-Token"))
	if err != nil {
		writeError(w, http.StatusUnauthorized, "invalid_device_token", "Device authentication failed")
		return
	}

	// Step 2: decode and validate the request body.
	var req domain.ChatRequest
	if decErr := json.NewDecoder(r.Body).Decode(&req); decErr != nil {
		writeError(w, http.StatusBadRequest, "malformed_body", "Request body is not valid JSON")
		return
	}

	// Step 3: set SSE response headers before writing anything.
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no") // disable Nginx proxy buffering

	flusher, ok := w.(http.Flusher)
	if !ok {
		writeError(w, http.StatusInternalServerError, "sse_unsupported", "SSE not supported")
		return
	}

	// Step 4: stream from Gemini.
	streamErr := h.chatSvc.StreamToWriter(
		r.Context(),
		deviceID,
		&req,
		w,
		flusher.Flush,
	)

	if streamErr != nil {
		var proxyErr service.ProxyError
		if errors.As(streamErr, &proxyErr) {
			if proxyErr.HTTPCode() == 429 {
				// Rate limit hit before stream opened — can still write JSON error.
				writeError(w, 429, proxyErr.ErrorKey(),
					"Daily AI limit of 50 messages reached. Resets at midnight UTC.")
				return
			}
			// Midstream error — emit SSE error event.
			fmt.Fprintf(w, "data: {\"error\":\"%s\",\"done\":true}\n\n", proxyErr.ErrorKey())
			flusher.Flush()
		}
		slog.Warn("chat.stream_error", "trace_id", traceID, "error", streamErr.Error())
	}
}

// ── Device token validation ───────────────────────────────────────────────────

// validateDeviceToken verifies the X-Device-Token header.
// Expected format: <device_uuid>:<HMAC-SHA256(device_uuid|hour_timestamp, shared_secret)>
// The hour timestamp window is ±1 hour to tolerate clock skew.
func (h *ChatHandler) validateDeviceToken(header string) (string, error) {
	parts := strings.SplitN(header, ":", 2)
	if len(parts) != 2 {
		return "", fmt.Errorf("malformed_device_token")
	}
	deviceUUID := parts[0]
	receivedHMAC := parts[1]

	// Check current hour and adjacent hours to tolerate clock skew.
	now := time.Now().Unix() / 3600
	for _, hour := range []int64{now - 1, now, now + 1} {
		if h.computeHMAC(deviceUUID, hour) == receivedHMAC {
			return deviceUUID, nil
		}
	}
	return "", fmt.Errorf("hmac_mismatch")
}

// computeHMAC computes HMAC-SHA256 of "<deviceUUID>|<hour>" with the shared secret.
func (h *ChatHandler) computeHMAC(deviceUUID string, hour int64) string {
	payload := fmt.Sprintf("%s|%s", deviceUUID, strconv.FormatInt(hour, 10))
	mac := hmac.New(sha256.New, []byte(h.cfg.ProxySharedSecret))
	mac.Write([]byte(payload))
	return hex.EncodeToString(mac.Sum(nil))
}

// ── Helpers ───────────────────────────────────────────────────────────────────

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, code int, errKey, msg string) {
	writeJSON(w, code, map[string]string{"error": errKey, "message": msg})
}
