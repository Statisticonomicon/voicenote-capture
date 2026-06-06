# Security policy

VoiceNote Capture is a personal, local-first, self-hosted hobby project shared as
source. It is provided "as is" with no warranty (see `LICENSE.md` and the README
disclaimer). There is no supported release, no security SLA, and no guarantee that
reported issues will be addressed.

## Design assumptions you must understand before using it

- **Cleartext over Tailscale is intentional.** The app permits cleartext HTTP only
  to a single Tailscale host, declared in `network_security_config.xml`. This is
  safe *only* because the Tailscale tunnel encrypts the transport. All other hosts
  remain HTTPS-only. If you point the endpoint at a non-Tailscale host, a public
  network, or any untrusted path, this assumption fails and the risk is yours.
- **Self-hosting is your responsibility.** Securing your Whisper server, your
  Tailscale network, your device, and your Obsidian vault is entirely up to you.
- **BYOK keys are yours to protect.** If you use the optional OpenAI provider, your
  API key is stored in the app's settings on your device. Treat it as a secret.
  That provider path is untested — verify it before relying on it.

## Reporting

If you find a security issue you wish to report, you may open an issue or contact
the author through the repository. Understand that this is a hobby project: there
is no commitment to triage, fix, or respond within any timeframe. Do not use this
software in any setting where an unpatched vulnerability would cause real harm.

## No warranty

Nothing in this policy creates any warranty, guarantee, or liability. Use entirely
at your own risk.
