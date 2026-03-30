#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROTO_SRC="${SCRIPT_DIR}/src/main/proto"
OUT_DIR="${SCRIPT_DIR}/generated/python"

VENV_PYTHON="${ROOT_DIR}/.venv/bin/python3"
if [ -x "${VENV_PYTHON}" ]; then
  PYTHON="${VENV_PYTHON}"
else
  PYTHON="python3"
fi

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

"${PYTHON}" -m grpc_tools.protoc \
  -I "${PROTO_SRC}" \
  --python_out="${OUT_DIR}" \
  --grpc_python_out="${OUT_DIR}" \
  --pyi_out="${OUT_DIR}" \
  "${PROTO_SRC}/signal.proto"

echo "Python stubs generated in ${OUT_DIR}"
