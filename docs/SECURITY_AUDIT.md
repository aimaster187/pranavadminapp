# PRANAV Ecosystem Security Audit Checklist & Verification

## 1. Authentication & Zero-Login Architecture
- **No public Web Admin Dashboard**: Verified backend serves REST JSON endpoints (`/api/v1/admin/*`, `/api/v1/client/*`, `/api/v1/telegram/*`, `/healthz`) with zero HTML/Web UI.
- **No conventional login/registration**: Verified Android Admin App (`:app`) contains zero username/password fields, zero registration forms, zero Google Sign-In, zero Firebase Auth, and zero OTP flows.
- **Hardware-Backed Owner Keystore**: `OwnerKeystoreVault` creates an ECDSA P-256 key pair inside `AndroidKeyStore` (`secp256r1`, SHA-256). Private keys never leave the hardware keystore. Silent challenge-response (`/auth/challenge` -> `/auth/verify`) verifies ownership on every launch before loading any admin data.
- **Zero Embedded Permanent Secrets**: No permanent owner credentials, private keys, or BotFather tokens are embedded in `:app`, `:pranav-sdk`, `:pranav-hook`, or `PRANAV.dex`.

## 2. Telegram Multi-Bot & Single-Owner Authorization
- **AES-256-GCM Encryption at Rest**: BotFather tokens entered in the Admin App are verified via Telegram `getMe`, encrypted using `AES-256-GCM` (`crypto.ts`), and stored encrypted in `telegram_bots.encrypted_token`. Only masked tails (`***xxxx`) are returned to clients.
- **Strict Numeric `OWNER_TELEGRAM_ID` Gate**: Every webhook update checks `String(senderId) === String(bot.owner_telegram_id)` using constant-time verification, never usernames or display names.
- **Non-Owner Containment**: Any message or callback from a non-owner Telegram user receives only the configured static welcome message; no admin keyboards, data, or commands are exposed.
- **Replay & Rate Protection**: `update_id` deduplication prevents replay attacks; per-sender rate limits prevent webhook flooding; optional Telegram `X-Telegram-Bot-Api-Secret-Token` header verification uses `safeEqual`.

## 3. Client SDK, Hook & DEX Isolation
- **Endpoint Separation**: Client SDK requests are authenticated against `sdk_sessions` + per-installation ECDSA signatures and cannot reach `/api/v1/admin/*` routes.
- **Standalone `PRANAV.dex`**: Compiled strictly via `d8` into `pranav-dex/dist/PRANAV.dex` with SHA-256 manifest (`isApk: false`), strictly separate from `:pranav-hook` and `:pranav-sdk`.
- **No Dynamic Code Execution**: Dynamic Module Registry (`module_registry`) is strictly declarative JSON feature capabilities—never downloads or executes `.dex`/`.jar`/`.so` binaries at runtime.

## 4. Database & VPS Hardening
- **Integrity Protection**: SQLite runs in WAL mode with `foreign_keys = ON`; startup executes `PRAGMA integrity_check` and throws `CorruptedDatabaseError` instead of silently overwriting data.
- **Backups & Restore**: Hot backups use `VACUUM INTO` + SHA-256 verification; live restores verify integrity & checksum before transactional `ATTACH DATABASE` swap.
- **VPS & CI/CD**: Backend binds `127.0.0.1:8443` behind Nginx TLS 1.3 + UFW (`22/80/443`), unprivileged `pranav` systemd user, and GitHub Actions reads release keystores strictly from GitHub Secrets.
