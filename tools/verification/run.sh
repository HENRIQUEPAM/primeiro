#!/usr/bin/env bash
#
# Roda todas as suites de verificacao na JVM, sem SDK do Android.
#
# Existe porque cada suite era invocada com uma lista de fontes escrita a mao, e
# um arquivo novo em `recognition/` quebrou a suite antiga sem que ninguem
# tivesse mexido nela. Aqui a lista de fontes fica em UM lugar so.
#
# Uso:
#   KOTLINC=/caminho/para/bin/kotlinc tools/verification/run.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

KOTLINC="${KOTLINC:-kotlinc}"
if ! command -v "$KOTLINC" >/dev/null 2>&1 && [ ! -x "$KOTLINC" ]; then
    echo "kotlinc nao encontrado. Defina KOTLINC=/caminho/para/bin/kotlinc" >&2
    exit 2
fi

LIB="$(dirname "$(readlink -f "$KOTLINC")")/../lib"
COROUTINES="$LIB/kotlinx-coroutines-core-jvm.jar"
STDLIB="$LIB/kotlin-stdlib.jar"
OUT="${TMPDIR:-/tmp}/portaretrato-verify"
rm -rf "$OUT"; mkdir -p "$OUT"

STUBS="tools/verification/stubs"
REC="app/src/main/java/com/portaretrato/app/recognition"
PEOPLE="app/src/main/java/com/portaretrato/app/people"
PHOTO="app/src/main/java/com/portaretrato/app/photo"
CALL="app/src/main/java/com/portaretrato/app/call"
SEC="app/src/main/java/com/portaretrato/app/security"

# Fontes que dependem de bibliotecas reais (WebRTC, Firestore, Firebase) e por
# isso nao entram em nenhuma compilacao aqui.
failed=0

run_suite() {
    local name="$1"; shift
    local dir="$OUT/$name"
    echo "=========================================================="
    echo "  $name"
    echo "=========================================================="
    if ! "$KOTLINC" -nowarn -classpath "$COROUTINES" -d "$dir" "$@" \
        "tools/verification/$name.kt" 2>&1 | grep -v "^warning:" | grep -v "Picked up"; then
        : # kotlinc escreve avisos no stderr; erros aparecem no grep acima
    fi
    if [ ! -d "$dir" ]; then
        echo "  ERRO: $name nao compilou"
        failed=$((failed + 1))
        return
    fi
    if ! java -cp "$dir:$COROUTINES:$STDLIB" "${name}Kt" 2>&1 | grep -v "Picked up"; then
        failed=$((failed + 1))
    fi
    echo
}

# Pipeline de reconhecimento. FaceScanCoordinator entra junto porque vive em
# `recognition/`, e ele arrasta `people/` e `photo/`.
run_suite Verify \
    $STUBS/*.kt $REC/*.kt $PEOPLE/*.kt $PHOTO/*.kt

# Indice de pessoas.
run_suite VerifyPeople \
    $STUBS/*.kt $REC/*.kt $PEOPLE/*.kt $PHOTO/*.kt

# Slideshow: Kotlin puro, sem stub nenhum.
run_suite VerifyPhoto \
    $PHOTO/SlideshowEngine.kt

# Modulo de chamadas: Kotlin puro.
run_suite VerifyCall \
    $CALL/CallModels.kt $CALL/CallStateMachine.kt $CALL/SignalingProtocol.kt \
    $CALL/AutoAnswerPolicy.kt

# Alinhamento com a especificacao v3.1.
run_suite VerifySpec \
    $CALL/CallModels.kt $CALL/CallStateMachine.kt $CALL/AutoAnswerPolicy.kt \
    $CALL/PhoneNumbers.kt $CALL/CallOptions.kt $CALL/PairingProtocol.kt \
    $CALL/SdpSigner.kt

# Politica de acesso a camera e criptografia de campo. Sao os fontes de
# `security/` que NAO dependem de Android (CameraGuard, CameraNotice e
# KeystoreKeyProvider dependem, e ficam de fora).
run_suite VerifySecurity \
    $SEC/CameraAccessPolicy.kt $SEC/CameraAuditLog.kt $SEC/PermissionFlow.kt \
    $SEC/FieldCrypto.kt

echo "=========================================================="
python3 tools/verification/validate_project.py || failed=$((failed + 1))

echo
if [ "$failed" -eq 0 ]; then
    echo "TODAS AS SUITES PASSARAM"
else
    echo "$failed SUITE(S) FALHARAM"
    exit 1
fi
