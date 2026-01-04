#!/bin/bash
# =============================================================================
# Generate Self-Signed SSL Certificates for Testing
# =============================================================================
# This script generates a self-signed certificate for local development/testing.
# DO NOT use these certificates in production.
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Certificate parameters
DAYS=365
KEY_SIZE=2048
COUNTRY="US"
STATE="California"
CITY="Berkeley"
ORG="Zuul Consul Test"
CN="localhost"

# Output files
KEY_FILE="server.key"
CERT_FILE="server.crt"

echo "Generating self-signed SSL certificate..."

# Generate private key and certificate in one command
openssl req -x509 \
    -nodes \
    -days "$DAYS" \
    -newkey rsa:$KEY_SIZE \
    -keyout "$KEY_FILE" \
    -out "$CERT_FILE" \
    -subj "/C=$COUNTRY/ST=$STATE/L=$CITY/O=$ORG/CN=$CN" \
    -addext "subjectAltName=DNS:localhost,DNS:*.localhost,IP:127.0.0.1"

# Set appropriate permissions
chmod 644 "$CERT_FILE"
chmod 600 "$KEY_FILE"

echo ""
echo "Certificate generated successfully:"
echo "  Certificate: $SCRIPT_DIR/$CERT_FILE"
echo "  Private Key: $SCRIPT_DIR/$KEY_FILE"
echo ""
echo "Certificate details:"
openssl x509 -in "$CERT_FILE" -noout -subject -dates
