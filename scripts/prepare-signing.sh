#!/bin/bash

if [ "x${ENCRYPTION_KEY}" == "x" ]; then
    echo "The encryption key has not been provided. Skipping signing preparation"
    exit 0
fi

if [ "x${ENCRYPTION_IV}" == "x" ]; then
    echo "The encryption initialization vector has not been provided. Skipping signing preparation"
    exit 0
fi

if [ "x${SIGNING_KEY_ID}" == "x" ]; then
    echo "The signing key id has not been provided. Skipping signing preparation"
    exit 0
fi

if [ "x${SIGNING_KEY_PASSPHRASE}" == "x" ]; then
    echo "The signing key passphrase has not been provided. Skipping signing preparation"
    exit 0
fi

if [ "x${SONATYPE_USERNAME}" == "x" ]; then
    echo "The sonatype username has not been provided. Skipping signing preparation"
    exit 0
fi

if [ "x${SONATYPE_PASSWORD}" == "x" ]; then
    echo "The sonatype password has not been provided. Skipping signing preparation"
    exit 0
fi

openssl aes-256-cbc -K "${ENCRYPTION_KEY}" -iv "${ENCRYPTION_IV}" -in scripts/signing-key.asc.enc -out scripts/signing-key.asc -d
if (( $? != 0 )); then
    echo "Failed to decrypt the signing key. Skipping."
    exit 1
fi

gpg --no-tty --batch --allow-secret-key-import --import scripts/signing-key.asc
rm -rf "$HOME/.gradle/gradle.properties"
echo signing.keyId="${SIGNING_KEY_ID}" > "$HOME/.gradle/gradle.properties"
echo signing.password="${SIGNING_KEY_PASSPHRASE}" >> "$HOME/.gradle/gradle.properties"
echo ossrhUsername="${SONATYPE_USERNAME}" >> "$HOME/.gradle/gradle.properties"
echo ossrhPassword="${SONATYPE_PASSWORD}" >> "$HOME/.gradle/gradle.properties"
echo signing.secretKeyRingFile="${HOME}/.gnupg/secring.gpg" >> "$HOME/.gradle/gradle.properties"
