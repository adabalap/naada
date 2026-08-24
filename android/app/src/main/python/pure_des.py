"""
Pure-Python DES (ECB mode) — decryption only.

Why this exists
---------------
JioSaavn returns stream URLs encrypted with single-DES in ECB mode. The
obvious way to decrypt them is the `cryptography` package, and that is what
this project used on the server.

But `cryptography` is a native extension (Rust + OpenSSL). Chaquopy can only
install native packages for which it has a prebuilt Android wheel, and it
ships those for a specific, limited set of versions. Pinning an arbitrary
version like 42.0.8 is very likely to fail the APK build with "no matching
distribution", and even a version that works today ties the build to
Chaquopy's wheel availability.

DES is a small, completely specified algorithm. Implementing it here in
~120 lines removes the only native dependency in the whole app, so every
remaining package is pure Python and guaranteed to install. The block size
is 8 bytes and the payloads here are short URLs, so the performance
difference is irrelevant.

Only decryption is implemented, because that is all this app needs.
"""

# ── Standard DES tables (FIPS 46-3) ────────────────────────────────────────

_PC1 = [56, 48, 40, 32, 24, 16,  8,  0, 57, 49, 41, 33, 25, 17,
         9,  1, 58, 50, 42, 34, 26, 18, 10,  2, 59, 51, 43, 35,
        62, 54, 46, 38, 30, 22, 14,  6, 61, 53, 45, 37, 29, 21,
        13,  5, 60, 52, 44, 36, 28, 20, 12,  4, 27, 19, 11,  3]

_PC2 = [13, 16, 10, 23,  0,  4,  2, 27, 14,  5, 20,  9,
        22, 18, 11,  3, 25,  7, 15,  6, 26, 19, 12,  1,
        40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47,
        43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31]

_IP = [57, 49, 41, 33, 25, 17,  9,  1, 59, 51, 43, 35, 27, 19, 11,  3,
       61, 53, 45, 37, 29, 21, 13,  5, 63, 55, 47, 39, 31, 23, 15,  7,
       56, 48, 40, 32, 24, 16,  8,  0, 58, 50, 42, 34, 26, 18, 10,  2,
       60, 52, 44, 36, 28, 20, 12,  4, 62, 54, 46, 38, 30, 22, 14,  6]

_FP = [39,  7, 47, 15, 55, 23, 63, 31, 38,  6, 46, 14, 54, 22, 62, 30,
       37,  5, 45, 13, 53, 21, 61, 29, 36,  4, 44, 12, 52, 20, 60, 28,
       35,  3, 43, 11, 51, 19, 59, 27, 34,  2, 42, 10, 50, 18, 58, 26,
       33,  1, 41,  9, 49, 17, 57, 25, 32,  0, 40,  8, 48, 16, 56, 24]

_E = [31,  0,  1,  2,  3,  4,  3,  4,  5,  6,  7,  8,
       7,  8,  9, 10, 11, 12, 11, 12, 13, 14, 15, 16,
      15, 16, 17, 18, 19, 20, 19, 20, 21, 22, 23, 24,
      23, 24, 25, 26, 27, 28, 27, 28, 29, 30, 31,  0]

_P = [15,  6, 19, 20, 28, 11, 27, 16,  0, 14, 22, 25,  4, 17, 30,  9,
       1,  7, 23, 13, 31, 26,  2,  8, 18, 12, 29,  5, 21, 10,  3, 24]

_SBOX = [
    [14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7,
      0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8,
      4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0,
     15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13],
    [15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10,
      3,13,4,7,15,2,8,14,12,0,1,10,6,9,11,5,
      0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15,
     13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9],
    [10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8,
     13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1,
     13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7,
      1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12],
    [7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15,
     13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9,
     10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4,
      3,15,0,6,10,1,13,8,9,4,5,11,12,7,2,14],
    [2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9,
     14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6,
      4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14,
     11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3],
    [12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11,
     10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8,
      9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6,
      4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13],
    [4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1,
     13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6,
      1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2,
      6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12],
    [13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7,
      1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2,
      7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8,
      2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11],
]

_SHIFTS = [1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1]


def _bits(data: bytes):
    out = []
    for byte in data:
        for i in range(7, -1, -1):
            out.append((byte >> i) & 1)
    return out


def _bytes_from_bits(bits):
    out = bytearray()
    for i in range(0, len(bits), 8):
        byte = 0
        for b in bits[i:i + 8]:
            byte = (byte << 1) | b
        out.append(byte)
    return bytes(out)


def _permute(bits, table):
    return [bits[i] for i in table]


def _subkeys(key: bytes):
    """Derive the 16 round keys from the 8-byte key."""
    kb = _permute(_bits(key), _PC1)
    c, d = kb[:28], kb[28:]
    keys = []
    for shift in _SHIFTS:
        c = c[shift:] + c[:shift]
        d = d[shift:] + d[:shift]
        keys.append(_permute(c + d, _PC2))
    return keys


def _feistel(right, subkey):
    x = _permute(right, _E)
    x = [a ^ b for a, b in zip(x, subkey)]
    out = []
    for i in range(8):
        chunk = x[i * 6:(i + 1) * 6]
        row = (chunk[0] << 1) | chunk[5]
        col = (chunk[1] << 3) | (chunk[2] << 2) | (chunk[3] << 1) | chunk[4]
        val = _SBOX[i][row * 16 + col]
        out += [(val >> 3) & 1, (val >> 2) & 1, (val >> 1) & 1, val & 1]
    return _permute(out, _P)


def _process_block(block: bytes, subkeys) -> bytes:
    bits = _permute(_bits(block), _IP)
    left, right = bits[:32], bits[32:]
    for k in subkeys:
        left, right = right, [a ^ b for a, b in zip(left, _feistel(right, k))]
    return _bytes_from_bits(_permute(right + left, _FP))


def decrypt_ecb(key: bytes, data: bytes, unpad: bool = True) -> bytes:
    """Decrypt `data` with single-DES in ECB mode.

    key   : exactly 8 bytes
    data  : length must be a multiple of 8
    unpad : strip PKCS#5/7 padding if it looks valid
    """
    if len(key) != 8:
        raise ValueError(f"DES key must be 8 bytes, got {len(key)}")
    if len(data) % 8:
        raise ValueError(f"ciphertext must be a multiple of 8 bytes, got {len(data)}")

    # Decryption is encryption with the round keys reversed.
    subkeys = list(reversed(_subkeys(key)))
    out = b"".join(_process_block(data[i:i + 8], subkeys)
                   for i in range(0, len(data), 8))

    if unpad and out:
        pad = out[-1]
        # Only strip when the padding is well-formed, so a genuine trailing
        # byte in unpadded data is never silently eaten.
        if 1 <= pad <= 8 and out.endswith(bytes([pad]) * pad):
            out = out[:-pad]
    return out
