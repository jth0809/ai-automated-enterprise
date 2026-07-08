# CI and DevSecOps Rules

Optimize CI pipelines for maximum speed and reliability.
Fail fast on security vulnerabilities using automated SAST and container scans.
Deliver immutable signed artifacts ready for deployment.
Eliminate manual approval bottlenecks by relying on robust test coverage.

## Web Server Security Rules
All web servers (e.g., Nginx, Apache) must explicitly include standard security headers in their configuration to protect against common web vulnerabilities (XSS, clickjacking, MIME-sniffing, etc.). Minimum required headers:
- `Strict-Transport-Security` (HSTS)
- `X-Frame-Options`
- `X-Content-Type-Options`
- `Content-Security-Policy` (CSP)
- `Referrer-Policy`
