# Security policy

## Reporting a vulnerability

Please do not publish credentials, exploit steps, or unpatched vulnerability details in a public issue. Use GitHub's private vulnerability reporting for this repository if it is enabled. If it is unavailable, contact the maintainer privately through their GitHub profile and ask for a secure reporting channel.

Include the affected version or commit, impact, reproduction details, and any safe mitigation. Do not include real player data, tokens, recovery codes, or production database URLs.

There is no guaranteed response or patch timeline for this prototype.

## Security notes

- Never commit `.env`, `local.properties`, signing material, certificates, tokens, or database credentials.
- Internet play should use TLS (`https://` and `wss://`). Cleartext traffic is enabled only in Android debug builds for local LAN testing.
- The server's public Solo Survival leaderboard accepts bounded, authenticated client-reported times; it does not validate a run by replaying gameplay.
- In-memory rate limits and sessions are prototype safeguards, not a guarantee against distributed abuse.
