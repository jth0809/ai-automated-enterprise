# Tracker WP3.3 transport-economics source evidence

Access date: 2026-07-15

This record covers the immutable `transport-economics-v1` numeric corpus. It
stores reviewed facts, source locators, and fingerprints only. No source body,
quotation, HTML, PDF, image, attachment, or binary is stored in the repository.

## Measurement boundary

- The dependent variable is a lower-bound proxy: published launch price divided
  by the matching configuration's maximum LEO payload, expressed in constant
  2025 USD/kg.
- It is not provider internal cost, an average realized customer price, or a
  promise that every mission can use maximum payload.
- Only operational Falcon 9 and Falcon Heavy configurations are eligible.
  Starship targets and unflown configurations are excluded.
- Four audited observations produce three annual frontier years. This remains
  `PROVISIONAL` evidence; five frontier years are required for `ESTABLISHED`.

## Accepted published-price observations

| Year | Configuration | Reviewed numeric fact | Official locator | SHA-256 |
|---:|---|---|---|---|
| 2017 | Falcon 9 expendable | USD 62,000,000 / 22,800 kg | NASA NTRS `20170010337`, p. 2 Table 1 and p. 15 | `32fa35b2ef38772ab7cb717ea344c7d26f1fad17659dd1f27ff4d512e2f0da8c` |
| 2018 | Falcon 9 expendable | USD 62,000,000 / 22,800 kg | NASA NTRS `20180007067`, p. 1 abstract | `702ac2731b28b3b7628d232ffa336f8fc2563ae5c80dab04d98206f3a5d0a521` |
| 2019 | Falcon 9 expendable | USD 62,000,000 / 22,800 kg | NASA NTRS `20190027610`, p. 8 section VII.A | `97aae9c110e8cb7cbd8991d08a09a598b05c0143ac6753eddb17afa8ce0121c5` |
| 2019 | Falcon Heavy expendable | USD 90,000,000 / 63,800 kg | NASA NTRS `20190027610`, p. 8 section VII.A | `97aae9c110e8cb7cbd8991d08a09a598b05c0143ac6753eddb17afa8ce0121c5` |

Official source URLs:

- `https://ntrs.nasa.gov/api/citations/20170010337/downloads/20170010337.pdf?attachment=true`
- `https://ntrs.nasa.gov/api/citations/20180007067/downloads/20180007067.pdf?attachment=true`
- `https://ntrs.nasa.gov/api/citations/20190027610/downloads/20190027610.pdf?attachment=true`

The two 2019 configurations are both retained for audit. The deterministic
annual reducer selects Falcon Heavy because its reviewed real USD/kg value is
lower.

## Candidates not admitted

| Candidate | Decision | Reason |
|---|---|---|
| FAA 2017 Annual Compendium, Falcon 9 USD 61.2M / 22,800 kg | Omitted | Official PDF exceeded the existing 5 MiB fingerprint limit. |
| FAA 2018 Annual Compendium, Falcon 9 USD 62M / 22,800 kg | Omitted | Official PDF exceeded the existing 5 MiB fingerprint limit. |
| SpaceX `Capabilities & Services`, 2024 USD 69.75M / 22,000 kg | Omitted | The reviewed official URL returned HTTP 404 on access date. |
| NASA NTRS `20250005924`, 2024 USD 69.75M | Omitted | The report fingerprints successfully but its price table does not declare the matching maximum LEO payload. Combining unrelated source rows would violate `configurationMatch`. |

No secondary mirror was substituted for a failed official URL.

## CPI-U normalization

Official API URL:

`https://api.bls.gov/publicAPI/v2/timeseries/data/CUUR0000SA0?startyear=2016&endyear=2025`

Raw response SHA-256:
`8e249f33d0af05ffb4f556673001dee90a01d04c357752346cced9b8e43d9098`

The corpus uses arithmetic means of published monthly, not seasonally adjusted
CPI-U values (`CUUR0000SA0`):

| Year | Included periods | Annual value |
|---:|---|---:|
| 2017 | M01-M12 | 245.120 |
| 2018 | M01-M12 | 251.107 |
| 2019 | M01-M12 | 255.657 |
| 2025 | M01-M09, M11-M12 | 321.943 |

BLS marks 2025 M10 as unavailable because of the 2025 lapse in appropriations;
the 2025 value is therefore the mean of the eleven published months. This rule
is explicit rather than silently treating the missing month as zero.

## Falcon-family annual launch counts

The LL2 query for each UTC year uses:

```text
format=json
include_suborbital=false
ordering=net
net__gte=<year>-01-01T00:00:00Z
net__lt=<next-year>-01-01T00:00:00Z
rocket__configuration__full_name__icontains=Falcon
```

The reviewed set then keeps configuration names beginning `Falcon 9` or
`Falcon Heavy` and status abbreviations `Success`, `Failure`, or
`Partial Failure`. LL2 record
`3622669f-6e06-467a-86f3-47b56b1114c1` is explicitly excluded because its own
name says `Amos 6 (Failure before launch)` and the mission description identifies
a propellant-loading/static-fire pad loss. It is relevant engineering evidence,
but it is not an orbital launch for this cumulative production proxy.

For each year, records are sorted by UUID and canonicalized as UTF-8 lines:

```text
id|net-as-UTC-yyyy-MM-ddTHH:mm:ssZ|status.abbrev|rocket.configuration.full_name
```

Lines are joined with LF and SHA-256 hashed. The zero-record 2011 set therefore
uses the standard SHA-256 of empty input.

| Year | Count | Canonical evidence-set SHA-256 |
|---:|---:|---|
| 2010 | 2 | `ceccdcf5da1971c9d7f13f88546071e419ee0c54755af3f343a2fbbf037e634d` |
| 2011 | 0 | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| 2012 | 2 | `4ea463fa0df20b068af61e026e6ca5da5eb22332e0487c7ea078aaf79dffce3b` |
| 2013 | 3 | `289f84fb2a64dc4086b2ec3e76af351bf71ea3daffb66b32e4c72b9a45f7ff55` |
| 2014 | 6 | `117893ff6c94b689a3cf01278043ee4cfc9ab28b5166dc6b0d5a1d1ba153f718` |
| 2015 | 7 | `08d865e0051109deb20b6bf9ec9b9ff27fe5c8b6c41e966961a2eec21a41decf` |
| 2016 | 8 | `07ed629092ddb0f58952606dca0e3822b4ac21fd808a0d8ede61a97e348efce7` |
| 2017 | 18 | `33528a0fe6db0e2ac8119882dc5c54791c908bac11fec3b987a86e37a83f79c1` |
| 2018 | 21 | `f1f9b0c04caf06481d0685ec8a94abece2aadaff8235afdabf159b30fc588a65` |
| 2019 | 13 | `549471cf4f86d4dc545c9a718ad8465aab1f56f6707056f15d676da2439513fd` |
| 2020 | 25 | `d92138333a9052df69e02f734828a18f6bef340381a9817d7d32813f82390653` |
| 2021 | 31 | `f709890f55c4ea021d55dc4792aa0aecda01fa60a92e3118f3feb574faa973dd` |
| 2022 | 61 | `f828dcb9195bbc7bcba93171649d3c6f0a6ccce9639a34da98d11a19ab9aa741` |
| 2023 | 96 | `f78111ee09500b41c6f4ed4823c6e601e23742aad04b18ae34bc8c8991ba059c` |
| 2024 | 134 | `ea42a43880a2863acbfb62af3091f7f392fdaccbe585fb77b9ee91e32167eafd` |

The reviewed 2016 count is eight after excluding the prelaunch AMOS-6 record.
The FAA 2017 compendium's Falcon 9 rows also total eight when the seven Full
Thrust launches are combined with the Jason-3 Falcon 9 v1.1 launch. The FAA 2018
compendium reports 18 Falcon 9 launches for 2017, matching the LL2 count. The
2018 LL2 value remains governed by the same immutable completed-orbital rule;
the oversized FAA source was not admitted as corpus provenance.

## Fingerprint procedure

Official price and CPI URLs were read with
`scripts/backfill/Get-SourceFingerprint.ps1 -Uri <https-url>`. The helper enforces
HTTPS, same-host redirects, at most three redirects, and a 5 MiB response limit,
then returns metadata and a lowercase SHA-256 without preserving response
content. LL2 hashes use the reviewed canonical evidence-set method above because
the paginated API response is mutable in ordering and envelope metadata.
