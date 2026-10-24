# Shorting 40

Separate Android application and unique install ID `com.suhas.shorting40`, derived
from the v7.7.2/v7.7.3 Multify architecture. The original Multify repository is
not changed. Only the `:shorting` module compiles or ships; legacy `:app` is
kept in this repository as an unbuilt reference for component-by-component parity.

## Observation-first APK with opt-in live short order submission

- Exactly 40 deterministic bearish features, in families of 14/13/13.
- Full NSE symbol-master LTP discovery, 50 per batch, nominal 150-second loop.
- Shortlist: 20 continuing decliners + 8 fresh turns + 12 bounce candidates +
  8 rotating liquid/negative-session names, with fallbacks to 48.
- Current quotes and up to four calendar days of 15-minute Groww candles.
- Quality gates, repeated PRIMARY confirmation and theoretical sell/cover prices.
- Three visible tabs: Fixed, Forecast (uncalibrated research), Settings.
- Forecast ranking switch defaults OFF; Fixed scanning and execution controls remain independent.
- Manually entered Groww-whitelisted static IP and real outgoing-IP verification.
- ARM, Fixed auto-SHORT and Forecast auto-SHORT switches are fully user-controllable
  and OFF by default. When ARM and a mode's auto-SHORT switch are enabled,
  the app can submit real NSE CASH/MIS MARKET SELL orders on new PRIMARY signals.
  A filled short receives broker-hosted CASH/MIS OCO BUY target and BUY stop.
  The app stores entry references, checks fills, monitors the net position,
  tightens stops and requests intraday BUY-to-cover at 14:55 IST.
  No simulated positions or trades are created.
- Daily Groww API access token encrypted by Android Keystore.
- Groww request pacing within this app follows the documented category ceilings: market data 10 requests/second and 300/minute; orders 10/second and 250/minute; non-trading 20/second and 500/minute. No quota is reserved for or coordinated with another app.
- No Multify notification listener, LONG/CNC trade execution, GTT or order
  submission code is built in the new module.

**Important:** This release is installable and can submit LIVE brokerage orders
if the user enables ARM and auto-SHORT. It has NOT been validated against
the user's actual broker account, and it is **NOT** production-validated.
Android can be suspended, the token or IP can expire, Groww can reject or
partially fill orders, and OCO creation can be accepted despite an HTTP timeout.
Uncertain protection therefore raises an urgent notification for reconciliation.
The proposed 40-factor thresholds have not passed historical short-specific
backtests or broker integration tests. Forecast's adaptive learning is still
blocked pending real short outcomes. NSE holidays, corporate actions and live
short-eligibility checks need additional work. The service uses a user-initiated
foreground scan and Android may still terminate it under some device conditions.

## Live release acceptance gates

1. Cash MIS short eligibility checks and daily exchange holiday gates.
2. Margin sizing and portfolio-wide loss budget before **any** SELL.
3. Verified negative-position OCO: immediate BUY stop, BUY target, idempotent
   order references, partial fills and crash/restart reconciliation.
4. Monotonic upside protection: ShortTrail may tighten but never loosen stop.
5. 14:55 IST forced BUY-to-cover and emergency broker failure escalation.
6. Historical short-specific backtest including slippage, circuits and costs,
   several live-observation sessions without placing orders, and separate Forecast calibration.
7. Stable release signing key stored securely in GitHub Actions secrets (not repo).

## Build

GitHub Actions: **Shorting 40 Android — tests and installable observe APK**.
Run `gradle :shorting:testDebugUnitTest :shorting:assembleDebug` with Java 17,
Android SDK 35 and Gradle 8.9. APK artifact path:
`shorting/build/outputs/apk/debug/shorting-debug.apk`.
