// Package postgres provides the PostgreSQL implementation of UserRepository
// and a no-op implementation used in demo mode.
package postgres

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"time"

	"github.com/artgrid/auth-service/internal/domain"
	"github.com/google/uuid"
	"github.com/jmoiron/sqlx"
	_ "github.com/lib/pq"
)

// MustConnect opens and pings a PostgreSQL connection. Panics on failure.
func MustConnect(url string) *sqlx.DB {
	db, err := sqlx.Connect("postgres", url)
	if err != nil {
		panic(fmt.Sprintf("postgres.connect: %v", err))
	}
	db.SetMaxOpenConns(10)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(30 * time.Minute)
	return db
}

// ── Real PostgreSQL implementation ────────────────────────────────────────────

// UserRepository persists and retrieves users in PostgreSQL.
type UserRepository struct {
	db  *sqlx.DB
	key []byte // 32-byte AES-256 key decoded from hex
}

// NewUserRepository constructs the repository with the AES-256 key.
func NewUserRepository(db *sqlx.DB, hexKey string) *UserRepository {
	key, err := hex.DecodeString(hexKey)
	if err != nil || len(key) != 32 {
		panic("PII_ENCRYPTION_KEY must be 64 hex chars (32 bytes)")
	}
	return &UserRepository{db: db, key: key}
}

// UpsertUser inserts a new user or returns the existing one for the given Google sub.
// Both google_sub and email are AES-256-GCM encrypted before storage.
func (r *UserRepository) UpsertUser(ctx context.Context, sub, email string) (*domain.User, error) {
	encSub, err := r.encrypt(sub)
	if err != nil {
		return nil, fmt.Errorf("encrypt_sub: %w", err)
	}
	encEmail, err := r.encrypt(email)
	if err != nil {
		return nil, fmt.Errorf("encrypt_email: %w", err)
	}

	const q = `
		INSERT INTO users (id, google_sub, email, created_at, updated_at)
		VALUES ($1, $2, $3, NOW(), NOW())
		ON CONFLICT (google_sub) WHERE deleted_at IS NULL
		DO UPDATE SET updated_at = NOW()
		RETURNING id, google_sub, email, created_at, updated_at, deleted_at`

	var row struct {
		ID        string     `db:"id"`
		GoogleSub string     `db:"google_sub"`
		Email     string     `db:"email"`
		CreatedAt time.Time  `db:"created_at"`
		UpdatedAt time.Time  `db:"updated_at"`
		DeletedAt *time.Time `db:"deleted_at"`
	}

	newID := uuid.New().String()
	if err := r.db.GetContext(ctx, &row, q, newID, encSub, encEmail); err != nil {
		return nil, fmt.Errorf("upsert_user.query: %w", err)
	}

	// Decrypt PII before returning to service layer.
	decEmail, err := r.decrypt(row.Email)
	if err != nil {
		return nil, fmt.Errorf("decrypt_email: %w", err)
	}

	return &domain.User{
		ID:        row.ID,
		GoogleSub: sub,     // return plaintext to caller
		Email:     decEmail,
		CreatedAt: row.CreatedAt,
		UpdatedAt: row.UpdatedAt,
		DeletedAt: row.DeletedAt,
	}, nil
}

// FindByGoogleSub looks up a user by their encrypted Google subject identifier.
func (r *UserRepository) FindByGoogleSub(ctx context.Context, sub string) (*domain.User, error) {
	encSub, err := r.encrypt(sub)
	if err != nil {
		return nil, fmt.Errorf("encrypt_sub: %w", err)
	}

	const q = `
		SELECT id, google_sub, email, created_at, updated_at, deleted_at
		FROM users WHERE google_sub = $1 AND deleted_at IS NULL`

	var row struct {
		ID        string     `db:"id"`
		GoogleSub string     `db:"google_sub"`
		Email     string     `db:"email"`
		CreatedAt time.Time  `db:"created_at"`
		UpdatedAt time.Time  `db:"updated_at"`
		DeletedAt *time.Time `db:"deleted_at"`
	}

	if err := r.db.GetContext(ctx, &row, q, encSub); err != nil {
		return nil, fmt.Errorf("find_by_google_sub: %w", err)
	}

	decEmail, err := r.decrypt(row.Email)
	if err != nil {
		return nil, fmt.Errorf("decrypt_email: %w", err)
	}

	return &domain.User{
		ID:        row.ID,
		GoogleSub: sub,
		Email:     decEmail,
		CreatedAt: row.CreatedAt,
		UpdatedAt: row.UpdatedAt,
		DeletedAt: row.DeletedAt,
	}, nil
}

// ── AES-256-GCM encryption helpers ───────────────────────────────────────────

// encrypt returns hex(nonce || ciphertext) for the given plaintext.
func (r *UserRepository) encrypt(plaintext string) (string, error) {
	block, err := aes.NewCipher(r.key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}
	ct := gcm.Seal(nonce, nonce, []byte(plaintext), nil)
	return hex.EncodeToString(ct), nil
}

// decrypt decodes hex(nonce || ciphertext) and returns the plaintext.
func (r *UserRepository) decrypt(cipherHex string) (string, error) {
	data, err := hex.DecodeString(cipherHex)
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(r.key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	ns := gcm.NonceSize()
	if len(data) < ns {
		return "", fmt.Errorf("ciphertext too short")
	}
	pt, err := gcm.Open(nil, data[:ns], data[ns:], nil)
	if err != nil {
		return "", err
	}
	return string(pt), nil
}

// ── No-op demo implementation ─────────────────────────────────────────────────

// NoopUserRepository is used in demo mode — no DB operations.
type NoopUserRepository struct{}

// NewNoopUserRepository returns the no-op implementation.
func NewNoopUserRepository() *NoopUserRepository { return &NoopUserRepository{} }

// UpsertUser in demo mode always returns a synthetic user without touching any DB.
func (r *NoopUserRepository) UpsertUser(_ context.Context, sub, email string) (*domain.User, error) {
	slog.Debug("noop_repository.upsert_user.skipped", "mode", "demo")
	return &domain.User{ID: "demo-user-id", GoogleSub: sub, Email: email, CreatedAt: time.Now(), UpdatedAt: time.Now()}, nil
}

// FindByGoogleSub in demo mode always returns a synthetic user.
func (r *NoopUserRepository) FindByGoogleSub(_ context.Context, _ string) (*domain.User, error) {
	return &domain.User{ID: "demo-user-id", CreatedAt: time.Now(), UpdatedAt: time.Now()}, nil
}
