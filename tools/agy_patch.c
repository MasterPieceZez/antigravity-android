#define _POSIX_C_SOURCE 200809L
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char OLD_PKG[10] = "com.termux";
static const char NEW_PKG[10] = "com.agy.os";
#define PKG_LEN 10

static void patch_buffer_inplace(uint8_t *buf, size_t len) {
    if (len < PKG_LEN) return;
    size_t limit = len - PKG_LEN;
    for (size_t i = 0; i <= limit; ) {
        if (buf[i] == 'c' && memcmp(buf + i, OLD_PKG, PKG_LEN) == 0) {
            memcpy(buf + i, NEW_PKG, PKG_LEN);
            i += PKG_LEN;
        } else {
            i++;
        }
    }
}

static void patch_tar_field(uint8_t *field, size_t field_len) {
    size_t actual_len = 0;
    while (actual_len < field_len && field[actual_len] != '\0') {
        actual_len++;
    }
    patch_buffer_inplace(field, actual_len);
}

static int read_exact(FILE *in, uint8_t *buf, size_t n) {
    size_t total = 0;
    while (total < n) {
        size_t r = fread(buf + total, 1, n - total, in);
        if (r == 0) {
            return total == 0 ? 0 : -1;
        }
        total += r;
    }
    return 1;
}

static int write_exact(FILE *out, const uint8_t *buf, size_t n) {
    size_t total = 0;
    while (total < n) {
        size_t w = fwrite(buf + total, 1, n - total, out);
        if (w == 0) return -1;
        total += w;
    }
    return 0;
}

static uint64_t parse_tar_size(const uint8_t *field) {
    if ((field[0] & 0x80) != 0) {
        uint64_t v = (uint64_t)(field[0] & 0x7f);
        for (int i = 1; i < 12; i++) {
            v = (v << 8) | field[i];
        }
        return v;
    }
    uint64_t v = 0;
    for (int i = 0; i < 12; i++) {
        uint8_t c = field[i];
        if (c == '\0' || c == ' ') continue;
        if (c >= '0' && c <= '7') {
            v = (v << 3) | (uint64_t)(c - '0');
        }
    }
    return v;
}

static int is_zero_block(const uint8_t *blk) {
    for (size_t i = 0; i < 512; i++) {
        if (blk[i] != 0) return 0;
    }
    return 1;
}

static void recompute_tar_checksum(uint8_t *hdr) {
    memset(hdr + 148, ' ', 8);
    unsigned int sum = 0;
    for (size_t i = 0; i < 512; i++) {
        sum += hdr[i];
    }
    char csum[8];
    snprintf(csum, sizeof(csum), "%06o", sum & 0777777u);
    memcpy(hdr + 148, csum, 6);
    hdr[154] = '\0';
    hdr[155] = ' ';
}

static int stream_patch(FILE *in, FILE *out, int has_limit, uint64_t limit_bytes) {
    uint8_t buf[65536 + PKG_LEN];
    size_t carry = 0;
    uint64_t remaining = limit_bytes;

    while (!has_limit || remaining > 0) {
        size_t to_read = sizeof(buf) - PKG_LEN;
        if (has_limit && remaining < to_read) {
            to_read = (size_t)remaining;
        }
        size_t r = fread(buf + carry, 1, to_read, in);
        if (r == 0) {
            if (has_limit && remaining > 0) return -1;
            break;
        }
        if (has_limit) remaining -= r;

        size_t total = carry + r;
        if (total < PKG_LEN) {
            carry = total;
            continue;
        }

        size_t safe_end = total - (PKG_LEN - 1);
        patch_buffer_inplace(buf, total);

        /* Re-check if a match could straddle safe_end: patch_buffer_inplace
           already patched any full 10-byte match inside [0, total), so
           bytes before safe_end are final. */
        if (write_exact(out, buf, safe_end) != 0) return -1;
        carry = total - safe_end;
        memmove(buf, buf + safe_end, carry);
    }

    if (carry > 0) {
        patch_buffer_inplace(buf, carry);
        if (write_exact(out, buf, carry) != 0) return -1;
    }
    return 0;
}

static int mode_tar(void) {
    uint8_t hdr[512];
    for (;;) {
        int rc = read_exact(stdin, hdr, 512);
        if (rc == 0) break;
        if (rc < 0) return 1;

        if (is_zero_block(hdr)) {
            if (write_exact(stdout, hdr, 512) != 0) return 1;
            continue;
        }

        uint64_t size = parse_tar_size(hdr + 124);
        patch_tar_field(hdr + 0, 100);
        patch_tar_field(hdr + 157, 100);
        patch_tar_field(hdr + 345, 155);
        recompute_tar_checksum(hdr);

        if (write_exact(stdout, hdr, 512) != 0) return 1;

        if (size > 0) {
            if (stream_patch(stdin, stdout, 1, size) != 0) return 1;
            size_t pad = (size_t)((512 - (size % 512)) % 512);
            if (pad > 0) {
                uint8_t padbuf[512];
                if (read_exact(stdin, padbuf, pad) != 1) return 1;
                if (write_exact(stdout, padbuf, pad) != 0) return 1;
            }
        }
    }
    fflush(stdout);
    return 0;
}

static int mode_file(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return 1;
    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return 1;
    }
    long sz = ftell(f);
    if (sz < 0) {
        fclose(f);
        return 1;
    }
    rewind(f);

    if (sz == 0) {
        fclose(f);
        return 0;
    }

    uint8_t *buf = (uint8_t *)malloc((size_t)sz);
    if (!buf) {
        fclose(f);
        return 1;
    }
    if (fread(buf, 1, (size_t)sz, f) != (size_t)sz) {
        free(buf);
        fclose(f);
        return 1;
    }
    fclose(f);

    patch_buffer_inplace(buf, (size_t)sz);

    f = fopen(path, "wb");
    if (!f) {
        free(buf);
        return 1;
    }
    int ok = write_exact(f, buf, (size_t)sz);
    fclose(f);
    free(buf);
    return ok == 0 ? 0 : 1;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--tar") == 0) {
        return mode_tar();
    }
    if (argc == 2 && strcmp(argv[1], "--bytes") == 0) {
        int rc = stream_patch(stdin, stdout, 0, 0);
        fflush(stdout);
        return rc == 0 ? 0 : 1;
    }
    if (argc == 3 && strcmp(argv[1], "--file") == 0) {
        return mode_file(argv[2]);
    }
    fprintf(stderr, "Usage: %s --tar | --bytes | --file <path>\n", argv[0]);
    return 2;
}
