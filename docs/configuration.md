# Configuration Guide

## Key Hierarchy

- **Android**: keys injected via manifest placeholders from `local.properties` or CI
- **Backend**: keys read from environment variables or mounted secrets

## Variables

| Variable | Required | Purpose |
|---|---|---|
| `AMAP_ANDROID_KEY` | Yes (release) | High德 Android SDK key |
| `AMAP_WEB_KEY` | Yes (release) | High德 Web API key |
| `CAIYUN_APP_KEY` | Yes (release) | 彩云天气 App Key |
| `CAIYUN_APP_SECRET` | Yes (release) | 彩云天气 App Secret |
| `POSTGRES_PASSWORD` | Yes | Database password |
| `REDIS_PASSWORD` | Yes | Redis password |

## Security

- No production key or secret is committed to the repository.
- Android release builds inject keys via CI secrets into manifest placeholders.
- Backend secrets are never logged, exposed in error messages, or included in trace/span attributes.
- URL, exception, and log filters must clear Caiyun App Key from URL paths and all three `x-cy-*` headers.
