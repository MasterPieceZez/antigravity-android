#define _POSIX_C_SOURCE 200809L
#include <ctype.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static const char OLD_PKG[] = "com.termux";
static const char NEW_PKG[] = "com.agy.os";
#define PKG_LEN 10
#define MAX_TAR_PATH 8192
#define MAX_TAR_META 65536

static int buffer_contains_old_pkg(const uint8_t *buf, size_t len) {
    if (len < PKG_LEN) return 0;
    size_t limit = len - PKG_LEN;
    for (size_t i = 0; i <= limit; i++) {
        if (buf[i] == 'c' && memcmp(buf + i, OLD_PKG, PKG_LEN) == 0) {
            return 1;
        }
    }
    return 0;
}

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

static void copy_tar_cstr(const uint8_t *field, size_t field_len, char *out, size_t out_cap) {
    size_t n = 0;
    while (n < field_len && n + 1 < out_cap && field[n] != '\0') {
        out[n] = (char)field[n];
        n++;
    }
    out[n] = '\0';
}

static int is_ustar_header(const uint8_t *hdr) {
    return memcmp(hdr + 257, "ustar\0", 6) == 0;
}

static void build_ustar_path(const uint8_t *hdr, char *out, size_t out_cap) {
    char prefix[156];
    char name[101];
    prefix[0] = '\0';
    if (is_ustar_header(hdr)) {
        copy_tar_cstr(hdr + 345, 155, prefix, sizeof(prefix));
    }
    copy_tar_cstr(hdr + 0, 100, name, sizeof(name));
    if (prefix[0] != '\0') {
        snprintf(out, out_cap, "%s/%s", prefix, name);
    } else {
        snprintf(out, out_cap, "%s", name);
    }
}

static int ends_with_ci(const char *str, const char *suffix) {
    size_t slen = strlen(str);
    size_t xlen = strlen(suffix);
    if (slen < xlen) return 0;
    const char *p = str + (slen - xlen);
    for (size_t i = 0; i < xlen; i++) {
        if (tolower((unsigned char)p[i]) != tolower((unsigned char)suffix[i])) {
            return 0;
        }
    }
    return 1;
}

static int has_binary_or_compressed_ext(const char *path) {
    static const char *const exts[] = {
        ".gz", ".tgz", ".xz", ".txz", ".bz2", ".tbz2",
        ".zst", ".tzst", ".lz4", ".7z", ".rar", ".tar",
        ".zip", ".deb", ".whl", ".jar", ".apk", ".pyc",
        ".a", ".o", ".lzma", ".br", ".z", ".lzo", ".pak", ".v8", ".ico",
        ".png", ".jpg", ".jpeg", ".webp", ".gif",
        ".sqlite", ".sqlite3", ".db", ".ldb", ".sst",
        ".wasm", ".node", ".asar", ".pdf", ".woff2", ".otf", ".ttf",
        ".mp3", ".mp4", ".flac", ".ogg", ".wav", ".webm", ".mkv", ".avi"
    };
    size_t count = sizeof(exts) / sizeof(exts[0]);
    for (size_t i = 0; i < count; i++) {
        if (ends_with_ci(path, exts[i])) return 1;
    }
    return 0;
}

static int is_home_user_file(const char *path) {
    if (!path) return 0;
    const char *p = path;
    while (p[0] == '.' && p[1] == '/') p += 2;
    while (p[0] == '/') p++;
    if (strncmp(p, "home/", 5) != 0) return 0;
    const char *rest = p + 5;
    if (rest[0] == '\0' || rest[0] == '.') return 0;
    return 1;
}

static int has_compressed_or_binary_magic(const uint8_t *buf, size_t len) {
    if (len >= 2 && buf[0] == 0x1f && buf[1] == 0x8b) return 1; /* gzip */
    if (len >= 6 && buf[0] == 0xfd && buf[1] == '7' && buf[2] == 'z' &&
        buf[3] == 'X' && buf[4] == 'Z' && buf[5] == 0x00) return 1; /* xz */
    if (len >= 4 && buf[0] == 0x28 && buf[1] == 0xb5 &&
        buf[2] == 0x2f && buf[3] == 0xfd) return 1; /* zstd */
    if (len >= 6 && buf[0] == '7' && buf[1] == 'z' && buf[2] == 0xbc &&
        buf[3] == 0xaf && buf[4] == 0x27 && buf[5] == 0x1c) return 1; /* 7z */
    if (len >= 4 && buf[0] == 'P' && buf[1] == 'K' &&
        buf[2] == 0x03 && buf[3] == 0x04) return 1; /* zip/jar/apk/whl */
    if (len >= 3 && buf[0] == 'B' && buf[1] == 'Z' && buf[2] == 'h') return 1; /* bzip2 */
    if (len >= 8 && buf[0] == 0x89 && buf[1] == 'P' && buf[2] == 'N' &&
        buf[3] == 'G' && buf[4] == '\r' && buf[5] == '\n' &&
        buf[6] == 0x1a && buf[7] == '\n') return 1; /* png */
    if (len >= 5 && memcmp(buf, "%PDF-", 5) == 0) return 1; /* pdf */
    if (len >= 4 && memcmp(buf, "wOF2", 4) == 0) return 1; /* woff2 */
    if (len >= 4 && buf[0] == 0x00 && buf[1] == 'a' && buf[2] == 's' && buf[3] == 'm') return 1; /* wasm */
    if (len >= 16 && memcmp(buf, "SQLite format 3\0", 16) == 0) return 1; /* sqlite */
    return 0;
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

static int discard_exact(FILE *in, uint64_t n) {
    uint8_t buf[65536];
    uint64_t rem = n;
    while (rem > 0) {
        size_t chunk = rem > sizeof(buf) ? sizeof(buf) : (size_t)rem;
        if (read_exact(in, buf, chunk) != 1) return -1;
        rem -= chunk;
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

static int stream_patch_ex(FILE *in, FILE *out, int has_limit, uint64_t limit_bytes,
                           int skip_patch, int detect_magic) {
    uint8_t buf[65536 + PKG_LEN];
    size_t carry = 0;
    uint64_t remaining = limit_bytes;
    int first_chunk = 1;

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
        if (first_chunk) {
            first_chunk = 0;
            if (!skip_patch && detect_magic && has_compressed_or_binary_magic(buf, total)) {
                skip_patch = 1;
            }
        }

        if (skip_patch) {
            if (write_exact(out, buf, total) != 0) return -1;
            carry = 0;
            continue;
        }

        if (total < PKG_LEN) {
            carry = total;
            continue;
        }

        size_t safe_end = total - (PKG_LEN - 1);
        patch_buffer_inplace(buf, total);

        if (write_exact(out, buf, safe_end) != 0) return -1;
        carry = total - safe_end;
        memmove(buf, buf + safe_end, carry);
    }

    if (carry > 0) {
        if (!skip_patch) {
            patch_buffer_inplace(buf, carry);
        }
        if (write_exact(out, buf, carry) != 0) return -1;
    }
    return 0;
}

static int parse_pax_records(const char *buf, size_t len,
                             char *pax_path, size_t path_cap, int *has_path,
                             char *pax_link, size_t link_cap, int *has_link,
                             int reject_path_keys);

static int mode_tar(void) {
    uint8_t hdr[512];
    char gnu_longname[MAX_TAR_PATH];
    char pax_path[MAX_TAR_PATH];
    char pax_link_unused[MAX_TAR_PATH];
    int has_gnu_longname = 0;
    int has_pax_path = 0;
    int has_pax_link_unused = 0;
    gnu_longname[0] = '\0';
    pax_path[0] = '\0';

    for (;;) {
        int rc = read_exact(stdin, hdr, 512);
        if (rc == 0) break;
        if (rc < 0) return 1;

        if (is_zero_block(hdr)) {
            if (write_exact(stdout, hdr, 512) != 0) return 1;
            continue;
        }

        uint8_t typeflag = hdr[156];
        uint64_t size = parse_tar_size(hdr + 124);

        char entry_path[MAX_TAR_PATH];
        if (has_pax_path) {
            snprintf(entry_path, sizeof(entry_path), "%s", pax_path);
        } else if (has_gnu_longname) {
            snprintf(entry_path, sizeof(entry_path), "%s", gnu_longname);
        } else {
            build_ustar_path(hdr, entry_path, sizeof(entry_path));
        }

        patch_tar_field(hdr + 0, 100);
        patch_tar_field(hdr + 157, 100);
        if (is_ustar_header(hdr)) {
            patch_tar_field(hdr + 345, 155);
        }
        recompute_tar_checksum(hdr);

        if (write_exact(stdout, hdr, 512) != 0) return 1;

        int is_meta = (typeflag == 'L' || typeflag == 'K' || typeflag == 'x' || typeflag == 'g');
        if (typeflag == 'L' && size > 0 && size < sizeof(gnu_longname)) {
            if (read_exact(stdin, (uint8_t *)gnu_longname, (size_t)size) != 1) return 1;
            gnu_longname[size] = '\0';
            patch_buffer_inplace((uint8_t *)gnu_longname, (size_t)size);
            if (write_exact(stdout, (const uint8_t *)gnu_longname, (size_t)size) != 0) return 1;
            has_gnu_longname = 1;
        } else if (typeflag == 'x' && size > 0 && size < MAX_TAR_META) {
            char *meta = (char *)malloc((size_t)size + 1);
            if (!meta) return 1;
            if (read_exact(stdin, (uint8_t *)meta, (size_t)size) != 1) {
                free(meta);
                return 1;
            }
            meta[size] = '\0';
            (void)parse_pax_records(meta, (size_t)size,
                                    pax_path, sizeof(pax_path), &has_pax_path,
                                    pax_link_unused, sizeof(pax_link_unused), &has_pax_link_unused, 0);
            patch_buffer_inplace((uint8_t *)meta, (size_t)size);
            int wrc = write_exact(stdout, (const uint8_t *)meta, (size_t)size);
            free(meta);
            if (wrc != 0) return 1;
        } else if (size > 0) {
            int skip_patch = 0;
            int detect_magic = 0;
            if (!is_meta && (typeflag == '0' || typeflag == '\0')) {
                skip_patch = has_binary_or_compressed_ext(entry_path) || is_home_user_file(entry_path);
                detect_magic = 1;
            }
            if (stream_patch_ex(stdin, stdout, 1, size, skip_patch, detect_magic) != 0) return 1;
        }

        if (size > 0) {
            size_t pad = (size_t)((512 - (size % 512)) % 512);
            if (pad > 0) {
                uint8_t padbuf[512];
                if (read_exact(stdin, padbuf, pad) != 1) return 1;
                if (write_exact(stdout, padbuf, pad) != 0) return 1;
            }
        }

        if (!is_meta) {
            has_gnu_longname = 0;
            has_pax_path = 0;
            has_pax_link_unused = 0;
            gnu_longname[0] = '\0';
            pax_path[0] = '\0';
        }
    }
    fflush(stdout);
    return 0;
}

static int is_safe_rel_path(const char *p) {
    if (!p || p[0] == '\0') return 1;
    if (p[0] == '/' || p[0] == '\\') return 0;

    const char *seg = p;
    for (const char *c = p; ; c++) {
        unsigned char uc = (unsigned char)*c;
        if (uc != '\0' && uc < 0x20) return 0;
        if (*c == '\\') return 0;
        if (*c == '/' || *c == '\0') {
            size_t len = (size_t)(c - seg);
            if (len == 2 && seg[0] == '.' && seg[1] == '.') {
                return 0;
            }
            if (*c == '\0') break;
            seg = c + 1;
        }
    }
    return 1;
}

static int starts_with(const char *str, const char *prefix) {
    size_t plen = strlen(prefix);
    return strncmp(str, prefix, plen) == 0;
}

static void normalize_rel_for_compare(const char *p, char *out, size_t cap) {
    if (!p || cap == 0) {
        if (cap > 0) out[0] = '\0';
        return;
    }
    while (p[0] == '.' && p[1] == '/') p += 2;
    while (p[0] == '/') p++;
    size_t n = 0;
    for (const char *c = p; *c != '\0' && n + 1 < cap; c++) {
        if (*c == '/' && (n == 0 || out[n - 1] == '/')) continue;
        out[n++] = *c;
    }
    out[n] = '\0';
    for (;;) {
        while (n > 0 && out[n - 1] == '/') {
            out[--n] = '\0';
        }
        if (n >= 2 && out[n - 2] == '/' && out[n - 1] == '.') {
            n -= 2;
            out[n] = '\0';
        } else if (n == 1 && out[0] == '.') {
            n = 0;
            out[0] = '\0';
        } else {
            break;
        }
    }
}

static int ends_with_slash_or_dot(const char *p) {
    if (!p || p[0] == '\0') return 1;
    size_t len = strlen(p);
    if (p[len - 1] == '/') return 1;
    if (len >= 2 && p[len - 2] == '/' && p[len - 1] == '.') return 1;
    if (len == 1 && p[0] == '.') return 1;
    return 0;
}

typedef struct {
    char **items;
    size_t count;
    size_t cap;
} StrList;

static int strlist_add(StrList *list, const char *s) {
    if (list->count == list->cap) {
        size_t next_cap = list->cap == 0 ? 16 : list->cap * 2;
        char **next = (char **)realloc(list->items, next_cap * sizeof(char *));
        if (!next) return -1;
        list->items = next;
        list->cap = next_cap;
    }
    size_t len = strlen(s);
    char *copy = (char *)malloc(len + 1);
    if (!copy) return -1;
    memcpy(copy, s, len + 1);
    list->items[list->count++] = copy;
    return 0;
}

static void strlist_free(StrList *list) {
    for (size_t i = 0; i < list->count; i++) {
        free(list->items[i]);
    }
    free(list->items);
    list->items = NULL;
    list->count = 0;
    list->cap = 0;
}

static int strlist_matches_prefix(const StrList *list, const char *norm_path, int allow_exact) {
    for (size_t i = 0; i < list->count; i++) {
        const char *sym = list->items[i];
        size_t slen = strlen(sym);
        if (slen == 0) continue;
        if (strncmp(norm_path, sym, slen) == 0) {
            if (norm_path[slen] == '/') return 1;
            if (!allow_exact && norm_path[slen] == '\0') return 1;
        }
    }
    return 0;
}

static int is_safe_symlink_target(const char *entry_path, const char *target) {
    if (!target || target[0] == '\0') return 0;
    if (ends_with_slash_or_dot(entry_path)) return 0;
    for (const char *c = target; *c != '\0'; c++) {
        unsigned char uc = (unsigned char)*c;
        if (uc < 0x20 || *c == '\\') return 0;
    }
    if (target[0] == '/') {
        if (!is_safe_rel_path(target + 1)) return 0;
        return starts_with(target, "/data/data/com.termux/files/") ||
               starts_with(target, "/data/data/com.agy.os/files/") ||
               starts_with(target, "/storage/") ||
               starts_with(target, "/sdcard/");
    }

    char norm_entry[MAX_TAR_PATH];
    normalize_rel_for_compare(entry_path, norm_entry, sizeof(norm_entry));
    if (norm_entry[0] == '\0') return 0;

    int depth = 0;
    const char *last_slash = strrchr(norm_entry, '/');
    if (last_slash) {
        const char *seg = norm_entry;
        for (const char *c = norm_entry; c <= last_slash; c++) {
            if (*c == '/') {
                size_t len = (size_t)(c - seg);
                if (len == 2 && seg[0] == '.' && seg[1] == '.') return 0;
                if (len > 0 && !(len == 1 && seg[0] == '.')) depth++;
                seg = c + 1;
            }
        }
    }

    const char *seg = target;
    for (const char *c = target; ; c++) {
        if (*c == '/' || *c == '\0') {
            size_t len = (size_t)(c - seg);
            if (len == 2 && seg[0] == '.' && seg[1] == '.') {
                if (depth <= 0) return 0;
                depth--;
            } else if (len > 0 && !(len == 1 && seg[0] == '.')) {
                depth++;
            }
            if (*c == '\0') break;
            seg = c + 1;
        }
    }
    return 1;
}

static int parse_pax_records(const char *buf, size_t len,
                             char *pax_path, size_t path_cap, int *has_path,
                             char *pax_link, size_t link_cap, int *has_link,
                             int reject_path_keys) {
    size_t pos = 0;
    while (pos < len) {
        size_t rec_len = 0;
        size_t p = pos;
        while (p < len && buf[p] >= '0' && buf[p] <= '9') {
            size_t d = (size_t)(buf[p] - '0');
            if (rec_len > (len - pos) / 10 + 1) return -1;
            rec_len = rec_len * 10 + d;
            p++;
        }
        if (p == pos || p >= len || buf[p] != ' ' || rec_len < 4 || pos + rec_len > len) {
            return -1;
        }
        const char *rec = buf + p + 1;
        size_t kv_len = pos + rec_len - (p + 1);
        if (kv_len == 0 || rec[kv_len - 1] != '\n') return -1;
        kv_len--;

        const char *eq = (const char *)memchr(rec, '=', kv_len);
        if (eq) {
            size_t klen = (size_t)(eq - rec);
            const char *val = eq + 1;
            size_t vlen = kv_len - klen - 1;
            if (memchr(val, '\0', vlen) != NULL) return -1;

            if (klen == 4 && memcmp(rec, "path", 4) == 0) {
                if (reject_path_keys || vlen >= path_cap) return -1;
                memcpy(pax_path, val, vlen);
                pax_path[vlen] = '\0';
                *has_path = 1;
            } else if (klen == 8 && memcmp(rec, "linkpath", 8) == 0) {
                if (reject_path_keys || vlen >= link_cap) return -1;
                memcpy(pax_link, val, vlen);
                pax_link[vlen] = '\0';
                *has_link = 1;
            }
        }
        pos += rec_len;
    }
    return 0;
}

static int mode_check_tar(void) {
    uint8_t hdr[512];
    char longname[MAX_TAR_PATH];
    char longlink[MAX_TAR_PATH];
    char pax_path[MAX_TAR_PATH];
    char pax_link[MAX_TAR_PATH];
    int has_longname = 0, has_longlink = 0, has_pax_path = 0, has_pax_link = 0;
    int seen_entry = 0;
    int ret = 1;
    StrList symlinks = {NULL, 0, 0};

    for (;;) {
        int rc = read_exact(stdin, hdr, 512);
        if (rc == 0) {
            ret = seen_entry ? 0 : 1;
            break;
        }
        if (rc < 0) goto cleanup;

        if (is_zero_block(hdr)) continue;

        uint8_t typeflag = hdr[156];
        uint64_t size = parse_tar_size(hdr + 124);
        if (size > UINT64_MAX - 512) {
            fprintf(stderr, "Oversized tar entry\n");
            goto cleanup;
        }
        size_t pad = size > 0 ? (size_t)((512 - (size % 512)) % 512) : 0;

        if (typeflag == 'L' || typeflag == 'K' || typeflag == 'x' || typeflag == 'g') {
            size_t max_meta = (typeflag == 'x' || typeflag == 'g') ? MAX_TAR_META : MAX_TAR_PATH;
            if (size >= max_meta) {
                fprintf(stderr, "Oversized tar metadata header\n");
                goto cleanup;
            }
            char *meta = (char *)malloc((size_t)size + 1);
            if (!meta) goto cleanup;
            if (size > 0 && read_exact(stdin, (uint8_t *)meta, (size_t)size) != 1) {
                free(meta);
                goto cleanup;
            }
            meta[size] = '\0';
            if (pad > 0) {
                uint8_t padbuf[512];
                if (read_exact(stdin, padbuf, pad) != 1) {
                    free(meta);
                    goto cleanup;
                }
            }
            if (typeflag == 'L') {
                memcpy(longname, meta, (size_t)size + 1);
                has_longname = 1;
            } else if (typeflag == 'K') {
                memcpy(longlink, meta, (size_t)size + 1);
                has_longlink = 1;
            } else if (typeflag == 'x') {
                if (parse_pax_records(meta, (size_t)size,
                                      pax_path, sizeof(pax_path), &has_pax_path,
                                      pax_link, sizeof(pax_link), &has_pax_link, 0) != 0) {
                    fprintf(stderr, "Invalid PAX header\n");
                    free(meta);
                    goto cleanup;
                }
            } else {
                if (parse_pax_records(meta, (size_t)size,
                                      pax_path, sizeof(pax_path), &has_pax_path,
                                      pax_link, sizeof(pax_link), &has_pax_link, 1) != 0) {
                    fprintf(stderr, "Unsafe global PAX path override\n");
                    free(meta);
                    goto cleanup;
                }
            }
            free(meta);
            continue;
        }

        if (typeflag != 'V') {
            seen_entry = 1;
            char entry_path[MAX_TAR_PATH];
            if (has_pax_path) {
                snprintf(entry_path, sizeof(entry_path), "%s", pax_path);
            } else if (has_longname) {
                snprintf(entry_path, sizeof(entry_path), "%s", longname);
            } else {
                build_ustar_path(hdr, entry_path, sizeof(entry_path));
            }

            if (!is_safe_rel_path(entry_path)) {
                fprintf(stderr, "Rejected unsafe archive path: %s\n", entry_path);
                goto cleanup;
            }

            char norm_entry[MAX_TAR_PATH];
            normalize_rel_for_compare(entry_path, norm_entry, sizeof(norm_entry));
            if (norm_entry[0] != '\0' && strlist_matches_prefix(&symlinks, norm_entry, 1)) {
                fprintf(stderr, "Rejected write through archive symlink: %s\n", entry_path);
                goto cleanup;
            }

            if (typeflag == '1' || typeflag == '2') {
                char link_target[MAX_TAR_PATH];
                if (has_pax_link) {
                    snprintf(link_target, sizeof(link_target), "%s", pax_link);
                } else if (has_longlink) {
                    snprintf(link_target, sizeof(link_target), "%s", longlink);
                } else {
                    copy_tar_cstr(hdr + 157, 100, link_target, sizeof(link_target));
                }

                if (typeflag == '1') {
                    char norm_link[MAX_TAR_PATH];
                    normalize_rel_for_compare(link_target, norm_link, sizeof(norm_link));
                    if (link_target[0] == '\0' || !is_safe_rel_path(link_target) ||
                        strlist_matches_prefix(&symlinks, norm_link, 0)) {
                        fprintf(stderr, "Rejected unsafe hardlink %s -> %s\n", entry_path, link_target);
                        goto cleanup;
                    }
                } else {
                    if (!is_safe_symlink_target(entry_path, link_target)) {
                        fprintf(stderr, "Rejected unsafe symlink %s -> %s\n", entry_path, link_target);
                        goto cleanup;
                    }
                    if (norm_entry[0] != '\0' && strlist_add(&symlinks, norm_entry) != 0) {
                        goto cleanup;
                    }
                }
            }
        }

        has_longname = 0;
        has_longlink = 0;
        has_pax_path = 0;
        has_pax_link = 0;

        if (size + pad > 0 && discard_exact(stdin, size + pad) != 0) {
            fprintf(stderr, "Truncated tar archive\n");
            goto cleanup;
        }
    }

cleanup:
    strlist_free(&symlinks);
    return ret;
}

static int mode_file(const char *path) {
    struct stat st;
    if (lstat(path, &st) != 0) return 1;
    if (S_ISLNK(st.st_mode)) return 0;

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

    if (!buffer_contains_old_pkg(buf, (size_t)sz)) {
        free(buf);
        return 0;
    }

    patch_buffer_inplace(buf, (size_t)sz);

    size_t path_len = strlen(path);
    size_t tmp_cap = path_len + 32;
    char *tmp_path = (char *)malloc(tmp_cap);
    if (!tmp_path) {
        free(buf);
        return 1;
    }
    snprintf(tmp_path, tmp_cap, "%s.agytmp.%ld", path, (long)getpid());

    f = fopen(tmp_path, "wb");
    if (!f) {
        free(tmp_path);
        free(buf);
        return 1;
    }
    int ok = write_exact(f, buf, (size_t)sz);
    if (fflush(f) != 0) ok = -1;
    if (fclose(f) != 0) ok = -1;
    free(buf);

    if (ok == 0) {
        chmod(tmp_path, st.st_mode & 07777);
        if (rename(tmp_path, path) != 0) {
            unlink(tmp_path);
            free(tmp_path);
            return 1;
        }
    } else {
        unlink(tmp_path);
    }

    free(tmp_path);
    return ok == 0 ? 0 : 1;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--tar") == 0) {
        return mode_tar();
    }
    if (argc == 2 && strcmp(argv[1], "--check-tar") == 0) {
        return mode_check_tar();
    }
    if (argc == 2 && strcmp(argv[1], "--bytes") == 0) {
        int rc = stream_patch_ex(stdin, stdout, 0, 0, 0, 0);
        fflush(stdout);
        return rc == 0 ? 0 : 1;
    }
    if (argc == 3 && strcmp(argv[1], "--file") == 0) {
        return mode_file(argv[2]);
    }
    fprintf(stderr, "Usage: %s --tar | --check-tar | --bytes | --file <path>\n", argv[0]);
    return 2;
}
