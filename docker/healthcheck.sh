#!/bin/sh
# Probes whichever connector is actually enabled.
#
# With PTT_HTTP_ENABLED=false there is no plaintext port to hit, and the TLS port serves a
# self-signed certificate that nothing in this image trusts — so certificate verification is
# skipped deliberately. This checks liveness over the loopback interface, not identity.
set -eu

if [ "${PTT_HTTP_ENABLED:-true}" != "false" ]; then
    exec wget -q -O /dev/null "http://127.0.0.1:${PTT_PORT:-8000}/health"
fi

exec wget -q -O /dev/null --no-check-certificate "https://127.0.0.1:${PTT_TLS_PORT:-8443}/health"
