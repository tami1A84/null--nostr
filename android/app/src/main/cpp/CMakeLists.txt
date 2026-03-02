cmake_minimum_required(VERSION 3.22.1)
project("nostrdb_jni")

# Set the C standard
set(CMAKE_C_STANDARD 11)

# Include directories
set(NOSTRDB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/nostrdb)
include_directories(
    ${NOSTRDB_DIR}
    ${NOSTRDB_DIR}/src
    ${NOSTRDB_DIR}/deps/lmdb
    ${NOSTRDB_DIR}/deps/secp256k1/include
    ${NOSTRDB_DIR}/deps/flatcc/include
)

# secp256k1 configuration for nostrdb
add_definitions(-DENABLE_MODULE_SCHNORRSIG=1)
add_definitions(-DENABLE_MODULE_EXTRAKEYS=1)

# nostrdb and dependencies sources
set(NOSTRDB_SOURCES
    ${NOSTRDB_DIR}/src/nostrdb.c
    ${NOSTRDB_DIR}/src/hex.c
    ${NOSTRDB_DIR}/src/base64.c
    ${NOSTRDB_DIR}/src/binmoji.c
    ${NOSTRDB_DIR}/src/block.c
    ${NOSTRDB_DIR}/src/hkdf_sha256.c
    ${NOSTRDB_DIR}/src/hmac_sha256.c
    ${NOSTRDB_DIR}/src/invoice.c
    ${NOSTRDB_DIR}/src/metadata.c
    ${NOSTRDB_DIR}/src/nip44.c
    ${NOSTRDB_DIR}/src/nostr_bech32.c
    ${NOSTRDB_DIR}/src/bolt11/amount.c
    ${NOSTRDB_DIR}/src/bolt11/bech32.c
    ${NOSTRDB_DIR}/src/bolt11/bech32_util.c
    ${NOSTRDB_DIR}/src/bolt11/bolt11.c
    ${NOSTRDB_DIR}/src/bolt11/error.c
    ${NOSTRDB_DIR}/src/bolt11/hash_u5.c
    ${NOSTRDB_DIR}/src/bolt11/node_id.c
    ${NOSTRDB_DIR}/deps/lmdb/mdb.c
    ${NOSTRDB_DIR}/deps/lmdb/midl.c
    ${NOSTRDB_DIR}/deps/secp256k1/src/secp256k1.c
)

# JNI wrapper source
set(JNI_SOURCES
    nostrdb-jni.c
)

# Build the shared library
add_library(nostrdb_jni SHARED
    ${NOSTRDB_SOURCES}
    ${JNI_SOURCES}
)

# Link libraries
target_link_libraries(nostrdb_jni
    log
)
