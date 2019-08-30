#!/usr/bin/env bash

set -e

if [[ $# -ne 2 ]]; then
    echo usage: $0 '<proxy-name> <forward-target>'
    exit 1
fi

SERVER=$1
FORWARD=$2

# Authority.crt & authority.key is pre-generated with hash code of sha256/n6Ovey/sJws9vKJpESmWyQf9Oocak9J51mmPKGm4S0E=
# About this file:
#   CN:        TEST CA
#   Algorithm: ECDSA-SHA256
#   Validity:
#     Not Before: Mar 7 14:38:20 2019 GMT
#     Not After:  Mar 7 14:38:20 2029 GMT
#
# To generate key: (not needed for cert renewal)
# openssl ecparam -genkey -name prime256v1 -out authority.key
#
# To regenerate:
# openssl req -new -sha256 -key authority.key -out authority.csr -subj "/CN=TEST CA/"
# openssl x509 -trustout -signkey authority.key -days 3652 -req -in authority.csr -out authority.crt
#
# To check pin:
# openssl ec -in authority.key -outform der -pubout | openssl dgst -sha256 -binary | openssl enc -base64
AUTHORITY=authority
TARGET=build/proxy

# generate secrets
cd $(dirname $0)
mkdir -p ./build
openssl ecparam -genkey -name prime256v1 -out ${TARGET}.key
openssl req -new -sha256 -key ${TARGET}.key -out ${TARGET}.csr -subj \
    "/CN=${SERVER}/"
openssl x509 -req -sha256 -days 1 -CA ${AUTHORITY}.crt -CAkey ${AUTHORITY}.key -CAcreateserial -in ${TARGET}.csr -out ${TARGET}.crt
chmod 644 ${TARGET}.key

cat ${AUTHORITY}.crt >> ${TARGET}.crt

# generate nginx settings
SERVER=${SERVER} FORWARD=${FORWARD} envsubst < "proxy.template.conf" > ${TARGET}.conf
