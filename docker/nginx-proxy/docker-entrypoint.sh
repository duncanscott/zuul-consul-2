#!/bin/sh
# =============================================================================
# Docker Entrypoint for Nginx Proxy
# =============================================================================
# This script checks for SSL certificates and generates them if missing,
# then starts nginx.
# =============================================================================

set -e

SSL_DIR="/etc/nginx/ssl"
CERT_FILE="$SSL_DIR/server.crt"
KEY_FILE="$SSL_DIR/server.key"

# Check if certificates exist
if [ ! -f "$CERT_FILE" ] || [ ! -f "$KEY_FILE" ]; then
    echo "SSL certificates not found. Generating self-signed certificates..."

    # Generate self-signed certificate
    openssl req -x509 \
        -nodes \
        -days 365 \
        -newkey rsa:2048 \
        -keyout "$KEY_FILE" \
        -out "$CERT_FILE" \
        -subj "/C=US/ST=California/L=Berkeley/O=Zuul Consul Test/CN=localhost" \
        -addext "subjectAltName=DNS:localhost,DNS:*.localhost,IP:127.0.0.1" \
        2>/dev/null

    chmod 644 "$CERT_FILE"
    chmod 600 "$KEY_FILE"

    echo "Self-signed certificates generated successfully."
else
    echo "SSL certificates found."
fi

# Execute the main command (nginx)
exec "$@"
