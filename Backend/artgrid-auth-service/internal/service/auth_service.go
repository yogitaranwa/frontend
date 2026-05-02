// Package service contains all business logic for authentication.
// No HTTP imports. No direct DB access — delegates to UserRepository.
package service

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/artgrid/auth-service/internal/config"
	"github.com/artgrid/auth-service/internal/domain"
	apperr "github.com/artgrid/auth-service/internal/pkg/errors"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

const (
	jwtExpiry      = 30 * 24 * time.Hour   // 30 days — keeps demo sessions alive without forced re-login
	refreshWindow  = 5 * time.Minute
	demoUserID     = "demo-user-id"
	googleJWKsURL  = "https://www.googleapis.com/oauth2/v3/certs"
)

// UserRepository is defined here (at the point of use) per Go interface convention.
type UserRepository interface {
	UpsertUser(ctx context.Context, sub, encryptedEmail string) (*domain.User, error)
	FindByGoogleSub(ctx context.Context, sub string) (*domain.User, error)
}

// AuthService handles sign-in and token refresh for both demo and deployed modes.
type AuthService struct {
	cfg      *config.Config
	userRepo UserRepository
}

// NewAuthService constructs the service with its dependencies.
func NewAuthService(cfg *config.Config, userRepo UserRepository) *AuthService {
	return &AuthService{cfg: cfg, userRepo: userRepo}
}

// SignInWithGoogle validates the Google ID token (deployed mode) or issues a mock
// JWT immediately (demo mode), upserts the user, and returns an AuthResponse.
func (s *AuthService) SignInWithGoogle(ctx context.Context, idToken string) (*domain.AuthResponse, error) {
	if s.cfg.DemoMode {
		return s.issueDemoSession()
	}
	claims, err := s.verifyGoogleIDToken(ctx, idToken)
	if err != nil {
		return nil, err
	}
	return s.upsertAndIssue(ctx, claims)
}

// Refresh issues a new JWT when the existing one is non-expired.
// The JWT has already been validated by auth middleware before reaching this method.
func (s *AuthService) Refresh(ctx context.Context, existingUserID string, isDemo bool) (*domain.RefreshResponse, error) {
	if isDemo {
		token, err := s.signJWT(demoUserID, true)
		if err != nil {
			return nil, apperr.Wrapf(apperr.ErrInternal, err, "refresh.sign_demo_jwt")
		}
		return &domain.RefreshResponse{AccessToken: token, ExpiresIn: int(jwtExpiry.Seconds())}, nil
	}
	token, err := s.signJWT(existingUserID, false)
	if err != nil {
		return nil, apperr.Wrapf(apperr.ErrInternal, err, "refresh.sign_jwt")
	}
	return &domain.RefreshResponse{AccessToken: token, ExpiresIn: int(jwtExpiry.Seconds())}, nil
}

// ValidateToken parses and validates an ArtGrid JWT, returning the user ID and demo flag.
func (s *AuthService) ValidateToken(tokenStr string) (userID string, isDemo bool, err error) {
	token, parseErr := jwt.Parse(tokenStr, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
		return []byte(s.cfg.JWTSecret), nil
	}, jwt.WithValidMethods([]string{"HS256"}))

	if parseErr != nil || !token.Valid {
		return "", false, apperr.ErrTokenExpired
	}

	claims, ok := token.Claims.(jwt.MapClaims)
	if !ok {
		return "", false, apperr.ErrInvalidIDToken
	}

	sub, _ := claims["sub"].(string)
	demo, _ := claims["is_demo"].(bool)
	return sub, demo, nil
}

// ── Private helpers ───────────────────────────────────────────────────────────

func (s *AuthService) issueDemoSession() (*domain.AuthResponse, error) {
	token, err := s.signJWT(demoUserID, true)
	if err != nil {
		return nil, apperr.Wrapf(apperr.ErrInternal, err, "demo.sign_jwt")
	}
	return &domain.AuthResponse{
		AccessToken: token,
		TokenType:   "Bearer",
		ExpiresIn:   int(jwtExpiry.Seconds()),
		UserID:      nil,
		Email:       nil,
		IsDemo:      true,
	}, nil
}

func (s *AuthService) upsertAndIssue(ctx context.Context, claims *domain.GoogleClaims) (*domain.AuthResponse, error) {
	user, err := s.userRepo.UpsertUser(ctx, claims.Sub, claims.Email)
	if err != nil {
		return nil, apperr.Wrapf(apperr.ErrInternal, err, "upsert_user")
	}
	token, err := s.signJWT(user.ID, false)
	if err != nil {
		return nil, apperr.Wrapf(apperr.ErrInternal, err, "sign_jwt")
	}
	email := user.Email
	return &domain.AuthResponse{
		AccessToken: token,
		TokenType:   "Bearer",
		ExpiresIn:   int(jwtExpiry.Seconds()),
		UserID:      &user.ID,
		Email:       &email,
		IsDemo:      false,
	}, nil
}

func (s *AuthService) signJWT(userID string, isDemo bool) (string, error) {
	now := time.Now()
	claims := jwt.MapClaims{
		"sub":     userID,
		"is_demo": isDemo,
		"iat":     now.Unix(),
		"exp":     now.Add(jwtExpiry).Unix(),
		"jti":     uuid.New().String(),
	}
	t := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return t.SignedString([]byte(s.cfg.JWTSecret))
}

// verifyGoogleIDToken validates a Google ID token against Google's JWK set.
// Returns GoogleClaims on success.
func (s *AuthService) verifyGoogleIDToken(ctx context.Context, idToken string) (*domain.GoogleClaims, error) {
	// Build a verification request to Google's tokeninfo endpoint (simplest approach
	// for an academic project — avoids manual JWK parsing).
	url := fmt.Sprintf("https://oauth2.googleapis.com/tokeninfo?id_token=%s", idToken)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, apperr.Wrapf(apperr.ErrInternal, err, "google_tokeninfo.build_request")
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		slog.Warn("google_tokeninfo.unreachable", "error", err.Error())
		return nil, apperr.Wrapf(apperr.ErrGoogleJWKUnavail, err, "google_tokeninfo.do")
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)

	if resp.StatusCode != http.StatusOK {
		slog.Warn("google_tokeninfo.rejected", "status", resp.StatusCode)
		return nil, apperr.ErrInvalidIDToken
	}

	var payload struct {
		Sub      string `json:"sub"`
		Email    string `json:"email"`
		Audience string `json:"aud"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		return nil, apperr.Wrapf(apperr.ErrInternal, err, "google_tokeninfo.parse")
	}

	// Verify the token was issued for our client.
	if payload.Audience != s.cfg.GoogleClientID {
		slog.Warn("google_tokeninfo.wrong_audience", "aud", payload.Audience)
		return nil, apperr.ErrInvalidIDToken
	}

	return &domain.GoogleClaims{Sub: payload.Sub, Email: payload.Email}, nil
}
