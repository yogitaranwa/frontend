// Package handler_test contains integration tests for the auth HTTP handler layer.
// Uses httptest — no real network, no real DB — demo mode only.
package handler_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/artgrid/auth-service/internal/api"
	"github.com/artgrid/auth-service/internal/config"
	"github.com/artgrid/auth-service/internal/repository/postgres"
	"github.com/artgrid/auth-service/internal/service"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// ── Test fixtures ─────────────────────────────────────────────────────────────

func setupRouter(t *testing.T) http.Handler {
	t.Helper()
	cfg := &config.Config{
		Port:      "8080",
		DemoMode:  true,
		JWTSecret: "integration-test-secret-32ch",
	}
	userRepo := postgres.NewNoopUserRepository()
	authSvc := service.NewAuthService(cfg, userRepo)
	return api.NewRouter(cfg, authSvc)
}

// ── GET /health ───────────────────────────────────────────────────────────────

func TestHealth_Returns200(t *testing.T) {
	router := setupRouter(t)
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rr := httptest.NewRecorder()

	router.ServeHTTP(rr, req)

	assert.Equal(t, http.StatusOK, rr.Code)
	var body map[string]string
	require.NoError(t, json.NewDecoder(rr.Body).Decode(&body))
	assert.Equal(t, "ok", body["status"])
}

// ── POST /api/v1/auth/google ──────────────────────────────────────────────────

func TestSignIn_DemoMode_AnyTokenAccepted(t *testing.T) {
	router := setupRouter(t)
	body := bytes.NewBufferString(`{"id_token":"any-value"}`)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/google", body)
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()

	router.ServeHTTP(rr, req)

	require.Equal(t, http.StatusOK, rr.Code)
	var resp map[string]interface{}
	require.NoError(t, json.NewDecoder(rr.Body).Decode(&resp))
	assert.NotEmpty(t, resp["access_token"])
	assert.Equal(t, "Bearer", resp["token_type"])
	assert.Equal(t, true, resp["is_demo"])
	assert.Nil(t, resp["user_id"])
	assert.Nil(t, resp["email"])
}

func TestSignIn_MalformedJSON_Returns400(t *testing.T) {
	router := setupRouter(t)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/google",
		bytes.NewBufferString(`not-json`))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()

	router.ServeHTTP(rr, req)

	assert.Equal(t, http.StatusBadRequest, rr.Code)
}

// ── POST /api/v1/auth/refresh ─────────────────────────────────────────────────

func TestRefresh_WithValidToken_Returns200(t *testing.T) {
	router := setupRouter(t)

	// First sign in to get a valid token.
	signInBody := bytes.NewBufferString(`{"id_token":"demo"}`)
	signInReq := httptest.NewRequest(http.MethodPost, "/api/v1/auth/google", signInBody)
	signInReq.Header.Set("Content-Type", "application/json")
	signInRR := httptest.NewRecorder()
	router.ServeHTTP(signInRR, signInReq)
	require.Equal(t, http.StatusOK, signInRR.Code)

	var signInResp map[string]interface{}
	require.NoError(t, json.NewDecoder(signInRR.Body).Decode(&signInResp))
	token := signInResp["access_token"].(string)

	// Now call /refresh with that token.
	refreshReq := httptest.NewRequest(http.MethodPost, "/api/v1/auth/refresh", nil)
	refreshReq.Header.Set("Authorization", "Bearer "+token)
	refreshRR := httptest.NewRecorder()
	router.ServeHTTP(refreshRR, refreshReq)

	require.Equal(t, http.StatusOK, refreshRR.Code)
	var refreshResp map[string]interface{}
	require.NoError(t, json.NewDecoder(refreshRR.Body).Decode(&refreshResp))
	assert.NotEmpty(t, refreshResp["access_token"])
}

func TestRefresh_NoToken_Returns401(t *testing.T) {
	router := setupRouter(t)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/refresh", nil)
	rr := httptest.NewRecorder()

	router.ServeHTTP(rr, req)

	assert.Equal(t, http.StatusUnauthorized, rr.Code)
}

func TestRefresh_TamperedToken_Returns401(t *testing.T) {
	router := setupRouter(t)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/refresh", nil)
	req.Header.Set("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.fake.signature")
	rr := httptest.NewRecorder()

	router.ServeHTTP(rr, req)

	assert.Equal(t, http.StatusUnauthorized, rr.Code)
}
