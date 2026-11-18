# PRANAV Owner First-Run Deployment & Device Enrollment Checklist

Follow these exact steps to deploy PRANAV to production and enroll your owner Android phone:

## Step 1: Configure GitHub Secrets (CI/CD)
In your GitHub repository **Settings -> Secrets and variables -> Actions**, set:
- `ANDROID_KEYSTORE_BASE64`: Base64-encoded release `.jks` keystore for signing the Android Admin APK.
- `ANDROID_STORE_PASSWORD`: Release keystore store password.
- `ANDROID_KEY_PASSWORD`: Release keystore key password.

## Step 2: Provision Production VPS
1. SSH into your Ubuntu/Debian VPS as `root`.
2. Point DNS `A`/`AAAA` record for your domain (e.g. `api.yourdomain.com`) to the VPS IP.
3. Run the automated setup script:
   ```bash
   sudo PRANAV_DOMAIN=api.yourdomain.com PRANAV_OWNER_TELEGRAM_ID=<YOUR_NUMERIC_ID> bash deploy/vps-setup.sh
   sudo certbot --nginx -d api.yourdomain.com
   sudo bash deploy/verify-vps.sh https://api.yourdomain.com
   ```
4. Verify `/etc/pranav/backend.env` contains a strong 32-byte `PRANAV_MASTER_ENCRYPTION_KEY` and your exact numeric `PRANAV_OWNER_TELEGRAM_ID`.

## Step 3: Issue One-Time Bootstrap Enrollment Token
On the VPS (as `pranav` or `root`), generate a single-use 15-minute enrollment token or check startup logs if bootstrap auto-issued one:
```bash
sudo journalctl -u pranav-backend -n 50 --no-pager
```
Alternatively, mint a bootstrap token via SQLite/backend CLI before enrolling your phone.

## Step 4: Enroll Your Owner Phone (Zero-Login Launch)
1. Install the signed **PRANAV Android Admin APK** on your owner phone.
2. On first launch, the app shows the **Cryptographic Shield** (no login/password/Google Sign-In).
3. Enter your **PRANAV Backend HTTPS URL** (`https://api.yourdomain.com`), paste the single-use **Bootstrap Token**, set your device label, and tap **Enroll Owner Device**.
4. Your phone generates a hardware-backed Android Keystore ECDSA P-256 keypair and registers its public key. Every future launch opens straight to the 24-module Super Admin Dashboard via silent challenge-response verification.

## Step 5: Add Your First Telegram Bot (RANGIN) via the Admin App
1. Open **Module 14 (Telegram Bot Manager)** in the PRANAV Android Admin App.
2. Create a bot with **@BotFather** on Telegram and copy its token.
3. Enter Bot Alias (e.g. `RANGIN`), paste the **BotFather Token**, enter your **Owner Telegram Numeric ID**, optionally set your HTTPS Webhook URL, customize the non-owner welcome message, and tap **Verify, Encrypt & Start Bot**.
4. Send `/start` to your bot from your Owner Telegram account to open the synchronized 24-module inline admin console. Any other Telegram user receives only the welcome message.

## Step 6: Distribute Separate SDK / Hook / `PRANAV.dex` Deliverables
- **PRANAV SDK**: `pranav-sdk-release.aar` (see `docs/PRANAV_SDK_GUIDE.md`)
- **PRANAV Hook**: `pranav-hook-release.aar` (see `docs/PRANAV_HOOK_GUIDE.md`)
- **Standalone `PRANAV.dex`**: `pranav-dex/dist/PRANAV.dex` + `PRANAV.dex.sha256` (see `docs/MT_MANAGER_DEX_GUIDE.md`)
