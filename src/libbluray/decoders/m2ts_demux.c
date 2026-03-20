/*
 * This file is part of libbluray
 * Copyright (C) 2010  hpi1
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library. If not, see
 * <http://www.gnu.org/licenses/>.
 */

#if HAVE_CONFIG_H
#include "config.h"
#endif

#include "m2ts_demux.h"
#include "pes_buffer.h"

#include "util/logging.h"
#include "util/macro.h"

#include <inttypes.h>
#include <stdarg.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

/*#define M2TS_TRACE(...) BD_DEBUG(DBG_CRIT,__VA_ARGS__)*/
#define M2TS_TRACE(...) do {} while(0)

static int _open3d_demux_trace_enabled(uint16_t pid)
{
    static int initialized = 0;
    static int enabled = 0;
    static unsigned trace_pid = 0;

    if (!initialized) {
        const char *env = getenv("OPEN3D_LIBBLURAY_MVC_TRACE_DEMUX");
        const char *pid_env = getenv("OPEN3D_LIBBLURAY_MVC_TRACE_DEMUX_PID");
        enabled = (env && env[0] && strcmp(env, "0")) ? 1 : 0;
        trace_pid = (pid_env && pid_env[0]) ? (unsigned)strtoul(pid_env, NULL, 0) : 0;
        initialized = 1;
    }

    if (!enabled) {
        return 0;
    }

    return trace_pid == 0 || trace_pid == pid;
}

static void _open3d_demux_trace(uint16_t pid, const char *fmt, ...)
{
    va_list ap;

    if (!_open3d_demux_trace_enabled(pid)) {
        return;
    }

    fprintf(stderr, "open3d_m2ts_demux pid=0x%04x ", pid);
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
}

static void _open3d_demux_trace_bytes(uint16_t pid, const char *label,
                                      const uint8_t *buf, unsigned len)
{
    char hex[3 * 16 + 1];
    unsigned ii;
    size_t off = 0;

    if (!_open3d_demux_trace_enabled(pid) || !label || !buf || len == 0) {
        return;
    }

    for (ii = 0; ii < len && ii < 16 && off + 3 < sizeof(hex); ii++) {
        int wrote = snprintf(hex + off, sizeof(hex) - off, "%s%02x",
                             ii ? " " : "", buf[ii]);
        if (wrote < 0 || (size_t)wrote >= sizeof(hex) - off) {
            break;
        }
        off += (size_t)wrote;
    }
    hex[off] = '\0';
    _open3d_demux_trace(pid, "%s len=%u bytes=[%s]", label, len, hex);
}

/*
 *
 */

struct m2ts_demux_s
{
    uint16_t    pid;
    uint32_t    pes_length;
    uint8_t     raw_pes_pending;
    PES_BUFFER *buf;
};

/*
 *
 */

static int64_t _parse_timestamp(uint8_t *p);

static int _parse_pes_header(PES_BUFFER *p, uint8_t *buf, unsigned len,
                             unsigned *hdr_len, unsigned *payload_len)
{
    unsigned pes_pid;
    unsigned pes_length;
    unsigned local_hdr_len = 6;

    if (len < 6) {
        return 1;
    }
    if (buf[0] || buf[1] || buf[2] != 1) {
        BD_DEBUG(DBG_DECODE, "invalid PES header (00 00 01)");
        return -1;
    }

    pes_pid = buf[3];
    pes_length = buf[4] << 8 | buf[5];

    if (pes_pid != 0xbf) {
        unsigned pts_exists;
        unsigned dts_exists;

        if (len < 9) {
            return 1;
        }

        pts_exists = buf[7] & 0x80;
        dts_exists = buf[7] & 0x40;
        local_hdr_len += buf[8] + 3;

        if (len < local_hdr_len) {
            return 1;
        }

        if (pts_exists) {
            p->pts = _parse_timestamp(buf + 9);
        }
        if (dts_exists) {
            p->dts = _parse_timestamp(buf + 14);
        }
    }

    if (hdr_len) {
        *hdr_len = local_hdr_len;
    }
    if (payload_len) {
        if (pes_length == 0) {
            *payload_len = 0;
        } else {
            *payload_len = pes_length + 6 - local_hdr_len;
        }
    }
    return 0;
}

static int _finalize_raw_pes(PES_BUFFER *p)
{
    unsigned hdr_len = 0;
    unsigned payload_len = 0;
    int r;

    if (!p || !p->buf || p->len == 0) {
        return -1;
    }

    p->pts = 0;
    p->dts = 0;
    r = _parse_pes_header(p, p->buf, p->len, &hdr_len, &payload_len);
    if (r != 0 || hdr_len > p->len) {
        return -1;
    }

    memmove(p->buf, p->buf + hdr_len, p->len - hdr_len);
    p->len -= hdr_len;
    return (int)payload_len;
}

static PES_BUFFER *_flush(M2TS_DEMUX *p)
{
    PES_BUFFER *result = NULL;

    if (p->raw_pes_pending && p->buf) {
        if (_finalize_raw_pes(p->buf) < 0) {
            _open3d_demux_trace(p->pid, "flush_raw_finalize_fail len=%u", p->buf->len);
            pes_buffer_free(&p->buf);
        }
        p->raw_pes_pending = 0;
    }

    result = p->buf;
    p->buf = NULL;

    return result;
}

void m2ts_demux_reset(M2TS_DEMUX *p)
{
    if (p) {
        PES_BUFFER *buf = _flush(p);
        pes_buffer_free(&buf);
    }
}

/*
 *
 */

M2TS_DEMUX *m2ts_demux_init(uint16_t pid)
{
    M2TS_DEMUX *p = calloc(1, sizeof(*p));

    if (p) {
        p->pid = pid;
    }

    return p;
}

void m2ts_demux_free(M2TS_DEMUX **p)
{
    if (p && *p) {
        m2ts_demux_reset(*p);
        X_FREE(*p);
    }
}

/*
 *
 */

static int _realloc(PES_BUFFER *p, size_t size)
{
    uint8_t *tmp = realloc(p->buf, size);

    if (!tmp) {
        BD_DEBUG(DBG_DECODE | DBG_CRIT, "out of memory\n");
        return -1;
    }

    p->size = size;
    p->buf = tmp;

    return 0;
}

static int _add_ts(PES_BUFFER *p, uint8_t *buf, unsigned len)
{
    // realloc
    if (p->size < p->len + len) {
        if (_realloc(p, p->size * 2) < 0) {
            return -1;
        }
    }

    // append
    memcpy(p->buf + p->len, buf, len);
    p->len += len;

    return 0;
}

/*
 * Parsing
 */

static int64_t _parse_timestamp(uint8_t *p)
{
    int64_t ts;
    ts  = ((int64_t)(p[0] & 0x0E)) << 29;
    ts |=  p[1]         << 22;
    ts |= (p[2] & 0xFE) << 14;
    ts |=  p[3]         <<  7;
    ts |= (p[4] & 0xFE) >>  1;
    return ts;
}

static int _parse_pes(PES_BUFFER *p, uint8_t *buf, unsigned len)
{
    unsigned hdr_len = 0;
    unsigned payload_len = 0;
    int parse = _parse_pes_header(p, buf, len, &hdr_len, &payload_len);
    int result = 0;

    if (parse > 0) {
        BD_DEBUG(DBG_DECODE, "invalid BDAV TS (PES header not in single TS packet)\n");
        return -2;
    }
    if (parse < 0) {
        return -1;
    }

    result = (int)payload_len;

    if (_realloc(p, BD_MAX(result, 0x100)) < 0) {
        return -1;
    }

    p->len = len - hdr_len;
    memcpy(p->buf, buf + hdr_len, p->len);

    return result;
}


/*
 *
 */

PES_BUFFER *m2ts_demux(M2TS_DEMUX *p, uint8_t *buf)
{
    uint8_t   *end = buf + 6144;
    PES_BUFFER *result = NULL;

    if (!buf) {
        return _flush(p);
    }

    for (; buf < end; buf += 192) {

        unsigned tp_error       = buf[4+1] & 0x80;
        unsigned pusi           = buf[4+1] & 0x40;
        uint16_t pid            = ((buf[4+1] & 0x1f) << 8) | buf[4+2];
        unsigned payload_exists = buf[4+3] & 0x10;
        int      payload_offset = (buf[4+3] & 0x20) ? buf[4+4] + 5 : 4;

        if (buf[4] != 0x47) {
            BD_DEBUG(DBG_DECODE, "missing sync byte. scrambled data ?\n");
            return NULL;
        }
        if (pid != p->pid) {
            M2TS_TRACE("skipping packet (pid %d)\n", pid);
            continue;
        }
        if (tp_error) {
            BD_DEBUG(DBG_DECODE, "skipping packet (transport error)\n");
            continue;
        }
        if (!payload_exists) {
            M2TS_TRACE("skipping packet (no payload)\n");
            continue;
        }
        if (payload_offset >= 188) {
            BD_DEBUG(DBG_DECODE, "skipping packet (invalid payload start address)\n");
            continue;
        }

        if (pusi) {
            _open3d_demux_trace(p->pid,
                                "pusi buf_present=%d buf_len=%u pes_length=%d payload_offset=%d",
                                p->buf ? 1 : 0,
                                p->buf ? p->buf->len : 0,
                                p->pes_length, payload_offset);
            _open3d_demux_trace_bytes(p->pid, "pusi_head",
                                      buf + 4 + payload_offset,
                                      (unsigned)(188 - payload_offset));
            if (p->buf) {
                if (p->raw_pes_pending) {
                    if (_finalize_raw_pes(p->buf) >= 0) {
                        _open3d_demux_trace(p->pid,
                                            "flush_raw len=%u pts=%" PRId64 " dts=%" PRId64,
                                            p->buf->len, p->buf->pts, p->buf->dts);
                        pes_buffer_append(&result, p->buf);
                    } else {
                        _open3d_demux_trace(p->pid, "drop_raw_finalize_fail len=%u",
                                            p->buf->len);
                        pes_buffer_free(&p->buf);
                    }
                    p->buf = NULL;
                    p->raw_pes_pending = 0;
                } else if (p->pes_length <= 0) {
                    /* Video PES may legitimately use unspecified length.
                     * In that case the next PUSI closes the current PES. */
                    _open3d_demux_trace(p->pid,
                                        "flush_unspecified len=%u pts=%" PRId64 " dts=%" PRId64,
                                        p->buf->len, p->buf->pts, p->buf->dts);
                    pes_buffer_append(&result, p->buf);
                    p->buf = NULL;
                } else {
                    _open3d_demux_trace(p->pid,
                                        "drop_mismatch len=%u expected=%d pts=%" PRId64 " dts=%" PRId64,
                                        p->buf->len, p->pes_length, p->buf->pts, p->buf->dts);
                    BD_DEBUG(DBG_DECODE, "PES length mismatch: have %d, expected %d\n",
                             p->buf->len, p->pes_length);
                    pes_buffer_free(&p->buf);
                }
            }
            p->buf = pes_buffer_alloc();
            if (!p->buf) {
                continue;
            }
            int r = _parse_pes(p->buf, buf + 4 + payload_offset, 188 - payload_offset);
            if (r < 0) {
                if (r == -2) {
                    if (_realloc(p->buf, BD_MAX(188 - payload_offset, 0x100)) < 0) {
                        pes_buffer_free(&p->buf);
                        continue;
                    }
                    p->buf->len = 188 - payload_offset;
                    memcpy(p->buf->buf, buf + 4 + payload_offset, p->buf->len);
                    p->buf->pts = 0;
                    p->buf->dts = 0;
                    p->pes_length = 0;
                    p->raw_pes_pending = 1;
                    _open3d_demux_trace(p->pid,
                                        "parse_partial payload_offset=%d initial_len=%u",
                                        payload_offset, p->buf->len);
                    continue;
                }
                _open3d_demux_trace(p->pid, "parse_fail payload_offset=%d", payload_offset);
                _open3d_demux_trace_bytes(p->pid, "parse_fail_head",
                                          buf + 4 + payload_offset,
                                          (unsigned)(188 - payload_offset));
                pes_buffer_free(&p->buf);
                continue;
            }
            p->pes_length = r;
            p->raw_pes_pending = 0;
            _open3d_demux_trace(p->pid,
                                "parse_ok pts=%" PRId64 " dts=%" PRId64
                                " pes_length=%d initial_len=%u",
                                p->buf->pts, p->buf->dts, p->pes_length, p->buf->len);

        } else {

            if (!p->buf) {
                _open3d_demux_trace(p->pid, "skip_no_pusi payload_offset=%d", payload_offset);
                BD_DEBUG(DBG_DECODE, "skipping packet (no pusi seen)\n");
                continue;
            }

            if (_add_ts(p->buf, buf + 4 + payload_offset, 188 - payload_offset) < 0) {
                _open3d_demux_trace(p->pid, "add_ts_fail payload_offset=%d", payload_offset);
                pes_buffer_free(&p->buf);
                continue;
            }
        }

        if (!p->raw_pes_pending && p->buf->len == p->pes_length) {
            M2TS_TRACE("PES complete (%d bytes)\n", p->pes_length);
            _open3d_demux_trace(p->pid,
                                "flush_exact len=%u pts=%" PRId64 " dts=%" PRId64,
                                p->buf->len, p->buf->pts, p->buf->dts);
            pes_buffer_append(&result, p->buf);
            p->buf = NULL;
        }
    }

    return result;
}
