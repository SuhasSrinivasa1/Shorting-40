# Shorting 40

Separate NSE intraday short-screening Android application with opt-in live Groww CASH/MIS short execution. See `SHORTING40_BUILD_PLAN.md` for functionality and limitations.

ARM, Fixed auto-SHORT, Forecast auto-SHORT and Forecast ranking switches default OFF. Controls remain user-accessible and are not locked. Enabling execution can submit real brokerage orders; the broker integration and strategy have not been validated against the user's account and are not production-validated.

The app applies Groww's documented per-category API ceilings locally. It does not reserve or coordinate quota with any other application.
