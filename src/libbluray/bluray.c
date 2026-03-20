/*
 * This file is part of libbluray
 * Copyright (C) 2009-2010  Obliter0n
 * Copyright (C) 2009-2010  John Stebbins
 * Copyright (C) 2010-2019  Petri Hintukainen <phintuka@users.sourceforge.net>
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

#include "bluray-version.h"
#include "bluray.h"
#include "bluray_open3d_mvc.h"
#include "bluray_internal.h"
#include "keys.h"
#include "register.h"
#include "util/array.h"
#include "util/event_queue.h"
#include "util/macro.h"
#include "util/logging.h"
#include "util/strutl.h"
#include "util/mutex.h"
#include "bdnav/bdid_parse.h"
#include "bdnav/clpi_data.h"
#include "bdnav/clpi_parse.h"
#include "bdnav/navigation.h"
#include "bdnav/index_parse.h"
#include "bdnav/meta_parse.h"
#include "bdnav/meta_data.h"
#include "bdnav/sound_parse.h"
#include "bdnav/uo_mask.h"
#include "hdmv/hdmv_vm.h"
#include "hdmv/mobj_parse.h"
#include "decoders/graphics_controller.h"
#include "decoders/hdmv_pids.h"
#include "decoders/m2ts_demux.h"
#include "decoders/m2ts_filter.h"
#include "decoders/overlay.h"
#include "decoders/pes_buffer.h"
#include "disc/disc.h"
#include "disc/enc_info.h"
#include "file/file.h"
#include "bdj/bdj.h"
#include "bdj/bdjo_parse.h"

#include <stdio.h> // SEEK_
#include <stdarg.h>
#include <stdlib.h>
#include <inttypes.h>
#include <string.h>


typedef enum {
    title_undef = 0,
    title_hdmv,
    title_bdj,
} BD_TITLE_TYPE;

typedef struct {
    /* current clip */
    const NAV_CLIP *clip;
    BD_FILE_H      *fp;
    uint64_t       clip_size;
    uint64_t       clip_block_pos;
    uint64_t       clip_pos;

    /* current aligned unit */
    uint16_t       int_buf_off;

    /* current stream UO mask (combined from playlist and current clip UO masks) */
    BD_UO_MASK     uo_mask;

    /* internally handled pids */
    uint16_t        ig_pid; /* pid of currently selected IG stream */
    uint16_t        pg_pid; /* pid of currently selected PG stream */

    /* */
    uint8_t         eof_hit;
    uint8_t         encrypted_block_cnt;
    uint8_t         seek_flag;  /* used to fine-tune first read after seek */

    M2TS_FILTER    *m2ts_filter;
} BD_STREAM;

typedef struct {
    const NAV_CLIP *clip;
    size_t    clip_size;
    uint8_t  *buf;
} BD_PRELOAD;

typedef struct bd_open3d_mvc_unit_node_s BD_OPEN3D_MVC_UNIT_NODE;
struct bd_open3d_mvc_unit_node_s {
    BD_OPEN3D_MVC_UNIT      unit;
    uint8_t                *buf;
    BD_OPEN3D_MVC_UNIT_NODE *next;
};

typedef struct {
    uint8_t                valid;
    BD_OPEN3D_MVC_INFO     info;
    const NAV_CLIP        *dependent_clip;
    BD_STREAM              dep_st;
    M2TS_DEMUX            *base_demux;
    M2TS_DEMUX            *dep_demux;
    PES_BUFFER            *dep_queue;
    BD_OPEN3D_MVC_UNIT_NODE *unit_head;
    BD_OPEN3D_MVC_UNIT_NODE *unit_tail;
    int64_t                last_unit_base_time;
    int64_t                last_exact_base_time;
    uint8_t                strict_relock_exacts_needed;
    uint8_t                initial_base_only_aus;
    uint8_t                stale_relaxed_active;
    uint8_t                pending_hard_relock;
    uint8_t                seek_restart_pending;
    uint8_t                seek_restart_allow_non_idr;
    uint8_t                started;
    uint8_t               *startup_base_prefix;
    uint32_t               startup_base_prefix_len;
    uint8_t                startup_base_prefix_used;
    uint8_t               *startup_dep_prefix;
    uint32_t               startup_dep_prefix_len;
    uint8_t                startup_dep_prefix_used;
    uint8_t                dep_seek_pending;
    uint32_t               dep_seek_pkt;
    uint32_t               dep_seek_time;
    uint8_t                dep_int_buf[6144];
} BD_OPEN3D_MVC_RUNTIME;

struct bluray {

    BD_MUTEX          mutex;  /* protect API function access to internal data */

    /* current disc */
    BD_DISC          *disc;
    BLURAY_DISC_INFO  disc_info;
    BLURAY_TITLE    **titles;  /* titles from disc index */
    META_ROOT        *meta;
    NAV_TITLE_LIST   *title_list;

    /* current playlist */
    NAV_TITLE      *title;
    uint32_t       title_idx;
    uint64_t       s_pos;

    /* streams */
    BD_STREAM      st0;       /* main path */
    BD_PRELOAD     st_ig;     /* preloaded IG stream sub path */
    BD_PRELOAD     st_textst; /* preloaded TextST sub path */
    BD_OPEN3D_MVC_RUNTIME open3d_mvc;

    /* buffer for bd_read(): current aligned unit of main stream (st0) */
    uint8_t        int_buf[6144];

    /* seamless angle change request */
    int            seamless_angle_change;
    uint32_t       angle_change_pkt;
    uint32_t       angle_change_time;
    unsigned       request_angle;

    /* mark tracking */
    uint64_t       next_mark_pos;
    int            next_mark;

    /* player state */
    BD_REGISTERS   *regs;            /* player registers */
    BD_EVENT_QUEUE *event_queue;     /* navigation mode event queue */
    BD_UO_MASK      uo_mask;         /* Current UO mask */
    BD_UO_MASK      title_uo_mask;   /* UO mask from current .bdjo file or Movie Object */
    BD_TITLE_TYPE   title_type;      /* type of current title (in navigation mode) */
    /* Pending action after playlist end
     * BD-J: delayed sending of BDJ_EVENT_END_OF_PLAYLIST
     *       1 - message pending. 3 - message sent.
     */
    uint8_t         end_of_playlist; /* 1 - reached. 3 - processed . */
    uint8_t         app_scr;         /* 1 if application provides presentation timetamps */
    uint8_t         uo_restriction_level; /* 0 to ignore UO restrictions */

    /* HDMV */
    HDMV_VM        *hdmv_vm;
    uint8_t         hdmv_suspended;
    uint8_t         hdmv_num_invalid_pl;

    /* BD-J */
    BDJAVA         *bdjava;
    BDJ_CONFIG      bdj_config;
    uint8_t         bdj_wait_start;  /* BD-J has selected playlist (prefetch) but not yet started playback */

    /* HDMV graphics */
    GRAPHICS_CONTROLLER *graphics_controller;
    SOUND_DATA          *sound_effects;
    BD_UO_MASK           gc_uo_mask;      /* UO mask from current menu page */
    uint32_t             gc_status;
    uint8_t              decode_pg;

    /* TextST */
    uint32_t gc_wakeup_time;  /* stream timestamp of next subtitle */
    uint64_t gc_wakeup_pos;   /* stream position of gc_wakeup_time */

    /* ARGB overlay output */
    void                *argb_overlay_proc_handle;
    bd_argb_overlay_proc_f argb_overlay_proc;
    BD_ARGB_BUFFER      *argb_buffer;
    BD_MUTEX             argb_buffer_mutex;
};

static void _close_m2ts(BD_STREAM *st);
static int  _open_m2ts(BLURAY *bd, BD_STREAM *st);
static int  _read_block(BLURAY *bd, BD_STREAM *st, uint8_t *buf);
static void _open3d_mvc_trace(const char *fmt, ...);
static int _open3d_mvc_contains_nal_type(const uint8_t *buf, uint32_t len,
                                         uint8_t nal_type);
static int64_t _seek_stream(BLURAY *bd, BD_STREAM *st,
                            const NAV_CLIP *clip, uint32_t clip_pkt);

/* Stream Packet Number = byte offset / 192. Avoid 64-bit division. */
#define SPN(pos) (((uint32_t)((pos) >> 6)) / 3)


/*
 * Library version
 */
void bd_get_version(int *major, int *minor, int *micro)
{
    *major = BLURAY_VERSION_MAJOR;
    *minor = BLURAY_VERSION_MINOR;
    *micro = BLURAY_VERSION_MICRO;
}

/*
 * Navigation mode event queue
 */

const char *bd_event_name(uint32_t event)
{
  switch ((bd_event_e)event) {
#define EVENT_ENTRY(e) case e : return & (#e [9])
        EVENT_ENTRY(BD_EVENT_NONE);
        EVENT_ENTRY(BD_EVENT_ERROR);
        EVENT_ENTRY(BD_EVENT_READ_ERROR);
        EVENT_ENTRY(BD_EVENT_ENCRYPTED);
        EVENT_ENTRY(BD_EVENT_ANGLE);
        EVENT_ENTRY(BD_EVENT_TITLE);
        EVENT_ENTRY(BD_EVENT_PLAYLIST);
        EVENT_ENTRY(BD_EVENT_PLAYITEM);
        EVENT_ENTRY(BD_EVENT_CHAPTER);
        EVENT_ENTRY(BD_EVENT_PLAYMARK);
        EVENT_ENTRY(BD_EVENT_END_OF_TITLE);
        EVENT_ENTRY(BD_EVENT_AUDIO_STREAM);
        EVENT_ENTRY(BD_EVENT_IG_STREAM);
        EVENT_ENTRY(BD_EVENT_PG_TEXTST_STREAM);
        EVENT_ENTRY(BD_EVENT_PIP_PG_TEXTST_STREAM);
        EVENT_ENTRY(BD_EVENT_SECONDARY_AUDIO_STREAM);
        EVENT_ENTRY(BD_EVENT_SECONDARY_VIDEO_STREAM);
        EVENT_ENTRY(BD_EVENT_PG_TEXTST);
        EVENT_ENTRY(BD_EVENT_PIP_PG_TEXTST);
        EVENT_ENTRY(BD_EVENT_SECONDARY_AUDIO);
        EVENT_ENTRY(BD_EVENT_SECONDARY_VIDEO);
        EVENT_ENTRY(BD_EVENT_SECONDARY_VIDEO_SIZE);
        EVENT_ENTRY(BD_EVENT_PLAYLIST_STOP);
        EVENT_ENTRY(BD_EVENT_DISCONTINUITY);
        EVENT_ENTRY(BD_EVENT_SEEK);
        EVENT_ENTRY(BD_EVENT_STILL);
        EVENT_ENTRY(BD_EVENT_STILL_TIME);
        EVENT_ENTRY(BD_EVENT_SOUND_EFFECT);
        EVENT_ENTRY(BD_EVENT_IDLE);
        EVENT_ENTRY(BD_EVENT_POPUP);
        EVENT_ENTRY(BD_EVENT_MENU);
        EVENT_ENTRY(BD_EVENT_STEREOSCOPIC_STATUS);
        EVENT_ENTRY(BD_EVENT_KEY_INTEREST_TABLE);
        EVENT_ENTRY(BD_EVENT_UO_MASK_CHANGED);
#undef EVENT_ENTRY
    }
    return NULL;
}

static int _get_event(BLURAY *bd, BD_EVENT *ev)
{
    int result = event_queue_get(bd->event_queue, ev);
    if (!result) {
        ev->event = BD_EVENT_NONE;
    }
    return result;
}

static int _queue_event(BLURAY *bd, uint32_t event, uint32_t param)
{
    int result = 0;
    if (bd->event_queue) {
        BD_EVENT ev = { event, param };
        result = event_queue_put(bd->event_queue, &ev);
        if (!result) {
            const char *name = bd_event_name(event);
            BD_DEBUG(DBG_BLURAY|DBG_CRIT, "_queue_event(%s:%d, %d): queue overflow !\n", name ? name : "?", event, param);
        }
    }
    return result;
}

/*
 * PSR utils
 */

static void _update_time_psr(BLURAY *bd, uint32_t time)
{
    /*
     * Update PSR8: Presentation Time
     * The PSR8 represents presentation time in the playing interval from IN_time until OUT_time of
     * the current PlayItem, measured in units of a 45 kHz clock.
     */

    if (!bd->title || !bd->st0.clip) {
        return;
    }
    if (time < bd->st0.clip->in_time) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_update_time_psr(): timestamp before clip start\n");
        return;
    }
    if (time > bd->st0.clip->out_time) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_update_time_psr(): timestamp after clip end\n");
        return;
    }

    bd_psr_write(bd->regs, PSR_TIME, time);
}

static uint32_t _update_time_psr_from_stream(BLURAY *bd)
{
    /* update PSR_TIME from stream. Not real presentation time (except when seeking), but near enough. */
    const NAV_CLIP *clip = bd->st0.clip;

    if (bd->title && clip) {

        uint32_t clip_pkt, clip_time;
        nav_clip_packet_search(bd->st0.clip, SPN(bd->st0.clip_pos), &clip_pkt, &clip_time);
        if (clip_time >= clip->in_time && clip_time <= clip->out_time) {
            _update_time_psr(bd, clip_time);
            return clip_time;
        } else {
            BD_DEBUG(DBG_BLURAY|DBG_CRIT, "%s: no timestamp for SPN %u (got %u). clip %u-%u.\n",
                     clip->name, SPN(bd->st0.clip_pos), clip_time, clip->in_time, clip->out_time);
        }
    }

    return 0;
}

static void _update_stream_psr_by_lang(BD_REGISTERS *regs,
                                       uint32_t psr_lang, uint32_t psr_stream,
                                       uint32_t enable_flag,
                                       const MPLS_STREAM *streams, unsigned num_streams,
                                       uint32_t *lang, uint32_t blacklist)
{
    uint32_t preferred_lang;
    int      stream_idx = -1;
    unsigned ii;
    uint32_t stream_lang = 0;

    /* get preferred language */
    preferred_lang = bd_psr_read(regs, psr_lang);

    /* find stream */
    for (ii = 0; ii < num_streams; ii++) {
        if (preferred_lang == str_to_uint32((const char *)streams[ii].lang, 3)) {
            stream_idx = ii;
            break;
        }
    }

    /* requested language not found ? */
    if (stream_idx < 0) {
        BD_DEBUG(DBG_BLURAY, "Stream with preferred language not found\n");
        /* select first stream */
        stream_idx = 0;
        /* no subtitles if preferred language not found */
        enable_flag = 0;
    }

    stream_lang = str_to_uint32((const char *)streams[stream_idx].lang, 3);

    /* avoid enabling subtitles if audio is in the same language */
    if (blacklist && blacklist == stream_lang) {
        enable_flag = 0;
        BD_DEBUG(DBG_BLURAY, "Subtitles disabled (audio is in the same language)\n");
    }

    if (lang) {
        *lang = stream_lang;
    }

    /* update PSR */

    BD_DEBUG(DBG_BLURAY, "Selected stream %d (language %s)\n", stream_idx, streams[stream_idx].lang);

    bd_psr_write_bits(regs, psr_stream,
                      (stream_idx + 1) | enable_flag,
                      0x80000fff);
}

static void _update_clip_psrs(BLURAY *bd, const NAV_CLIP *clip)
{
    const MPLS_STN *stn = &clip->title->pl->play_item[clip->ref].stn;
    uint32_t audio_lang = 0;
    uint32_t psr_val;

    bd_psr_write(bd->regs, PSR_PLAYITEM, clip->ref);
    bd_psr_write(bd->regs, PSR_TIME,     clip->in_time);

    /* Validate selected audio, subtitle and IG stream PSRs */
    if (stn->num_audio) {
        bd_psr_lock(bd->regs);
        psr_val = bd_psr_read(bd->regs, PSR_PRIMARY_AUDIO_ID);
        if (psr_val == 0 || psr_val > stn->num_audio) {
            _update_stream_psr_by_lang(bd->regs,
                                       PSR_AUDIO_LANG, PSR_PRIMARY_AUDIO_ID, 0,
                                       stn->audio, stn->num_audio,
                                       &audio_lang, 0);
        } else {
            audio_lang = str_to_uint32((const char *)stn->audio[psr_val - 1].lang, 3);
        }
        bd_psr_unlock(bd->regs);
    }
    if (stn->num_pg) {
        bd_psr_lock(bd->regs);
        psr_val = bd_psr_read(bd->regs, PSR_PG_STREAM) & 0xfff;
        if ((psr_val == 0) || (psr_val > stn->num_pg)) {
            _update_stream_psr_by_lang(bd->regs,
                                       PSR_PG_AND_SUB_LANG, PSR_PG_STREAM, 0x80000000,
                                       stn->pg, stn->num_pg,
                                       NULL, audio_lang);
        }
        bd_psr_unlock(bd->regs);
    }
    if (stn->num_ig && bd->title_type != title_undef) {
        bd_psr_lock(bd->regs);
        psr_val = bd_psr_read(bd->regs, PSR_IG_STREAM_ID);
        if ((psr_val == 0) || (psr_val > stn->num_ig)) {
            bd_psr_write(bd->regs, PSR_IG_STREAM_ID, 1);
            BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Selected IG stream 1 (stream %d not available)\n", psr_val);
        }
        bd_psr_unlock(bd->regs);
    }
}

static void _update_playlist_psrs(BLURAY *bd)
{
    const NAV_CLIP *clip = bd->st0.clip;

    bd_psr_write(bd->regs, PSR_PLAYLIST, atoi(bd->title->name));
    bd_psr_write(bd->regs, PSR_ANGLE_NUMBER, bd->title->angle + 1);
    bd_psr_write(bd->regs, PSR_CHAPTER, 0xffff);

    if (clip && bd->title_type == title_undef) {
        /* Initialize selected audio and subtitle stream PSRs when not using menus.
         * Selection is based on language setting PSRs and clip STN.
         */
        const MPLS_STN *stn = &clip->title->pl->play_item[clip->ref].stn;
        uint32_t audio_lang = 0;

        /* make sure clip is up-to-date before STREAM events are triggered */
        bd_psr_write(bd->regs, PSR_PLAYITEM, clip->ref);

        if (stn->num_audio) {
            _update_stream_psr_by_lang(bd->regs,
                                       PSR_AUDIO_LANG, PSR_PRIMARY_AUDIO_ID, 0,
                                       stn->audio, stn->num_audio,
                                       &audio_lang, 0);
        }

        if (stn->num_pg) {
            _update_stream_psr_by_lang(bd->regs,
                                       PSR_PG_AND_SUB_LANG, PSR_PG_STREAM, 0x80000000,
                                       stn->pg, stn->num_pg,
                                       NULL, audio_lang);
        }
    }
}

static int _is_interactive_title(BLURAY *bd)
{
    if (bd->titles && bd->title_type != title_undef) {
        unsigned title = bd_psr_read(bd->regs, PSR_TITLE_NUMBER);
        if (title == BLURAY_TITLE_FIRST_PLAY && bd->disc_info.first_play->interactive) {
            return 1;
        }
        if (title <= bd->disc_info.num_titles && bd->titles[title]) {
            return bd->titles[title]->interactive;
        }
    }
    return 0;
}

static void _update_chapter_psr(BLURAY *bd)
{
    if (!_is_interactive_title(bd) && bd->title->chap_list.count > 0) {
        uint32_t current_chapter = bd_get_current_chapter(bd);
        bd_psr_write(bd->regs, PSR_CHAPTER,  current_chapter + 1);
    }
}

/*
 * PG
 */

static int _find_pg_stream(BLURAY *bd, uint16_t *pid, int *sub_path_idx, unsigned *sub_clip_idx, uint8_t *char_code)
{
    unsigned  main_clip_idx = bd->st0.clip ? bd->st0.clip->ref : 0;
    unsigned  pg_stream = bd_psr_read(bd->regs, PSR_PG_STREAM);
    const MPLS_STN *stn = &bd->title->pl->play_item[main_clip_idx].stn;

#if 0
    /* Enable decoder unconditionally (required for forced subtitles).
       Display flag is checked in graphics controller. */
    /* check PG display flag from PSR */
    if (!(pg_stream & 0x80000000)) {
      return 0;
    }
#endif

    pg_stream &= 0xfff;

    if (pg_stream > 0 && pg_stream <= stn->num_pg) {
        pg_stream--; /* stream number to table index */
        if (stn->pg[pg_stream].stream_type == 2) {
            *sub_path_idx = stn->pg[pg_stream].subpath_id;
            *sub_clip_idx = stn->pg[pg_stream].subclip_id;
        }
        *pid = stn->pg[pg_stream].pid;

        if (char_code && stn->pg[pg_stream].coding_type == BLURAY_STREAM_TYPE_SUB_TEXT) {
            *char_code = stn->pg[pg_stream].char_code;
        }

        BD_DEBUG(DBG_BLURAY, "_find_pg_stream(): current PG stream pid 0x%04x sub-path %d\n",
              *pid, *sub_path_idx);
        return 1;
    }

    return 0;
}

static int _init_pg_stream(BLURAY *bd)
{
    int      pg_subpath = -1;
    unsigned pg_subclip = 0;
    uint16_t pg_pid     = 0;

    bd->st0.pg_pid = 0;

    if (!bd->graphics_controller) {
        return 0;
    }

    /* reset PG decoder and controller */
    gc_run(bd->graphics_controller, GC_CTRL_PG_RESET, 0, NULL);

    if (!bd->decode_pg || !bd->title) {
        return 0;
    }

    _find_pg_stream(bd, &pg_pid, &pg_subpath, &pg_subclip, NULL);

    /* store PID of main path embedded PG stream */
    if (pg_subpath < 0) {
        bd->st0.pg_pid = pg_pid;
        return !!pg_pid;
    }

    return 0;
}

static void _update_textst_timer(BLURAY *bd)
{
    if (bd->st_textst.clip) {
        if (bd->st0.clip_block_pos >= bd->gc_wakeup_pos) {
            GC_NAV_CMDS cmds = {-1, NULL, -1, 0, 0, EMPTY_UO_MASK};

            gc_run(bd->graphics_controller, GC_CTRL_PG_UPDATE, bd->gc_wakeup_time, &cmds);

            bd->gc_wakeup_time = cmds.wakeup_time;
            bd->gc_wakeup_pos = (uint64_t)(int64_t)-1; /* no wakeup */

            /* next event in this clip ? */
            if (cmds.wakeup_time >= bd->st0.clip->in_time && cmds.wakeup_time < bd->st0.clip->out_time) {
                /* find event position in main path clip */
                const NAV_CLIP *clip = bd->st0.clip;
                if (clip->cl) {
                    uint32_t spn;
                    nav_clip_time_search(clip, cmds.wakeup_time, &spn, NULL);
                    if (spn) {
                        bd->gc_wakeup_pos = (uint64_t)spn * 192L;
                  }
                }
            }
        }
    }
}

static void _init_textst_timer(BLURAY *bd)
{
    if (bd->st_textst.clip && bd->st0.clip->cl) {
        uint32_t clip_time, clip_pkt;
        nav_clip_packet_search(bd->st0.clip, SPN(bd->st0.clip_block_pos), &clip_pkt, &clip_time);
        bd->gc_wakeup_time = clip_time;
        bd->gc_wakeup_pos = 0;
        _update_textst_timer(bd);
    }
}

static void _open3d_copy_clip_id(char dst[6], const char *src)
{
    memset(dst, 0, 6);
    if (!src) {
        return;
    }
    memcpy(dst, src, strnlen(src, 5));
}

static void _open3d_mvc_free_unit_queue(BD_OPEN3D_MVC_RUNTIME *mvc)
{
    while (mvc && mvc->unit_head) {
        BD_OPEN3D_MVC_UNIT_NODE *node = mvc->unit_head;
        mvc->unit_head = node->next;
        X_FREE(node->buf);
        X_FREE(node);
    }

    if (mvc) {
        mvc->unit_tail = NULL;
    }
}

static void _open3d_mvc_flush_sidecar(BD_OPEN3D_MVC_RUNTIME *mvc)
{
    if (!mvc) {
        return;
    }

    _close_m2ts(&mvc->dep_st);
    m2ts_demux_free(&mvc->base_demux);
    m2ts_demux_free(&mvc->dep_demux);
    pes_buffer_free(&mvc->dep_queue);
    _open3d_mvc_free_unit_queue(mvc);
    X_FREE(mvc->startup_base_prefix);
    mvc->startup_base_prefix_len = 0;
    mvc->startup_base_prefix_used = 0;
    X_FREE(mvc->startup_dep_prefix);
    mvc->startup_dep_prefix_len = 0;
    mvc->startup_dep_prefix_used = 0;
    mvc->last_unit_base_time = (int64_t)-1;
    mvc->last_exact_base_time = (int64_t)-1;
    mvc->strict_relock_exacts_needed = 0;
    mvc->stale_relaxed_active = 0;
    mvc->pending_hard_relock = 0;
    mvc->seek_restart_pending = 0;
    mvc->seek_restart_allow_non_idr = 0;
    mvc->dependent_clip = NULL;
}

static void _open3d_mvc_reset_runtime(BLURAY *bd)
{
    if (bd) {
        _open3d_mvc_flush_sidecar(&bd->open3d_mvc);
        memset(&bd->open3d_mvc, 0, sizeof(bd->open3d_mvc));
    }
}

static int _open3d_mvc_runtime_matches_current(BLURAY *bd, unsigned main_playitem_index)
{
    if (!bd || !bd->open3d_mvc.valid || !bd->st0.clip) {
        return 0;
    }

    if (bd->open3d_mvc.info.playitem_index != (int32_t)main_playitem_index) {
        return 0;
    }

    if (strncmp(bd->open3d_mvc.info.base_clip_id, bd->st0.clip->name, 5)) {
        return 0;
    }

    return 1;
}

static unsigned _open3d_current_playitem_index(BLURAY *bd)
{
    return bd->st0.clip ? bd->st0.clip->ref : 0;
}

static const MPLS_STREAM *_open3d_pick_base_stream(const MPLS_STN *stn)
{
    unsigned ii;

    if (!stn) {
        return NULL;
    }

    for (ii = 0; ii < stn->num_video; ii++) {
        if (stn->video[ii].coding_type == BLURAY_STREAM_TYPE_VIDEO_H264) {
            return &stn->video[ii];
        }
    }
    if (stn->num_video > 0) {
        return &stn->video[0];
    }

    return NULL;
}

static const MPLS_STREAM *_open3d_pick_dependent_stream(const MPLS_STN *stn)
{
    unsigned ii;

    if (!stn) {
        return NULL;
    }

    for (ii = 0; ii < stn->num_secondary_video; ii++) {
        if (stn->secondary_video[ii].coding_type == 0x20) {
            return &stn->secondary_video[ii];
        }
    }
    if (stn->num_secondary_video > 0) {
        return &stn->secondary_video[0];
    }

    return NULL;
}

static uint16_t _open3d_pick_base_pid_from_clip(const NAV_CLIP *clip)
{
    unsigned ii;

    if (!clip || !clip->cl) {
        return 0;
    }

    for (ii = 0; ii < clip->cl->program.num_prog; ii++) {
        const CLPI_PROG *prog = &clip->cl->program.progs[ii];
        unsigned jj;

        for (jj = 0; jj < prog->num_streams; jj++) {
            if (prog->streams[jj].coding_type == BLURAY_STREAM_TYPE_VIDEO_H264) {
                return prog->streams[jj].pid;
            }
        }
        for (jj = 0; jj < prog->num_streams; jj++) {
            switch (prog->streams[jj].coding_type) {
                case BLURAY_STREAM_TYPE_VIDEO_MPEG1:
                case BLURAY_STREAM_TYPE_VIDEO_MPEG2:
                case BLURAY_STREAM_TYPE_VIDEO_VC1:
                case BLURAY_STREAM_TYPE_VIDEO_H264:
                case BLURAY_STREAM_TYPE_VIDEO_HEVC:
                    return prog->streams[jj].pid;
                default:
                    break;
            }
        }
    }

    return 0;
}

static uint16_t _open3d_pick_dependent_pid_from_clip(const NAV_CLIP *clip)
{
    unsigned ii;

    if (!clip || !clip->cl) {
        return 0;
    }

    for (ii = 0; ii < clip->cl->program_ss.num_prog; ii++) {
        const CLPI_PROG *prog = &clip->cl->program_ss.progs[ii];
        unsigned jj;

        for (jj = 0; jj < prog->num_streams; jj++) {
            if (prog->streams[jj].coding_type == 0x20) {
                return prog->streams[jj].pid;
            }
        }
        if (prog->num_streams > 0) {
            return prog->streams[0].pid;
        }
    }

    return 0;
}

static uint16_t _open3d_pick_base_pid(const MPLS_STN *stn, const NAV_CLIP *clip)
{
    const MPLS_STREAM *stream = _open3d_pick_base_stream(stn);

    if (stream && stream->pid) {
        return stream->pid;
    }

    return _open3d_pick_base_pid_from_clip(clip);
}

static uint16_t _open3d_pick_dependent_pid(const MPLS_STN *stn, const NAV_CLIP *clip)
{
    const MPLS_STREAM *stream = _open3d_pick_dependent_stream(stn);

    if (stream && stream->pid) {
        return stream->pid;
    }

    return _open3d_pick_dependent_pid_from_clip(clip);
}

static int _open3d_fill_resolved_clip(BD_OPEN3D_MVC_INFO *info,
                                      const NAV_SUB_PATH *nav_sub_path,
                                      const MPLS_SUB *mpls_sub_path,
                                      uint8_t subpath_kind,
                                      unsigned subpath_index,
                                      unsigned subclip_index,
                                      const NAV_CLIP **resolved_clip)
{
    const MPLS_SUB_PI *spi;
    const NAV_CLIP *clip;

    if (!info || !nav_sub_path || !mpls_sub_path) {
        return 0;
    }
    if (subclip_index >= nav_sub_path->clip_list.count ||
        subclip_index >= mpls_sub_path->sub_playitem_count) {
        return 0;
    }

    spi = &mpls_sub_path->sub_play_item[subclip_index];
    clip = &nav_sub_path->clip_list.clip[subclip_index];

    info->subpath_kind = subpath_kind;
    info->subpath_type = nav_sub_path->type;
    info->sync_play_item_id = spi->sync_play_item_id;
    info->sync_pts = spi->sync_pts;
    info->subpath_index = subpath_index;
    info->subclip_index = subclip_index;

    if (spi->clip && spi->clip[0].clip_id[0]) {
        _open3d_copy_clip_id(info->dependent_clip_id, spi->clip[0].clip_id);
    } else {
        _open3d_copy_clip_id(info->dependent_clip_id, clip->name);
    }
    if (resolved_clip) {
        *resolved_clip = clip;
    }

    return 1;
}

static int _open3d_try_resolve_subpath_exact(BD_OPEN3D_MVC_INFO *info,
                                             const MPLS_STREAM *dep_stream,
                                             const NAV_SUB_PATH *nav_sub_paths,
                                             unsigned nav_sub_path_count,
                                             const MPLS_SUB *mpls_sub_paths,
                                             unsigned mpls_sub_path_count,
                                             uint8_t subpath_kind,
                                             const NAV_CLIP **resolved_clip)
{
    unsigned subpath_index;
    unsigned subclip_index;

    if (!info || !dep_stream || !nav_sub_paths || !mpls_sub_paths) {
        return 0;
    }

    subpath_index = dep_stream->subpath_id;
    subclip_index = dep_stream->subclip_id;

    if (subpath_index >= nav_sub_path_count || subpath_index >= mpls_sub_path_count) {
        return 0;
    }
    if (nav_sub_paths[subpath_index].type != mpls_sub_path_ss_video ||
        mpls_sub_paths[subpath_index].type != mpls_sub_path_ss_video) {
        return 0;
    }

    return _open3d_fill_resolved_clip(info,
                                      &nav_sub_paths[subpath_index],
                                      &mpls_sub_paths[subpath_index],
                                      subpath_kind,
                                      subpath_index,
                                      subclip_index,
                                      resolved_clip);
}

static int _open3d_try_resolve_subpath_scan(BD_OPEN3D_MVC_INFO *info,
                                            unsigned main_playitem_index,
                                            const NAV_SUB_PATH *nav_sub_paths,
                                            unsigned nav_sub_path_count,
                                            const MPLS_SUB *mpls_sub_paths,
                                            unsigned mpls_sub_path_count,
                                            uint8_t subpath_kind,
                                            const NAV_CLIP **resolved_clip)
{
    unsigned ss;
    unsigned ii;

    if (!info || !nav_sub_paths || !mpls_sub_paths) {
        return 0;
    }

    for (ss = 0; ss < nav_sub_path_count && ss < mpls_sub_path_count; ss++) {
        if (nav_sub_paths[ss].type != mpls_sub_path_ss_video ||
            mpls_sub_paths[ss].type != mpls_sub_path_ss_video) {
            continue;
        }

        for (ii = 0; ii < nav_sub_paths[ss].clip_list.count &&
                      ii < mpls_sub_paths[ss].sub_playitem_count; ii++) {
            if (mpls_sub_paths[ss].sub_play_item[ii].sync_play_item_id != main_playitem_index) {
                continue;
            }

            return _open3d_fill_resolved_clip(info,
                                              &nav_sub_paths[ss],
                                              &mpls_sub_paths[ss],
                                              subpath_kind,
                                              ss,
                                              ii,
                                              resolved_clip);
        }
    }

    return 0;
}

static int _open3d_mvc_refresh_runtime_locked(BLURAY *bd)
{
    BD_OPEN3D_MVC_INFO *info;
    const MPLS_PI *pi;
    const MPLS_STREAM *dep_stream;
    const NAV_CLIP *base_clip;
    const NAV_CLIP *resolved_dep_clip = NULL;
    unsigned main_playitem_index;

    if (!bd || !bd->title || !bd->title->pl || bd->title->pl->list_count < 1) {
        return 0;
    }

    main_playitem_index = _open3d_current_playitem_index(bd);

    if (_open3d_mvc_runtime_matches_current(bd, main_playitem_index)) {
        return 1;
    }

    _open3d_mvc_reset_runtime(bd);

    if (main_playitem_index >= bd->title->pl->list_count ||
        main_playitem_index >= bd->title->clip_list.count) {
        return 0;
    }

    pi = &bd->title->pl->play_item[main_playitem_index];
    base_clip = &bd->title->clip_list.clip[main_playitem_index];
    dep_stream = _open3d_pick_dependent_stream(&pi->stn);

    info = &bd->open3d_mvc.info;
    info->playitem_index = (int32_t)main_playitem_index;
    info->base_pid = _open3d_pick_base_pid(&pi->stn, base_clip);

    if (pi->clip && pi->clip[0].clip_id[0]) {
        _open3d_copy_clip_id(info->base_clip_id, pi->clip[0].clip_id);
    } else {
        _open3d_copy_clip_id(info->base_clip_id, base_clip->name);
    }

    if (_open3d_try_resolve_subpath_exact(info, dep_stream,
                                          bd->title->sub_path, bd->title->sub_path_count,
                                          bd->title->pl->sub_path, bd->title->pl->sub_count,
                                          BD_OPEN3D_MVC_SUBPATH_NORMAL,
                                          &resolved_dep_clip) ||
        _open3d_try_resolve_subpath_exact(info, dep_stream,
                                          bd->title->ext_sub_path, bd->title->ext_sub_path_count,
                                          bd->title->pl->ext_sub_path, bd->title->pl->ext_sub_count,
                                          BD_OPEN3D_MVC_SUBPATH_EXTENSION,
                                          &resolved_dep_clip) ||
        _open3d_try_resolve_subpath_scan(info, main_playitem_index,
                                         bd->title->sub_path, bd->title->sub_path_count,
                                         bd->title->pl->sub_path, bd->title->pl->sub_count,
                                         BD_OPEN3D_MVC_SUBPATH_NORMAL,
                                         &resolved_dep_clip) ||
        _open3d_try_resolve_subpath_scan(info, main_playitem_index,
                                         bd->title->ext_sub_path, bd->title->ext_sub_path_count,
                                         bd->title->pl->ext_sub_path, bd->title->pl->ext_sub_count,
                                         BD_OPEN3D_MVC_SUBPATH_EXTENSION,
                                         &resolved_dep_clip)) {
        info->dependent_pid = _open3d_pick_dependent_pid(&pi->stn, resolved_dep_clip);
        info->available = 1;
        bd->open3d_mvc.dependent_clip = resolved_dep_clip;
    }

    bd->open3d_mvc.valid = 1;
    _open3d_mvc_trace("assembler=lav playitem=%u base=%s dep=%s available=%u",
                      main_playitem_index,
                      info->base_clip_id,
                      info->dependent_clip_id,
                      info->available);
    return 1;
}

static uint32_t _open3d_clpi_lookup_spn_cpi(const CLPI_CL *cl,
                                            const CLPI_CPI *cpi,
                                            uint32_t timestamp,
                                            int before,
                                            uint8_t stc_id)
{
    const CLPI_EP_MAP_ENTRY *entry;
    int ii, jj;
    uint32_t coarse_pts, pts;
    uint32_t spn, coarse_spn, stc_spn;
    int start, end;
    int ref;

    if (!cl || !cpi || cpi->num_stream_pid < 1 || !cpi->entry) {
        if (before) {
            return 0;
        }
        return cl ? cl->clip.num_source_packets : 0;
    }

    entry = &cpi->entry[0];

    stc_spn = clpi_find_stc_spn(cl, stc_id);
    for (ii = 0; ii < entry->num_ep_coarse; ii++) {
        ref = entry->coarse[ii].ref_ep_fine_id;
        if (entry->coarse[ii].spn_ep >= stc_spn) {
            break;
        }
    }
    if (ii >= entry->num_ep_coarse) {
        return cl->clip.num_source_packets;
    }
    pts = ((uint64_t)(entry->coarse[ii].pts_ep & ~0x01) << 18) +
          ((uint64_t)entry->fine[ref].pts_ep << 8);
    if (pts > timestamp && ii) {
        ii--;
        coarse_pts = (uint32_t)(entry->coarse[ii].pts_ep & ~0x01) << 18;
        coarse_spn = entry->coarse[ii].spn_ep;
        start = entry->coarse[ii].ref_ep_fine_id;
        end = entry->coarse[ii + 1].ref_ep_fine_id;
        for (jj = start; jj < end; jj++) {
            pts = coarse_pts + ((uint32_t)entry->fine[jj].pts_ep << 8);
            spn = (coarse_spn & ~0x1FFFF) + entry->fine[jj].spn_ep;
            if (stc_spn >= spn && pts > timestamp) {
                break;
            }
        }
        goto done;
    }

    start = ii;
    for (ii = start; ii < entry->num_ep_coarse; ii++) {
        ref = entry->coarse[ii].ref_ep_fine_id;
        pts = ((uint64_t)(entry->coarse[ii].pts_ep & ~0x01) << 18) +
              ((uint64_t)entry->fine[ref].pts_ep << 8);
        if (pts > timestamp) {
            break;
        }
    }
    if (ii == 0) {
        return 0;
    }
    ii--;
    coarse_pts = (uint32_t)(entry->coarse[ii].pts_ep & ~0x01) << 18;
    start = entry->coarse[ii].ref_ep_fine_id;
    if (ii < entry->num_ep_coarse - 1) {
        end = entry->coarse[ii + 1].ref_ep_fine_id;
    } else {
        end = entry->num_ep_fine;
    }
    for (jj = start; jj < end; jj++) {
        pts = coarse_pts + ((uint32_t)entry->fine[jj].pts_ep << 8);
        if (pts > timestamp) {
            break;
        }
    }

done:
    if (jj == start && ii == 0) {
        return 0;
    }
    if (jj == end) {
        jj--;
    } else if (pts > timestamp && before) {
        if (jj > start) {
            jj--;
        } else if (ii > 0) {
            ii--;
            start = entry->coarse[ii].ref_ep_fine_id;
            if (ii < entry->num_ep_coarse - 1) {
                end = entry->coarse[ii + 1].ref_ep_fine_id;
            } else {
                end = entry->num_ep_fine;
            }
            jj = end - 1;
        }
    }

    return (entry->coarse[ii].spn_ep & ~0x1FFFF) + entry->fine[jj].spn_ep;
}

static uint32_t _open3d_mvc_lookup_dependent_spn(const NAV_CLIP *clip,
                                                 uint32_t timestamp,
                                                 uint8_t stc_id)
{
    if (!clip || !clip->cl) {
        return 0;
    }

    if (clip->cl->cpi_ss.num_stream_pid > 0 && clip->cl->cpi_ss.entry) {
        return _open3d_clpi_lookup_spn_cpi(clip->cl, &clip->cl->cpi_ss,
                                           timestamp, 1, stc_id);
    }

    return clpi_lookup_spn(clip->cl, timestamp, 1, stc_id);
}

static const MPLS_SUB_PI *_open3d_mvc_get_subplayitem_locked(BLURAY *bd,
                                                             const BD_OPEN3D_MVC_RUNTIME *mvc,
                                                             uint8_t *dep_stc_id)
{
    const MPLS_SUB *mpls_sub_path;

    if (!bd || !mvc || !bd->title || !bd->title->pl) {
        return NULL;
    }

    if (mvc->info.subpath_kind == BD_OPEN3D_MVC_SUBPATH_NORMAL) {
        if (mvc->info.subpath_index >= bd->title->pl->sub_count) {
            return NULL;
        }
        mpls_sub_path = &bd->title->pl->sub_path[mvc->info.subpath_index];
    } else if (mvc->info.subpath_kind == BD_OPEN3D_MVC_SUBPATH_EXTENSION) {
        if (mvc->info.subpath_index >= bd->title->pl->ext_sub_count) {
            return NULL;
        }
        mpls_sub_path = &bd->title->pl->ext_sub_path[mvc->info.subpath_index];
    } else {
        return NULL;
    }

    if (mvc->info.subclip_index >= mpls_sub_path->sub_playitem_count) {
        return NULL;
    }

    if (dep_stc_id) {
        *dep_stc_id = 0;
        if (mpls_sub_path->sub_play_item[mvc->info.subclip_index].clip &&
            mpls_sub_path->sub_play_item[mvc->info.subclip_index].clip_count > 0) {
            *dep_stc_id =
                mpls_sub_path->sub_play_item[mvc->info.subclip_index].clip[0].stc_id;
        }
    }

    return &mpls_sub_path->sub_play_item[mvc->info.subclip_index];
}

#define OPEN3D_MVC_SEEK_CANDIDATE_MAX_APS 16
#define OPEN3D_MVC_SEEK_CANDIDATE_MAX_BLOCKS 256
#define OPEN3D_MVC_SEEK_CANDIDATE_MAX_PES 24
#define OPEN3D_MVC_SEEK_CANDIDATE_MAX_DELTA 900000

static int _open3d_mvc_probe_base_seek_candidate_locked(BLURAY *bd,
                                                        const NAV_CLIP *base_clip,
                                                        uint32_t base_clip_pkt,
                                                        uint8_t *out_has_idr,
                                                        uint8_t *out_has_sps,
                                                        uint8_t *out_has_pps)
{
    BD_STREAM probe_st;
    NAV_CLIP probe_clip;
    M2TS_DEMUX *probe_demux = NULL;
    uint8_t block[6144];
    uint8_t has_idr = 0;
    uint8_t has_sps = 0;
    uint8_t has_pps = 0;
    unsigned pes_seen = 0;
    unsigned blocks_read = 0;
    int ok = 0;

    if (out_has_idr) {
        *out_has_idr = 0;
    }
    if (out_has_sps) {
        *out_has_sps = 0;
    }
    if (out_has_pps) {
        *out_has_pps = 0;
    }

    if (!bd || !base_clip || !base_clip->cl ||
        !_open3d_mvc_refresh_runtime_locked(bd) ||
        !bd->open3d_mvc.info.base_pid) {
        return 0;
    }

    memset(&probe_st, 0, sizeof(probe_st));
    memset(&probe_clip, 0, sizeof(probe_clip));
    probe_clip = *base_clip;
    probe_st.clip = &probe_clip;

    if (!_open_m2ts(bd, &probe_st)) {
        return 0;
    }

    probe_demux = m2ts_demux_init(bd->open3d_mvc.info.base_pid);
    if (!probe_demux) {
        _close_m2ts(&probe_st);
        return 0;
    }

    if (_seek_stream(bd, &probe_st, base_clip, base_clip_pkt) < 0) {
        goto out;
    }

    while (blocks_read < OPEN3D_MVC_SEEK_CANDIDATE_MAX_BLOCKS &&
           pes_seen < OPEN3D_MVC_SEEK_CANDIDATE_MAX_PES &&
           !ok) {
        PES_BUFFER *base_list;
        int r = _read_block(bd, &probe_st, block);
        if (r <= 0) {
            break;
        }

        blocks_read++;
        base_list = m2ts_demux(probe_demux, block);
        while (base_list) {
            PES_BUFFER *base = base_list;
            base_list = base->next;
            base->next = NULL;

            has_idr |= _open3d_mvc_contains_nal_type(base->buf, base->len, 5) ? 1 : 0;
            has_sps |= _open3d_mvc_contains_nal_type(base->buf, base->len, 7) ? 1 : 0;
            has_pps |= _open3d_mvc_contains_nal_type(base->buf, base->len, 8) ? 1 : 0;
            pes_seen++;

            if (has_idr && has_sps && has_pps) {
                ok = 1;
            }

            pes_buffer_free(&base);
            if (ok || pes_seen >= OPEN3D_MVC_SEEK_CANDIDATE_MAX_PES) {
                break;
            }
        }

        if (base_list) {
            pes_buffer_free(&base_list);
        }
    }

out:
    if (out_has_idr) {
        *out_has_idr = has_idr;
    }
    if (out_has_sps) {
        *out_has_sps = has_sps;
    }
    if (out_has_pps) {
        *out_has_pps = has_pps;
    }

    _open3d_mvc_trace("seek_candidate_probe clip=%s pkt=%u pes=%u blocks=%u"
                      " idr=%u sps=%u pps=%u ok=%d",
                      base_clip->name, base_clip_pkt, pes_seen, blocks_read,
                      has_idr, has_sps, has_pps, ok);

    m2ts_demux_free(&probe_demux);
    _close_m2ts(&probe_st);
    return ok;
}

static uint32_t _open3d_mvc_select_base_seek_candidate_locked(BLURAY *bd,
                                                              const NAV_CLIP *base_clip,
                                                              uint32_t base_clip_pkt)
{
    uint32_t candidate_pkt;
    uint32_t first_candidate_time = 0;
    uint32_t candidate_time = 0;
    uint32_t next_pkt;
    uint32_t next_time = 0;
    int saw_non_idr_restart = 0;
    unsigned ii;

    if (!bd || !base_clip || !base_clip->cl ||
        !_open3d_mvc_refresh_runtime_locked(bd) ||
        !bd->open3d_mvc.info.available) {
        return base_clip_pkt;
    }

    candidate_pkt = clpi_access_point(base_clip->cl, base_clip_pkt, 0, 0, &candidate_time);
    if (candidate_pkt < base_clip->start_pkt) {
        candidate_pkt = base_clip->start_pkt;
        candidate_time = base_clip->in_time;
    }
    if (candidate_pkt > base_clip->end_pkt) {
        candidate_pkt = base_clip->end_pkt;
        candidate_time = base_clip->out_time;
    }
    first_candidate_time = candidate_time;

    for (ii = 0; ii < OPEN3D_MVC_SEEK_CANDIDATE_MAX_APS; ii++) {
        uint8_t has_idr = 0;
        uint8_t has_sps = 0;
        uint8_t has_pps = 0;

        if (_open3d_mvc_probe_base_seek_candidate_locked(bd, base_clip, candidate_pkt,
                                                         &has_idr, &has_sps, &has_pps)) {
            bd->open3d_mvc.seek_restart_allow_non_idr = 0;
            if (candidate_pkt != base_clip_pkt) {
                _open3d_mvc_trace("seek_candidate_select clip=%s requested_pkt=%u"
                                  " selected_pkt=%u selected_time=%u step=%u",
                                  base_clip->name, base_clip_pkt,
                                  candidate_pkt, candidate_time, ii);
            }
            return candidate_pkt;
        }
        if (!has_idr && has_sps && has_pps) {
            saw_non_idr_restart = 1;
        }

        next_pkt = clpi_access_point(base_clip->cl, candidate_pkt + 1, 1, 0, &next_time);
        if (next_pkt <= candidate_pkt || next_pkt > base_clip->end_pkt) {
            break;
        }
        if (next_time > first_candidate_time &&
            next_time - first_candidate_time > OPEN3D_MVC_SEEK_CANDIDATE_MAX_DELTA) {
            break;
        }

        _open3d_mvc_trace("seek_candidate_skip clip=%s requested_pkt=%u"
                          " candidate_pkt=%u candidate_time=%u next_pkt=%u next_time=%u"
                          " step=%u",
                          base_clip->name, base_clip_pkt,
                          candidate_pkt, candidate_time,
                          next_pkt, next_time, ii);
        candidate_pkt = next_pkt;
        candidate_time = next_time;
    }

    bd->open3d_mvc.seek_restart_allow_non_idr = saw_non_idr_restart ? 1 : 0;
    if (saw_non_idr_restart) {
        _open3d_mvc_trace("seek_candidate_non_idr_fallback clip=%s requested_pkt=%u",
                          base_clip->name, base_clip_pkt);
    }

    return base_clip_pkt;
}

static void _open3d_mvc_prepare_dep_seek_locked(BLURAY *bd,
                                                const NAV_CLIP *base_clip,
                                                uint32_t base_clip_pkt)
{
    BD_OPEN3D_MVC_RUNTIME *mvc;
    const MPLS_SUB_PI *dep_spi;
    uint8_t dep_stc_id = 0;
    uint32_t base_time = 0;
    uint32_t dep_tick;
    uint32_t dep_pkt;

    if (!bd || !base_clip || !base_clip->cl) {
        return;
    }

    if (!_open3d_mvc_refresh_runtime_locked(bd)) {
        return;
    }

    mvc = &bd->open3d_mvc;
    if (!mvc->info.available || !mvc->dependent_clip || !mvc->dependent_clip->cl) {
        return;
    }

    dep_spi = _open3d_mvc_get_subplayitem_locked(bd, mvc, &dep_stc_id);
    if (!dep_spi) {
        return;
    }

    dep_tick = dep_spi->in_time;
    dep_pkt = mvc->dependent_clip->start_pkt;

    if (clpi_access_point(base_clip->cl, base_clip_pkt, 0, 0, &base_time) <
        base_clip->cl->clip.num_source_packets) {
        if (base_time >= dep_spi->sync_pts) {
            uint32_t delta = base_time - dep_spi->sync_pts;
            if (delta > dep_spi->out_time - dep_spi->in_time) {
                dep_tick = dep_spi->out_time;
            } else {
                dep_tick = dep_spi->in_time + delta;
            }
        }

        if (dep_tick < dep_spi->in_time) {
            dep_tick = dep_spi->in_time;
        }
        if (dep_tick > dep_spi->out_time) {
            dep_tick = dep_spi->out_time;
        }

        dep_pkt = _open3d_mvc_lookup_dependent_spn(mvc->dependent_clip,
                                                   dep_tick, dep_stc_id);
        if (dep_pkt < mvc->dependent_clip->start_pkt) {
            dep_pkt = mvc->dependent_clip->start_pkt;
        }
        if (dep_pkt > mvc->dependent_clip->end_pkt) {
            dep_pkt = mvc->dependent_clip->end_pkt;
        }
    }

    mvc->dep_seek_pkt = dep_pkt;
    mvc->dep_seek_time = dep_tick;
    mvc->dep_seek_pending = 1;
    mvc->seek_restart_pending = 1;
    _open3d_mvc_trace("dep_seek_prepare base_clip=%s base_pkt=%u base_time=%u"
                      " dep_clip=%s dep_pkt=%u dep_time=%u sync_pts=%u dep_stc_id=%u",
                      base_clip->name, base_clip_pkt, base_time,
                      mvc->dependent_clip->name, dep_pkt, dep_tick,
                      dep_spi->sync_pts, dep_stc_id);
}

#define OPEN3D_MVC_DEP_FILL_BLOCKS 256
#define OPEN3D_MVC_DEP_EMPTY_FILL_BLOCKS 1024
#define OPEN3D_MVC_RELAXED_CLOCK_WINDOW 4500
#define OPEN3D_MVC_STARTUP_RELAXED_PTS_WINDOW 4500
#define OPEN3D_MVC_WEAK_RELOCK_GAP 45000
#define OPEN3D_MVC_INITIAL_BASE_ONLY_WARMUP_AUS 1
#define OPEN3D_MVC_STARTUP_EXACT_RUNWAY 2
#define OPEN3D_MVC_SEEK_STARTUP_EXACT_RUNWAY 4
#define OPEN3D_MVC_RELOCK_EXACT_RUNWAY 3
#define OPEN3D_MVC_SAFE_RELAXED_FOLLOW_WINDOW 120000
#define OPEN3D_MVC_INVALID_TS ((int64_t)-1)

static int _open3d_mvc_trace_enabled(void)
{
    static int trace_enabled = -1;

    if (trace_enabled < 0) {
        const char *env = getenv("OPEN3D_LIBBLURAY_MVC_TRACE");
        trace_enabled = (env && env[0] && strcmp(env, "0")) ? 1 : 0;
    }

    return trace_enabled;
}

static int _open3d_mvc_trace_pes_enabled(void)
{
    static int trace_enabled = -1;

    if (trace_enabled < 0) {
        const char *env = getenv("OPEN3D_LIBBLURAY_MVC_TRACE_PES");
        trace_enabled = (env && env[0] && strcmp(env, "0")) ? 1 : 0;
    }

    return trace_enabled;
}

static int _open3d_mvc_trace_pes_seq_enabled(void)
{
    static int trace_enabled = -1;

    if (trace_enabled < 0) {
        const char *env = getenv("OPEN3D_LIBBLURAY_MVC_TRACE_PES_SEQ");
        trace_enabled = (env && env[0] && strcmp(env, "0")) ? 1 : 0;
    }

    return trace_enabled;
}

static int64_t _open3d_mvc_trace_min_time(void)
{
    static int initialized = 0;
    static int64_t min_time = 0;

    if (!initialized) {
        const char *env = getenv("OPEN3D_LIBBLURAY_MVC_TRACE_MIN_TIME");
        if (env && env[0]) {
            min_time = strtoll(env, NULL, 10);
        }
        initialized = 1;
    }

    return min_time;
}

static void _open3d_mvc_trace(const char *fmt, ...)
{
    va_list ap;

    if (!_open3d_mvc_trace_enabled()) {
        return;
    }

    fprintf(stderr, "open3d_libbluray_mvc: ");
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
}

static void _open3d_mvc_format_nal_seq(const uint8_t *buf, uint32_t len,
                                       char *dst, size_t dst_size)
{
    uint32_t ii = 0;
    size_t used = 0;
    int first = 1;

    if (!dst || !dst_size) {
        return;
    }

    dst[0] = '\0';
    if (!buf || len < 4) {
        return;
    }

    while (ii + 3 < len && used + 8 < dst_size) {
        uint32_t sc = 0;
        uint32_t payload;
        uint8_t nal_type;
        int wrote;

        if (buf[ii] == 0x00 && buf[ii + 1] == 0x00) {
            if (buf[ii + 2] == 0x01) {
                sc = 3;
            } else if (ii + 3 < len &&
                       buf[ii + 2] == 0x00 && buf[ii + 3] == 0x01) {
                sc = 4;
            }
        }
        if (!sc) {
            ii++;
            continue;
        }

        payload = ii + sc;
        if (payload >= len) {
            break;
        }

        nal_type = buf[payload] & 0x1f;
        wrote = snprintf(dst + used, dst_size - used, "%s%u@%u",
                         first ? "" : ",", nal_type, ii);
        if (wrote < 0 || (size_t)wrote >= dst_size - used) {
            used = dst_size - 1;
            break;
        }

        used += (size_t)wrote;
        first = 0;
        ii = payload + 1;
    }

    dst[used] = '\0';
}

static int64_t _open3d_mvc_pes_time(const PES_BUFFER *pes)
{
    if (!pes) {
        return OPEN3D_MVC_INVALID_TS;
    }

    if (pes->dts > 0) {
        return pes->dts;
    }
    if (pes->pts > 0) {
        return pes->pts;
    }

    return OPEN3D_MVC_INVALID_TS;
}

static int64_t _open3d_mvc_pes_emit_time(const PES_BUFFER *pes)
{
    if (!pes) {
        return OPEN3D_MVC_INVALID_TS;
    }

    if (pes->pts > 0) {
        return pes->pts;
    }

    return _open3d_mvc_pes_time(pes);
}

static int64_t _open3d_mvc_abs64(int64_t value)
{
    return value < 0 ? -value : value;
}

static int _open3d_mvc_nal_type_is_vcl(uint8_t nal_type)
{
    return nal_type >= 1 && nal_type <= 5;
}

static int _open3d_mvc_find_first_nal_type_offset(const uint8_t *buf, uint32_t len,
                                                  uint8_t target_type, uint32_t *out_off)
{
    uint32_t ii = 0;

    if (!buf || len < 4 || !out_off) {
        return 0;
    }

    while (ii + 3 < len) {
        uint32_t sc_len = 0;
        uint32_t payload;

        if (buf[ii] == 0x00 && buf[ii + 1] == 0x00 && buf[ii + 2] == 0x01) {
            sc_len = 3;
        } else if (ii + 4 < len &&
                   buf[ii] == 0x00 && buf[ii + 1] == 0x00 &&
                   buf[ii + 2] == 0x00 && buf[ii + 3] == 0x01) {
            sc_len = 4;
        }

        if (!sc_len) {
            ii++;
            continue;
        }

        payload = ii + sc_len;
        if (payload >= len) {
            break;
        }

        if ((buf[payload] & 0x1f) == target_type) {
            *out_off = ii;
            return 1;
        }

        ii = payload;
    }

    return 0;
}

static int _open3d_mvc_find_first_start_code_offset(const uint8_t *buf, uint32_t len,
                                                    uint32_t *out_off)
{
    uint32_t ii;

    if (!buf || len < 4 || !out_off) {
        return 0;
    }

    for (ii = 0; ii + 3 < len; ii++) {
        if (buf[ii] == 0x00 && buf[ii + 1] == 0x00) {
            if (buf[ii + 2] == 0x01) {
                *out_off = ii;
                return 1;
            }
            if (ii + 3 < len &&
                buf[ii + 2] == 0x00 && buf[ii + 3] == 0x01) {
                *out_off = ii;
                return 1;
            }
        }
    }

    return 0;
}

static int _open3d_mvc_contains_nal_type(const uint8_t *buf, uint32_t len,
                                         uint8_t target_type)
{
    uint32_t off = 0;
    return _open3d_mvc_find_first_nal_type_offset(buf, len, target_type, &off);
}

static int _open3d_mvc_dep_prefix_nal_allowed(uint8_t nal_type)
{
    switch (nal_type) {
        case 6:
        case 8:
        case 9:
        case 14:
        case 15:
        case 24:
            return 1;
        default:
            return 0;
    }
}

static int _open3d_mvc_dep_startup_ctx_nal_allowed(uint8_t nal_type)
{
    switch (nal_type) {
        case 6:
        case 8:
        case 14:
        case 15:
            return 1;
        default:
            return 0;
    }
}

static int _open3d_mvc_base_startup_ctx_nal_allowed(uint8_t nal_type)
{
    switch (nal_type) {
        case 6:
        case 7:
        case 8:
        case 9:
            return 1;
        default:
            return 0;
    }
}

static void _open3d_mvc_cache_base_startup_prefix(BD_OPEN3D_MVC_RUNTIME *mvc,
                                                  const PES_BUFFER *base)
{
    uint32_t type5_off = 0;
    uint32_t total = 0;
    uint32_t ii;
    uint8_t *buf;
    uint32_t off = 0;

    if (!mvc || !base || mvc->startup_base_prefix || base->len < 4) {
        return;
    }

    if (!_open3d_mvc_contains_nal_type(base->buf, base->len, 7) ||
        !_open3d_mvc_contains_nal_type(base->buf, base->len, 8) ||
        !_open3d_mvc_find_first_nal_type_offset(base->buf, base->len, 5, &type5_off) ||
        type5_off >= base->len) {
        return;
    }

    for (ii = 0; ii + 3 < type5_off; ) {
        uint32_t sc_len = 0;
        uint32_t payload;
        uint32_t next = base->len;
        uint8_t nal_type;

        if (base->buf[ii] == 0x00 && base->buf[ii + 1] == 0x00 &&
            base->buf[ii + 2] == 0x01) {
            sc_len = 3;
        } else if (ii + 4 < type5_off &&
                   base->buf[ii] == 0x00 && base->buf[ii + 1] == 0x00 &&
                   base->buf[ii + 2] == 0x00 && base->buf[ii + 3] == 0x01) {
            sc_len = 4;
        }
        if (!sc_len) {
            ii++;
            continue;
        }

        payload = ii + sc_len;
        if (payload >= type5_off) {
            break;
        }
        for (next = payload; next + 3 < type5_off; ++next) {
            if (base->buf[next] == 0x00 && base->buf[next + 1] == 0x00 &&
                (base->buf[next + 2] == 0x01 ||
                 (next + 3 < type5_off &&
                  base->buf[next + 2] == 0x00 &&
                  base->buf[next + 3] == 0x01))) {
                break;
            }
        }

        nal_type = base->buf[payload] & 0x1f;
        if (_open3d_mvc_base_startup_ctx_nal_allowed(nal_type)) {
            total += (next - ii);
        }
        ii = payload + 1;
    }

    if (!total) {
        return;
    }

    buf = malloc(total);
    if (!buf) {
        return;
    }

    for (ii = 0; ii + 3 < type5_off; ) {
        uint32_t sc_len = 0;
        uint32_t payload;
        uint32_t next = base->len;
        uint8_t nal_type;

        if (base->buf[ii] == 0x00 && base->buf[ii + 1] == 0x00 &&
            base->buf[ii + 2] == 0x01) {
            sc_len = 3;
        } else if (ii + 4 < type5_off &&
                   base->buf[ii] == 0x00 && base->buf[ii + 1] == 0x00 &&
                   base->buf[ii + 2] == 0x00 && base->buf[ii + 3] == 0x01) {
            sc_len = 4;
        }
        if (!sc_len) {
            ii++;
            continue;
        }

        payload = ii + sc_len;
        if (payload >= type5_off) {
            break;
        }
        for (next = payload; next + 3 < type5_off; ++next) {
            if (base->buf[next] == 0x00 && base->buf[next + 1] == 0x00 &&
                (base->buf[next + 2] == 0x01 ||
                 (next + 3 < type5_off &&
                  base->buf[next + 2] == 0x00 &&
                  base->buf[next + 3] == 0x01))) {
                break;
            }
        }

        nal_type = base->buf[payload] & 0x1f;
        if (_open3d_mvc_base_startup_ctx_nal_allowed(nal_type)) {
            memcpy(buf + off, base->buf + ii, next - ii);
            off += (next - ii);
        }
        ii = payload + 1;
    }

    mvc->startup_base_prefix = buf;
    mvc->startup_base_prefix_len = off;
    _open3d_mvc_trace("cache_base_startup_prefix pts=%" PRId64
                      " dts=%" PRId64 " len=%u",
                      base->pts, base->dts, off);
}

static void _open3d_mvc_cache_dep_startup_prefix(BD_OPEN3D_MVC_RUNTIME *mvc,
                                                 const PES_BUFFER *dep)
{
    uint32_t type20_off = 0;
    uint32_t total = 0;
    uint32_t ii;
    uint8_t *buf;
    uint32_t off = 0;

    if (!mvc || !dep || mvc->startup_dep_prefix || dep->len < 4) {
        return;
    }

    if (!_open3d_mvc_contains_nal_type(dep->buf, dep->len, 15) ||
        !_open3d_mvc_find_first_nal_type_offset(dep->buf, dep->len, 20, &type20_off) ||
        type20_off >= dep->len) {
        return;
    }

    for (ii = 0; ii + 3 < type20_off; ) {
        uint32_t sc_len = 0;
        uint32_t payload;
        uint32_t next = dep->len;
        uint8_t nal_type;

        if (dep->buf[ii] == 0x00 && dep->buf[ii + 1] == 0x00 &&
            dep->buf[ii + 2] == 0x01) {
            sc_len = 3;
        } else if (ii + 4 < type20_off &&
                   dep->buf[ii] == 0x00 && dep->buf[ii + 1] == 0x00 &&
                   dep->buf[ii + 2] == 0x00 && dep->buf[ii + 3] == 0x01) {
            sc_len = 4;
        }
        if (!sc_len) {
            ii++;
            continue;
        }

        payload = ii + sc_len;
        if (payload >= type20_off) {
            break;
        }
        for (next = payload; next + 3 < type20_off; ++next) {
            if (dep->buf[next] == 0x00 && dep->buf[next + 1] == 0x00 &&
                (dep->buf[next + 2] == 0x01 ||
                 (next + 3 < type20_off &&
                  dep->buf[next + 2] == 0x00 &&
                  dep->buf[next + 3] == 0x01))) {
                break;
            }
        }

        nal_type = dep->buf[payload] & 0x1f;
        if (_open3d_mvc_dep_startup_ctx_nal_allowed(nal_type)) {
            total += (next - ii);
        }
        ii = payload + 1;
    }

    if (!total) {
        return;
    }

    buf = malloc(total);
    if (!buf) {
        return;
    }

    for (ii = 0; ii + 3 < type20_off; ) {
        uint32_t sc_len = 0;
        uint32_t payload;
        uint32_t next = dep->len;
        uint8_t nal_type;

        if (dep->buf[ii] == 0x00 && dep->buf[ii + 1] == 0x00 &&
            dep->buf[ii + 2] == 0x01) {
            sc_len = 3;
        } else if (ii + 4 < type20_off &&
                   dep->buf[ii] == 0x00 && dep->buf[ii + 1] == 0x00 &&
                   dep->buf[ii + 2] == 0x00 && dep->buf[ii + 3] == 0x01) {
            sc_len = 4;
        }
        if (!sc_len) {
            ii++;
            continue;
        }

        payload = ii + sc_len;
        if (payload >= type20_off) {
            break;
        }
        for (next = payload; next + 3 < type20_off; ++next) {
            if (dep->buf[next] == 0x00 && dep->buf[next + 1] == 0x00 &&
                (dep->buf[next + 2] == 0x01 ||
                 (next + 3 < type20_off &&
                  dep->buf[next + 2] == 0x00 &&
                  dep->buf[next + 3] == 0x01))) {
                break;
            }
        }

        nal_type = dep->buf[payload] & 0x1f;
        if (_open3d_mvc_dep_startup_ctx_nal_allowed(nal_type)) {
            memcpy(buf + off, dep->buf + ii, next - ii);
            off += (next - ii);
        }
        ii = payload + 1;
    }

    mvc->startup_dep_prefix = buf;
    mvc->startup_dep_prefix_len = off;
    _open3d_mvc_trace("cache_dep_startup_prefix pts=%" PRId64
                      " dts=%" PRId64 " len=%u",
                      dep->pts, dep->dts, off);
}

static int _open3d_mvc_find_leading_sync_offset(const uint8_t *buf, uint32_t len,
                                                uint32_t *out_off)
{
    uint32_t nal_offs[128];
    uint8_t nal_types[128];
    uint32_t nal_count = 0;
    uint32_t ii = 0;
    const uint32_t window = 64;
    uint32_t nn;

    if (!buf || len < 4 || !out_off) {
        return 0;
    }

    while (ii + 3 < len && nal_count < (sizeof(nal_offs) / sizeof(nal_offs[0]))) {
        uint32_t sc_len = 0;
        uint32_t payload;

        if (buf[ii] == 0x00 && buf[ii + 1] == 0x00 && buf[ii + 2] == 0x01) {
            sc_len = 3;
        } else if (ii + 4 < len &&
                   buf[ii] == 0x00 && buf[ii + 1] == 0x00 &&
                   buf[ii + 2] == 0x00 && buf[ii + 3] == 0x01) {
            sc_len = 4;
        }

        if (!sc_len) {
            ii++;
            continue;
        }

        payload = ii + sc_len;
        if (payload >= len) {
            break;
        }

        nal_offs[nal_count] = ii;
        nal_types[nal_count] = buf[payload] & 0x1f;
        nal_count++;
        ii = payload;
    }

    if (!nal_count) {
        return 0;
    }

    if (nal_types[0] == 7 || nal_types[0] == 9) {
        *out_off = nal_offs[0];
        return 1;
    }

    for (nn = 0; nn < nal_count; nn++) {
        uint32_t jj;
        uint32_t hi;
        int has_pps = 0;
        int has_vcl = 0;

        if (nal_types[nn] != 7 && nal_types[nn] != 9) {
            continue;
        }

        hi = (nn + window < nal_count) ? (nn + window) : nal_count;
        for (jj = nn; jj < hi; jj++) {
            has_pps |= nal_types[jj] == 8;
            has_vcl |= _open3d_mvc_nal_type_is_vcl(nal_types[jj]);
            if (has_pps && has_vcl) {
                *out_off = nal_offs[nn];
                return 1;
            }
        }
    }

    for (nn = 0; nn < nal_count; nn++) {
        if (nal_types[nn] == 7) {
            *out_off = nal_offs[nn];
            return 1;
        }
    }

    return 0;
}

static int _open3d_mvc_find_dep_context_offset(const uint8_t *buf, uint32_t len,
                                               int strict_start,
                                               uint32_t *out_off)
{
    uint32_t start_off = 0;
    uint32_t type20_off = 0;
    uint32_t ii = 0;
    int saw_prefix = 0;

    if (!buf || !out_off || len < 4) {
        return 0;
    }

    if (!_open3d_mvc_find_first_start_code_offset(buf, len, &start_off) ||
        start_off >= len) {
        return 0;
    }

    if (_open3d_mvc_find_first_nal_type_offset(buf, len, 20, &type20_off) &&
        type20_off < len && type20_off >= start_off) {
        for (ii = start_off; ii + 3 < type20_off; ) {
            uint32_t sc_len = 0;
            uint32_t payload;
            uint8_t nal_type;

            if (buf[ii] == 0x00 && buf[ii + 1] == 0x00 && buf[ii + 2] == 0x01) {
                sc_len = 3;
            } else if (ii + 4 < type20_off &&
                       buf[ii] == 0x00 && buf[ii + 1] == 0x00 &&
                       buf[ii + 2] == 0x00 && buf[ii + 3] == 0x01) {
                sc_len = 4;
            }

            if (!sc_len) {
                ii++;
                continue;
            }

            payload = ii + sc_len;
            if (payload >= type20_off) {
                break;
            }

            nal_type = buf[payload] & 0x1f;
            if (!_open3d_mvc_dep_prefix_nal_allowed(nal_type)) {
                saw_prefix = 0;
                break;
            }

            saw_prefix = 1;
            ii = payload + 1;
        }

        if (saw_prefix) {
            *out_off = start_off;
            return 1;
        }

        if (strict_start) {
            *out_off = type20_off;
            return 1;
        }
    }

    if (_open3d_mvc_find_leading_sync_offset(buf, len, out_off) &&
        *out_off < len) {
        return 1;
    }
    if (_open3d_mvc_find_first_nal_type_offset(buf, len, 6, out_off) &&
        *out_off < len) {
        return 1;
    }
    if (_open3d_mvc_find_first_nal_type_offset(buf, len, 20, out_off) &&
        *out_off < len) {
        return 1;
    }

    *out_off = start_off;
    return 1;
}

static int _open3d_mvc_find_initial_dep_exact_offset(const uint8_t *buf, uint32_t len,
                                                     uint32_t *out_off)
{
    uint32_t type20_off = 0;
    uint32_t ii = 0;
    int saw_subset15 = 0;

    if (!buf || len < 4 || !out_off) {
        return 0;
    }

    if (!_open3d_mvc_find_first_nal_type_offset(buf, len, 20, &type20_off) ||
        type20_off >= len) {
        return 0;
    }

    while (ii + 3 < type20_off) {
        uint32_t sc_len = 0;
        uint32_t payload;
        uint8_t nal_type;

        if (buf[ii] == 0x00 && buf[ii + 1] == 0x00 && buf[ii + 2] == 0x01) {
            sc_len = 3;
        } else if (ii + 4 < type20_off &&
                   buf[ii] == 0x00 && buf[ii + 1] == 0x00 &&
                   buf[ii + 2] == 0x00 && buf[ii + 3] == 0x01) {
            sc_len = 4;
        }

        if (!sc_len) {
            ii++;
            continue;
        }

        payload = ii + sc_len;
        if (payload >= type20_off) {
            break;
        }

        nal_type = buf[payload] & 0x1f;
        if (nal_type == 15) {
            saw_subset15 = 1;
        } else if (saw_subset15 && nal_type == 6) {
            *out_off = ii;
            return 1;
        }

        ii = payload + 1;
    }

    return 0;
}

static int _open3d_mvc_prepare_match_offsets(const BD_OPEN3D_MVC_RUNTIME *mvc,
                                             const PES_BUFFER *base,
                                             const PES_BUFFER *dep,
                                             int initial_start,
                                             int relock_start,
                                             int hard_relock_start,
                                             uint32_t *base_off,
                                             uint32_t *dep_off)
{
    uint32_t dep_candidate = 0;
    uint32_t local_base_off = 0;
    uint32_t local_dep_off = 0;
    uint32_t base_type5_off = 0;
    uint32_t type20_off = 0;
    uint32_t initial_dep_trim_off = 0;
    int base_has_idr = 0;
    int base_has_sps = 0;
    int base_has_pps = 0;
    int allow_seek_non_idr = 0;
    int can_use_cached_base_prefix = 0;
    int dep_has_subset15 = 0;
    int can_use_cached_dep_prefix = 0;
    int initial_exact_start = initial_start && mvc->initial_base_only_aus == 0;

    if (!mvc || !base || !dep || !base_off || !dep_off) {
        return 0;
    }

    if (!_open3d_mvc_find_leading_sync_offset(base->buf, base->len, &local_base_off) ||
        local_base_off >= base->len) {
        return 0;
    }

    if (initial_start || hard_relock_start) {
        allow_seek_non_idr =
            initial_start &&
            mvc->seek_restart_pending &&
            mvc->seek_restart_allow_non_idr;
        base_has_idr =
            _open3d_mvc_contains_nal_type(base->buf + local_base_off,
                                          base->len - local_base_off, 5);
        base_has_sps =
            _open3d_mvc_contains_nal_type(base->buf + local_base_off,
                                          base->len - local_base_off, 7);
        base_has_pps =
            _open3d_mvc_contains_nal_type(base->buf + local_base_off,
                                          base->len - local_base_off, 8);
        can_use_cached_base_prefix =
            base_has_idr && !base_has_sps && !base_has_pps &&
            mvc->startup_base_prefix &&
            mvc->startup_base_prefix_len > 0 &&
            !mvc->startup_base_prefix_used &&
            _open3d_mvc_find_first_nal_type_offset(base->buf + local_base_off,
                                                   base->len - local_base_off,
                                                   5, &base_type5_off);
        if ((!base_has_idr && !allow_seek_non_idr) ||
            ((!base_has_sps || !base_has_pps) && !can_use_cached_base_prefix)) {
            return 0;
        }

        if (can_use_cached_base_prefix) {
            local_base_off += base_type5_off;
        }

        if (!_open3d_mvc_find_dep_context_offset(dep->buf, dep->len, 1, &dep_candidate) ||
            dep_candidate >= dep->len ||
            !_open3d_mvc_find_first_nal_type_offset(dep->buf + dep_candidate,
                                                    dep->len - dep_candidate,
                                                    20, &type20_off)) {
            return 0;
        }

        dep_has_subset15 =
            _open3d_mvc_contains_nal_type(dep->buf + dep_candidate,
                                          dep->len - dep_candidate, 15);
        can_use_cached_dep_prefix =
            !dep_has_subset15 &&
            mvc->startup_dep_prefix &&
            mvc->startup_dep_prefix_len > 0 &&
            !mvc->startup_dep_prefix_used;
        if (!dep_has_subset15 && !can_use_cached_dep_prefix) {
            return 0;
        }

        if (initial_exact_start &&
            dep_has_subset15 &&
            _open3d_mvc_find_initial_dep_exact_offset(dep->buf + dep_candidate,
                                                      dep->len - dep_candidate,
                                                      &initial_dep_trim_off)) {
            local_dep_off = dep_candidate + initial_dep_trim_off;
        } else {
            local_dep_off = dep_has_subset15 ? dep_candidate
                                             : (dep_candidate + type20_off);
        }
    } else if (relock_start) {
        if (!_open3d_mvc_find_dep_context_offset(dep->buf, dep->len, 1, &dep_candidate) ||
            dep_candidate >= dep->len ||
            !_open3d_mvc_contains_nal_type(dep->buf + dep_candidate,
                                           dep->len - dep_candidate, 20)) {
            return 0;
        }

        local_dep_off = dep_candidate;
    } else if (_open3d_mvc_find_dep_context_offset(dep->buf, dep->len, 0, &dep_candidate) &&
               dep_candidate < dep->len) {
        local_dep_off = dep_candidate;
    }

    if (local_dep_off >= dep->len) {
        return 0;
    }

    *base_off = local_base_off;
    *dep_off = local_dep_off;
    return 1;
}

static int _open3d_mvc_relaxed_window_match(const PES_BUFFER *base,
                                            const PES_BUFFER *dep)
{
    int64_t base_pts;
    int64_t dep_pts;

    if (!base || !dep) {
        return 0;
    }

    base_pts = base->pts > 0 ? base->pts : OPEN3D_MVC_INVALID_TS;
    dep_pts = dep->pts > 0 ? dep->pts : OPEN3D_MVC_INVALID_TS;

    if (base->dts <= 0 && base->pts > 0 && dep->dts > 0) {
        if (_open3d_mvc_abs64(base->pts - dep->dts) > OPEN3D_MVC_RELAXED_CLOCK_WINDOW) {
            return 0;
        }
        if (dep_pts > 0 &&
            _open3d_mvc_abs64(base_pts - dep_pts) > OPEN3D_MVC_RELAXED_CLOCK_WINDOW) {
            return 0;
        }
        return 1;
    }

    if (dep->dts <= 0 && dep->pts > 0 && base->dts > 0) {
        if (_open3d_mvc_abs64(dep->pts - base->dts) > OPEN3D_MVC_RELAXED_CLOCK_WINDOW) {
            return 0;
        }
        if (base_pts > 0 &&
            _open3d_mvc_abs64(dep_pts - base_pts) > OPEN3D_MVC_RELAXED_CLOCK_WINDOW) {
            return 0;
        }
        return 1;
    }

    return 0;
}

static int _open3d_mvc_dep_candidate_ready(const PES_BUFFER *base,
                                           const PES_BUFFER *dep)
{
    int64_t base_time;
    int64_t dep_time;

    if (!base || !dep) {
        return 0;
    }

    if (_open3d_mvc_relaxed_window_match(base, dep)) {
        return 1;
    }

    base_time = _open3d_mvc_pes_time(base);
    dep_time = _open3d_mvc_pes_time(dep);

    if (base_time <= 0 || dep_time <= 0 || dep_time >= base_time) {
        return 1;
    }

    return 0;
}

static void _open3d_mvc_trace_pes(const char *kind, const PES_BUFFER *pes)
{
    unsigned aud_count = 0;
    unsigned sei_count = 0;
    unsigned slice1_count = 0;
    unsigned slice20_count = 0;
    unsigned subset15_count = 0;
    unsigned prefix14_count = 0;
    unsigned type24_count = 0;
    unsigned nal_count = 0;
    const uint8_t *buf;
    uint32_t len;
    int64_t time;
    int64_t min_time;
    char seq_buf[1024];

    if (!_open3d_mvc_trace_pes_enabled() || !kind || !pes) {
        return;
    }

    time = _open3d_mvc_pes_time(pes);
    min_time = _open3d_mvc_trace_min_time();
    if (min_time > 0 && time > 0 && time < min_time) {
        return;
    }

    buf = pes->buf;
    len = pes->len;
    if (buf && len > 4) {
        uint32_t ii = 0;
        while (ii + 3 < len) {
            uint32_t sc = 0;
            uint8_t nal_type;
            if (buf[ii] == 0x00 && buf[ii + 1] == 0x00) {
                if (buf[ii + 2] == 0x01) {
                    sc = 3;
                } else if (ii + 3 < len && buf[ii + 2] == 0x00 && buf[ii + 3] == 0x01) {
                    sc = 4;
                }
            }
            if (!sc) {
                ii++;
                continue;
            }
            if (ii + sc >= len) {
                break;
            }

            nal_type = buf[ii + sc] & 0x1f;
            nal_count++;
            switch (nal_type) {
                case 1:
                    slice1_count++;
                    break;
                case 6:
                    sei_count++;
                    break;
                case 9:
                    aud_count++;
                    break;
                case 14:
                    prefix14_count++;
                    break;
                case 15:
                    subset15_count++;
                    break;
                case 20:
                    slice20_count++;
                    break;
                case 24:
                    type24_count++;
                    break;
                default:
                    break;
            }
            ii += sc;
        }
    }

    _open3d_mvc_trace("%s pts=%" PRId64 " dts=%" PRId64
                      " time=%" PRId64 " len=%u nals=%u aud=%u sei=%u"
                      " slice1=%u prefix14=%u subset15=%u slice20=%u type24=%u",
                      kind, pes->pts, pes->dts, time, pes->len,
                      nal_count, aud_count, sei_count,
                      slice1_count, prefix14_count, subset15_count,
                      slice20_count, type24_count);

    if (_open3d_mvc_trace_pes_seq_enabled()) {
        _open3d_mvc_format_nal_seq(buf, len, seq_buf, sizeof(seq_buf));
        _open3d_mvc_trace("%s_seq pts=%" PRId64 " dts=%" PRId64
                          " time=%" PRId64 " seq=[%s]",
                          kind, pes->pts, pes->dts, time, seq_buf);
    }
}

static uint32_t _open3d_mvc_match_flags(const PES_BUFFER *base,
                                        const PES_BUFFER *dep)
{
    int64_t base_time;
    int64_t dep_time;

    if (!base || !dep) {
        return BD_OPEN3D_MVC_UNIT_FLAG_NONE;
    }

    base_time = _open3d_mvc_pes_time(base);
    dep_time = _open3d_mvc_pes_time(dep);

    if (base_time > 0 && dep_time > 0) {
        if (_open3d_mvc_relaxed_window_match(base, dep)) {
            return BD_OPEN3D_MVC_UNIT_FLAG_MATCHED | BD_OPEN3D_MVC_UNIT_FLAG_RELAXED_DTS;
        }

        if (base_time != dep_time) {
            return BD_OPEN3D_MVC_UNIT_FLAG_NONE;
        }

        if (base->dts == dep->dts && base->dts > 0) {
            return BD_OPEN3D_MVC_UNIT_FLAG_MATCHED;
        }

        return BD_OPEN3D_MVC_UNIT_FLAG_MATCHED | BD_OPEN3D_MVC_UNIT_FLAG_RELAXED_DTS;
    }

    if (base->pts > 0 && dep->pts > 0 && base->pts == dep->pts) {
        return BD_OPEN3D_MVC_UNIT_FLAG_MATCHED | BD_OPEN3D_MVC_UNIT_FLAG_RELAXED_DTS;
    }

    if (_open3d_mvc_relaxed_window_match(base, dep)) {
        return BD_OPEN3D_MVC_UNIT_FLAG_MATCHED | BD_OPEN3D_MVC_UNIT_FLAG_RELAXED_DTS;
    }

    /* LAV-like fallback: when one side lacks a usable DTS/PTS surface, pair
     * the current dependent head with the current base unit rather than
     * degenerating into an extended BASE_ONLY tail. */
    if (base_time <= 0 || dep_time <= 0) {
        return BD_OPEN3D_MVC_UNIT_FLAG_MATCHED | BD_OPEN3D_MVC_UNIT_FLAG_RELAXED_DTS;
    }

    return BD_OPEN3D_MVC_UNIT_FLAG_NONE;
}

static int _open3d_mvc_queue_unit(BD_OPEN3D_MVC_RUNTIME *mvc,
                                  const PES_BUFFER *base,
                                  const PES_BUFFER *dep,
                                  uint32_t flags,
                                  uint32_t base_off,
                                  uint32_t dep_off,
                                  const uint8_t *base_prefix,
                                  uint32_t base_prefix_size,
                                  const uint8_t *dep_prefix,
                                  uint32_t dep_prefix_size)
{
    BD_OPEN3D_MVC_UNIT_NODE *node;
    uint32_t base_size;
    uint32_t dep_size;
    uint32_t merged_size;
    uint32_t off = 0;

    if (!mvc || !base || base_off > base->len || (dep && dep_off > dep->len)) {
        return 0;
    }

    base_size = base->len - base_off;
    dep_size = dep ? (dep->len - dep_off) : 0;

    node = calloc(1, sizeof(*node));
    if (!node) {
        return 0;
    }

    merged_size = base_prefix_size + base_size + dep_prefix_size + dep_size;
    if (merged_size > 0) {
        node->buf = malloc(merged_size);
        if (!node->buf) {
            X_FREE(node);
            return 0;
        }

        if (base_prefix && base_prefix_size > 0) {
            memcpy(node->buf + off, base_prefix, base_prefix_size);
            off += base_prefix_size;
        }
        memcpy(node->buf + off, base->buf + base_off, base_size);
        off += base_size;
        if (dep_prefix && dep_prefix_size > 0) {
            memcpy(node->buf + off, dep_prefix, dep_prefix_size);
            off += dep_prefix_size;
        }
        if (dep && dep_size > 0) {
            memcpy(node->buf + off, dep->buf + dep_off, dep_size);
        }
    }

    node->unit.flags = dep ? flags : BD_OPEN3D_MVC_UNIT_FLAG_BASE_ONLY;
    node->unit.base_size = base_prefix_size + base_size;
    node->unit.dependent_size = dep_prefix_size + dep_size;
    node->unit.merged_size = merged_size;
    node->unit.base_pts = base->pts;
    node->unit.base_dts = base->dts;
    node->unit.dependent_pts = dep ? dep->pts : 0;
    node->unit.dependent_dts = dep ? dep->dts : 0;

    if (!mvc->unit_head) {
        mvc->unit_head = node;
        mvc->unit_tail = node;
    } else {
        mvc->unit_tail->next = node;
        mvc->unit_tail = node;
    }

    mvc->last_unit_base_time = _open3d_mvc_pes_time(base);

    return 1;
}

static int _open3d_mvc_should_hold_weak_relock(const BD_OPEN3D_MVC_RUNTIME *mvc,
                                               const PES_BUFFER *base,
                                               const PES_BUFFER *dep,
                                               uint32_t flags)
{
    int64_t base_time;

    if (!mvc || !base || !dep) {
        return 0;
    }

    if (!(flags & BD_OPEN3D_MVC_UNIT_FLAG_MATCHED) ||
        !(flags & BD_OPEN3D_MVC_UNIT_FLAG_RELAXED_DTS)) {
        return 0;
    }

    if (base->dts > 0 && dep->dts > 0) {
        return 0;
    }

    base_time = _open3d_mvc_pes_time(base);
    if (base_time <= 0 || mvc->last_unit_base_time <= 0) {
        return 0;
    }

    if (base_time - mvc->last_unit_base_time <= OPEN3D_MVC_WEAK_RELOCK_GAP) {
        return 0;
    }

    return 1;
}

static int _open3d_mvc_should_drop_stale_relaxed(const BD_OPEN3D_MVC_RUNTIME *mvc,
                                                 const PES_BUFFER *base,
                                                 const PES_BUFFER *dep,
                                                 uint32_t flags)
{
    int64_t base_emit_time;
    int64_t dep_emit_time;

    if (!mvc || !base || !dep) {
        return 0;
    }

    if (!(flags & BD_OPEN3D_MVC_UNIT_FLAG_MATCHED) ||
        !(flags & BD_OPEN3D_MVC_UNIT_FLAG_RELAXED_DTS)) {
        return 0;
    }

    if (!mvc->stale_relaxed_active || mvc->last_exact_base_time <= 0) {
        return 0;
    }

    base_emit_time = _open3d_mvc_pes_emit_time(base);
    dep_emit_time = _open3d_mvc_pes_emit_time(dep);

    if ((base_emit_time > 0 && base_emit_time <= mvc->last_exact_base_time) ||
        (dep_emit_time > 0 && dep_emit_time <= mvc->last_exact_base_time)) {
        return 1;
    }

    return 0;
}

static int _open3d_mvc_is_safe_relaxed_follow(const BD_OPEN3D_MVC_RUNTIME *mvc,
                                              const PES_BUFFER *base,
                                              const PES_BUFFER *dep,
                                              uint32_t flags)
{
    int64_t base_emit_time;
    int64_t ref_time;

    if (!mvc || !base || !dep) {
        return 0;
    }

    if (!(flags & BD_OPEN3D_MVC_UNIT_FLAG_MATCHED) ||
        !(flags & BD_OPEN3D_MVC_UNIT_FLAG_RELAXED_DTS)) {
        return 0;
    }

    if (!mvc->started) {
        return 0;
    }

    if (base->dts > 0 || dep->dts > 0) {
        return 0;
    }

    if (base->pts <= 0 || dep->pts <= 0 || base->pts != dep->pts) {
        return 0;
    }

    base_emit_time = _open3d_mvc_pes_emit_time(base);
    if (base_emit_time <= 0) {
        return 0;
    }

    ref_time = mvc->last_exact_base_time > 0
        ? mvc->last_exact_base_time
        : mvc->last_unit_base_time;
    if (ref_time <= 0 || base_emit_time < ref_time) {
        return 0;
    }

    if (base_emit_time - ref_time > OPEN3D_MVC_SAFE_RELAXED_FOLLOW_WINDOW) {
        return 0;
    }

    return 1;
}

static int _open3d_mvc_is_strong_exact_match(const PES_BUFFER *base,
                                             const PES_BUFFER *dep,
                                             uint32_t flags)
{
    if (!base || !dep) {
        return 0;
    }

    if (!(flags & BD_OPEN3D_MVC_UNIT_FLAG_MATCHED) ||
        (flags & BD_OPEN3D_MVC_UNIT_FLAG_RELAXED_DTS)) {
        return 0;
    }

    if (base->dts <= 0 || dep->dts <= 0) {
        return 0;
    }

    return base->dts == dep->dts;
}

static PES_BUFFER *_open3d_mvc_take_startup_relaxed_dep(BD_OPEN3D_MVC_RUNTIME *mvc,
                                                        const PES_BUFFER *base,
                                                        uint32_t *out_flags)
{
    PES_BUFFER *best = NULL;
    PES_BUFFER *it;
    int64_t base_pts;
    int64_t best_delta = INT64_MAX;

    if (out_flags) {
        *out_flags = BD_OPEN3D_MVC_UNIT_FLAG_NONE;
    }

    if (!mvc || !base || !mvc->dep_queue) {
        return NULL;
    }

    base_pts = base->pts > 0 ? base->pts : _open3d_mvc_pes_emit_time(base);
    if (base_pts <= 0) {
        return NULL;
    }

    for (it = mvc->dep_queue; it; it = it->next) {
        int64_t dep_pts;
        int64_t delta;

        if (!_open3d_mvc_contains_nal_type(it->buf, it->len, 20)) {
            continue;
        }

        dep_pts = it->pts > 0 ? it->pts : _open3d_mvc_pes_emit_time(it);
        if (dep_pts <= 0) {
            continue;
        }

        delta = _open3d_mvc_abs64(dep_pts - base_pts);
        if (delta > OPEN3D_MVC_STARTUP_RELAXED_PTS_WINDOW) {
            continue;
        }

        if (!best || delta < best_delta ||
            (delta == best_delta && dep_pts <= base_pts &&
             (best->pts <= 0 || best->pts > base_pts))) {
            best = it;
            best_delta = delta;
        }
    }

    if (!best) {
        return NULL;
    }

    while (mvc->dep_queue && mvc->dep_queue != best) {
        PES_BUFFER *old = mvc->dep_queue;
        mvc->dep_queue = old->next;
        old->next = NULL;
        pes_buffer_free(&old);
    }

    if (mvc->dep_queue == best) {
        mvc->dep_queue = best->next;
        best->next = NULL;
    }

    if (out_flags) {
        *out_flags = BD_OPEN3D_MVC_UNIT_FLAG_MATCHED |
                     BD_OPEN3D_MVC_UNIT_FLAG_RELAXED_DTS;
    }
    return best;
}

static int _open3d_mvc_ensure_sidecar_locked(BLURAY *bd)
{
    BD_OPEN3D_MVC_RUNTIME *mvc;
    int opened_dep = 0;

    if (!bd || !_open3d_mvc_refresh_runtime_locked(bd)) {
        return 0;
    }

    mvc = &bd->open3d_mvc;
    if (!mvc->info.available || !mvc->dependent_clip ||
        !mvc->info.base_pid || !mvc->info.dependent_pid) {
        return 0;
    }

    if (!mvc->base_demux) {
        mvc->base_demux = m2ts_demux_init(mvc->info.base_pid);
    }
    if (!mvc->dep_demux) {
        mvc->dep_demux = m2ts_demux_init(mvc->info.dependent_pid);
    }
    if (!mvc->base_demux || !mvc->dep_demux) {
        return 0;
    }

    if (!mvc->dep_st.fp || mvc->dep_st.clip != mvc->dependent_clip) {
        _close_m2ts(&mvc->dep_st);
        m2ts_demux_reset(mvc->dep_demux);
        pes_buffer_free(&mvc->dep_queue);
        mvc->dep_st.clip = mvc->dependent_clip;
        if (!_open_m2ts(bd, &mvc->dep_st)) {
            return 0;
        }
        opened_dep = 1;
    }

    if (mvc->dep_seek_pending) {
        if (!opened_dep) {
            m2ts_demux_reset(mvc->dep_demux);
            pes_buffer_free(&mvc->dep_queue);
        }
        if (_seek_stream(bd, &mvc->dep_st, mvc->dependent_clip, mvc->dep_seek_pkt) < 0) {
            return 0;
        }
        _open3d_mvc_trace("dep_seek_apply dep_clip=%s dep_pkt=%u dep_time=%u",
                          mvc->dependent_clip->name,
                          mvc->dep_seek_pkt,
                          mvc->dep_seek_time);
        mvc->dep_seek_pending = 0;
    }

    return 1;
}

static void _open3d_mvc_drop_dep_before(BD_OPEN3D_MVC_RUNTIME *mvc,
                                        const PES_BUFFER *base)
{
    while (mvc && mvc->dep_queue) {
        int64_t dep_time = _open3d_mvc_pes_time(mvc->dep_queue);
        int64_t base_time = _open3d_mvc_pes_time(base);

        if (_open3d_mvc_dep_candidate_ready(base, mvc->dep_queue) ||
            base_time <= 0 || dep_time <= 0) {
            break;
        }

        pes_buffer_next(&mvc->dep_queue);
    }
}

static void _open3d_mvc_fill_dep_queue_locked(BLURAY *bd, const PES_BUFFER *base,
                                              int allow_aging)
{
    BD_OPEN3D_MVC_RUNTIME *mvc = &bd->open3d_mvc;
    int64_t base_time = _open3d_mvc_pes_time(base);
    int max_blocks = mvc->dep_queue ? OPEN3D_MVC_DEP_FILL_BLOCKS
                                    : OPEN3D_MVC_DEP_EMPTY_FILL_BLOCKS;
    int ii;

    if (!mvc->dep_st.fp || !mvc->dep_demux) {
        return;
    }

    for (ii = 0; ii < max_blocks; ii++) {
        PES_BUFFER *dep_list;
        int r;
        int64_t dep_time;

        if (allow_aging) {
            _open3d_mvc_drop_dep_before(mvc, base);
        }
        if (mvc->dep_queue) {
            dep_time = _open3d_mvc_pes_time(mvc->dep_queue);
            if (_open3d_mvc_dep_candidate_ready(base, mvc->dep_queue) ||
                base_time <= 0 || dep_time <= 0) {
                if (ii > 0) {
                    _open3d_mvc_trace("dep_fill stop blocks=%d base_time=%" PRId64
                                      " dep_head_pts=%" PRId64 " dep_head_dts=%" PRId64
                                      " dep_head_time=%" PRId64,
                                      ii, base_time,
                                      mvc->dep_queue->pts, mvc->dep_queue->dts, dep_time);
                }
                return;
            }
        }

        r = _read_block(bd, &mvc->dep_st, mvc->dep_int_buf);
        if (r <= 0) {
            _open3d_mvc_trace("dep_fill eof blocks=%d base_time=%" PRId64
                              " dep_queue=%s",
                              ii, base_time, mvc->dep_queue ? "nonempty" : "empty");
            return;
        }

        dep_list = m2ts_demux(mvc->dep_demux, mvc->dep_int_buf);
        if (dep_list) {
            PES_BUFFER *dep_trace = dep_list;
            while (dep_trace) {
                _open3d_mvc_cache_dep_startup_prefix(mvc, dep_trace);
                _open3d_mvc_trace_pes("dep_pes", dep_trace);
                dep_trace = dep_trace->next;
            }
            pes_buffer_append(&mvc->dep_queue, dep_list);
        }
    }

    _open3d_mvc_trace("dep_fill budget_hit blocks=%d base_time=%" PRId64
                      " dep_queue=%s dep_head_pts=%" PRId64 " dep_head_dts=%" PRId64
                      " dep_head_time=%" PRId64,
                      max_blocks, base_time,
                      mvc->dep_queue ? "nonempty" : "empty",
                      mvc->dep_queue ? mvc->dep_queue->pts : 0,
                      mvc->dep_queue ? mvc->dep_queue->dts : 0,
                      mvc->dep_queue ? _open3d_mvc_pes_time(mvc->dep_queue)
                                     : OPEN3D_MVC_INVALID_TS);
}

static void _open3d_mvc_process_base_pes_lavlike_locked(BLURAY *bd, PES_BUFFER *base)
{
    BD_OPEN3D_MVC_RUNTIME *mvc = &bd->open3d_mvc;
    PES_BUFFER *dep = NULL;
    uint32_t flags = BD_OPEN3D_MVC_UNIT_FLAG_NONE;
    uint32_t base_off = 0;
    uint32_t dep_off = 0;
    int64_t base_time;
    int64_t dep_time = OPEN3D_MVC_INVALID_TS;
    int64_t base_emit_time = OPEN3D_MVC_INVALID_TS;
    int64_t dep_emit_time = OPEN3D_MVC_INVALID_TS;
    int initial_start = 0;
    int restart_epoch = 0;
    int runway_needs_exact = 0;
    int stale_relaxed = 0;
    int strong_exact = 0;
    int startup_base_has_idr = 0;
    int startup_base_has_sps = 0;
    int startup_base_has_pps = 0;
    int startup_base_can_use_cached_prefix = 0;
    const uint8_t *startup_base_prefix = NULL;
    uint32_t startup_base_prefix_len = 0;
    const uint8_t *startup_dep_prefix = NULL;
    uint32_t startup_dep_prefix_len = 0;

    if (!mvc || !base) {
        return;
    }

    base_time = _open3d_mvc_pes_time(base);
    initial_start = !mvc->started;
    restart_epoch = mvc->seek_restart_pending;
    runway_needs_exact = mvc->strict_relock_exacts_needed > 0;

    _open3d_mvc_drop_dep_before(mvc, base);
    _open3d_mvc_fill_dep_queue_locked(bd, base, 1);
    _open3d_mvc_drop_dep_before(mvc, base);
    _open3d_mvc_cache_base_startup_prefix(mvc, base);

    if (mvc->dep_queue) {
        dep_time = _open3d_mvc_pes_time(mvc->dep_queue);
        flags = _open3d_mvc_match_flags(base, mvc->dep_queue);
        if ((flags & BD_OPEN3D_MVC_UNIT_FLAG_MATCHED) &&
            _open3d_mvc_prepare_match_offsets(mvc, base, mvc->dep_queue,
                                              initial_start, 0, 0,
                                              &base_off, &dep_off)) {
            dep = mvc->dep_queue;
            mvc->dep_queue = dep->next;
            dep->next = NULL;
            dep_time = _open3d_mvc_pes_time(dep);
            base_emit_time = _open3d_mvc_pes_emit_time(base);
            dep_emit_time = _open3d_mvc_pes_emit_time(dep);
            strong_exact = _open3d_mvc_is_strong_exact_match(base, dep, flags);
            stale_relaxed =
                (flags & BD_OPEN3D_MVC_UNIT_FLAG_RELAXED_DTS) &&
                mvc->last_unit_base_time > 0 &&
                ((base_emit_time > 0 && base_emit_time <= mvc->last_unit_base_time) ||
                 (dep_emit_time > 0 && dep_emit_time <= mvc->last_unit_base_time));
            _open3d_mvc_trace("lavlike_match base_pts=%" PRId64 " base_dts=%" PRId64
                              " dep_pts=%" PRId64 " dep_dts=%" PRId64
                              " flags=0x%x initial=%d restart=%d runway=%u stale=%d strong=%d",
                              base->pts, base->dts,
                              dep->pts, dep->dts,
                              flags, initial_start, restart_epoch,
                              mvc->strict_relock_exacts_needed, stale_relaxed, strong_exact);
        } else {
            _open3d_mvc_trace("lavlike_skip_unmatched base_pts=%" PRId64
                              " base_dts=%" PRId64 " base_time=%" PRId64
                              " dep_head_pts=%" PRId64 " dep_head_dts=%" PRId64
                              " dep_head_time=%" PRId64 " flags=0x%x initial=%d",
                              base->pts, base->dts, base_time,
                              mvc->dep_queue->pts, mvc->dep_queue->dts, dep_time,
                              flags, initial_start);
        }
    } else {
        _open3d_mvc_trace("lavlike_no_dep base_pts=%" PRId64
                          " base_dts=%" PRId64 " base_time=%" PRId64
                          " initial=%d warmup_aus=%u",
                          base->pts, base->dts, base_time,
                          initial_start, mvc->initial_base_only_aus);
    }

    if (!dep) {
        if (!initial_start ||
            mvc->initial_base_only_aus >= OPEN3D_MVC_INITIAL_BASE_ONLY_WARMUP_AUS ||
            !_open3d_mvc_find_leading_sync_offset(base->buf, base->len, &base_off) ||
            base_off >= base->len) {
            return;
        }

        _open3d_mvc_queue_unit(mvc, base, NULL, BD_OPEN3D_MVC_UNIT_FLAG_NONE,
                               base_off, 0, NULL, 0, NULL, 0);
        if (mvc->initial_base_only_aus < UINT8_MAX) {
            mvc->initial_base_only_aus++;
        }
        _open3d_mvc_trace("lavlike_base_only base_pts=%" PRId64
                          " base_dts=%" PRId64 " base_time=%" PRId64
                          " warmup_aus=%u base_off=%u",
                          base->pts, base->dts, base_time,
                          mvc->initial_base_only_aus, base_off);
        return;
    }

    if (runway_needs_exact && !strong_exact) {
        _open3d_mvc_trace("lavlike_drop_relaxed_runway base_pts=%" PRId64
                          " base_dts=%" PRId64 " dep_pts=%" PRId64
                          " dep_dts=%" PRId64 " flags=0x%x runway=%u",
                          base->pts, base->dts, dep->pts, dep->dts,
                          flags, mvc->strict_relock_exacts_needed);
        pes_buffer_free(&dep);
        return;
    }

    if (stale_relaxed) {
        _open3d_mvc_trace("lavlike_drop_nonmonotonic_relaxed base_pts=%" PRId64
                          " base_dts=%" PRId64 " dep_pts=%" PRId64
                          " dep_dts=%" PRId64 " last_unit_time=%" PRId64,
                          base->pts, base->dts, dep->pts, dep->dts,
                          mvc->last_unit_base_time);
        pes_buffer_free(&dep);
        return;
    }

    startup_base_has_idr = _open3d_mvc_contains_nal_type(base->buf, base->len, 5);
    startup_base_has_sps = _open3d_mvc_contains_nal_type(base->buf, base->len, 7);
    startup_base_has_pps = _open3d_mvc_contains_nal_type(base->buf, base->len, 8);
    startup_base_can_use_cached_prefix =
        startup_base_has_idr &&
        !startup_base_has_sps &&
        !startup_base_has_pps &&
        mvc->startup_base_prefix &&
        mvc->startup_base_prefix_len > 0 &&
        !mvc->startup_base_prefix_used;

    if (initial_start &&
        mvc->startup_base_prefix &&
        mvc->startup_base_prefix_len > 0 &&
        !mvc->startup_base_prefix_used &&
        !_open3d_mvc_contains_nal_type(base->buf + base_off,
                                       base->len - base_off, 7) &&
        !_open3d_mvc_contains_nal_type(base->buf + base_off,
                                       base->len - base_off, 8) &&
        startup_base_can_use_cached_prefix) {
        startup_base_prefix = mvc->startup_base_prefix;
        startup_base_prefix_len = mvc->startup_base_prefix_len;
    }

    if (initial_start &&
        mvc->startup_dep_prefix &&
        mvc->startup_dep_prefix_len > 0 &&
        !mvc->startup_dep_prefix_used &&
        !_open3d_mvc_contains_nal_type(dep->buf + dep_off,
                                       dep->len - dep_off, 15)) {
        startup_dep_prefix = mvc->startup_dep_prefix;
        startup_dep_prefix_len = mvc->startup_dep_prefix_len;
    }

    _open3d_mvc_queue_unit(mvc, base, dep, flags, base_off, dep_off,
                           startup_base_prefix, startup_base_prefix_len,
                           startup_dep_prefix, startup_dep_prefix_len);

    if (initial_start || restart_epoch) {
        mvc->strict_relock_exacts_needed = restart_epoch
            ? OPEN3D_MVC_SEEK_STARTUP_EXACT_RUNWAY
            : OPEN3D_MVC_STARTUP_EXACT_RUNWAY;
    }
    if (strong_exact && mvc->strict_relock_exacts_needed > 0) {
        mvc->strict_relock_exacts_needed--;
    }

    mvc->started = 1;
    mvc->initial_base_only_aus = 0;
    mvc->pending_hard_relock = 0;
    mvc->stale_relaxed_active = 0;
    if (restart_epoch) {
        mvc->seek_restart_pending = 0;
        mvc->seek_restart_allow_non_idr = 0;
        _open3d_mvc_trace("lavlike_seek_restart_complete base_pts=%" PRId64
                          " base_dts=%" PRId64 " flags=0x%x",
                          base->pts, base->dts, flags);
    }
    if (strong_exact) {
        mvc->last_exact_base_time = _open3d_mvc_pes_emit_time(base);
    }

    if (startup_base_prefix_len > 0) {
        mvc->startup_base_prefix_used = 1;
        _open3d_mvc_trace("lavlike_startup_base_prefix_apply base_pts=%" PRId64
                          " base_dts=%" PRId64 " prefix_len=%u",
                          base->pts, base->dts, startup_base_prefix_len);
    }
    if (startup_dep_prefix_len > 0) {
        mvc->startup_dep_prefix_used = 1;
        _open3d_mvc_trace("lavlike_startup_dep_prefix_apply base_pts=%" PRId64
                          " base_dts=%" PRId64 " prefix_len=%u",
                          base->pts, base->dts, startup_dep_prefix_len);
    }
    if (initial_start || restart_epoch || runway_needs_exact) {
        _open3d_mvc_trace("lavlike_runway_state base_pts=%" PRId64
                          " base_dts=%" PRId64 " remaining=%u",
                          base->pts, base->dts, mvc->strict_relock_exacts_needed);
    }

    pes_buffer_free(&dep);
}

static void _open3d_mvc_process_base_pes_locked(BLURAY *bd, PES_BUFFER *base)
{
    if (!bd || !base) {
        return;
    }

    _open3d_mvc_process_base_pes_lavlike_locked(bd, base);
}

static void _open3d_mvc_process_block_locked(BLURAY *bd, uint8_t *block)
{
    BD_OPEN3D_MVC_RUNTIME *mvc = &bd->open3d_mvc;
    PES_BUFFER *base_list;

    if (!bd || !block || !_open3d_mvc_ensure_sidecar_locked(bd)) {
        return;
    }

    base_list = m2ts_demux(mvc->base_demux, block);
    while (base_list) {
        PES_BUFFER *base = base_list;
        base_list = base->next;
        base->next = NULL;
        _open3d_mvc_trace_pes("base_pes", base);
        _open3d_mvc_process_base_pes_locked(bd, base);
        pes_buffer_free(&base);
    }
}

/*
 * UO mask
 */

static uint32_t _compressed_mask(BD_UO_MASK mask)
{
    uint32_t value = 0x0;
#define UO_MASK_VALUE(v, f)  ((!!(mask.f)) * (BLURAY_UO_ ## v))
    value |= UO_MASK_VALUE(MENU_CALL,                           menu_call);
    value |= UO_MASK_VALUE(TITLE_SEARCH,                        title_search);
    value |= UO_MASK_VALUE(CHAPTER_SEARCH,                      chapter_search);
    value |= UO_MASK_VALUE(TIME_SEARCH_MASK,                    time_search);
    value |= UO_MASK_VALUE(SKIP_TO_NEXT_POINT_MASK,             skip_to_next_point);
    value |= UO_MASK_VALUE(SKIP_BACK_TO_PREVIOUS_POINT_MASK,    skip_to_prev_point);
    value |= UO_MASK_VALUE(STOP_MASK,                           stop);
    value |= UO_MASK_VALUE(PAUSE_ON_MASK,                       pause_on);
    value |= UO_MASK_VALUE(STILL_OFF_MASK,                      still_off);
    value |= UO_MASK_VALUE(FORWARD_PLAY_MASK,                   forward);
    value |= UO_MASK_VALUE(BACKWARD_PLAY_MASK,                  backward);
    value |= UO_MASK_VALUE(RESUME_MASK,                         resume);
    value |= UO_MASK_VALUE(MOVE_UP_SELECTED_BUTTON_MASK,        move_up);
    value |= UO_MASK_VALUE(MOVE_DOWN_SELECTED_BUTTON_MASK,      move_down);
    value |= UO_MASK_VALUE(MOVE_LEFT_SELECTED_BUTTON_MASK,      move_left);
    value |= UO_MASK_VALUE(MOVE_RIGHT_SELECTED_BUTTON_MASK,     move_right);
    value |= UO_MASK_VALUE(SELECT_BUTTON_MASK,                  select);
    value |= UO_MASK_VALUE(ACTIVATE_BUTTON_MASK,                activate);
    value |= UO_MASK_VALUE(SELECT_AND_ACTIVATE_MASK,            select_and_activate);
    value |= UO_MASK_VALUE(PRIMARY_AUDIO_CHANGE_MASK,           primary_audio_change);
    value |= UO_MASK_VALUE(ANGLE_CHANGE_MASK,                   angle_change);
    value |= UO_MASK_VALUE(POPUP_ON_MASK,                       popup_on);
    value |= UO_MASK_VALUE(POPUP_OFF_MASK,                      popup_off);
    value |= UO_MASK_VALUE(PG_TEXTST_ENABLE_DISABLE_MASK,       pg_enable_disable);
    value |= UO_MASK_VALUE(PG_TEXTST_CHANGE_MASK,               pg_change);
    value |= UO_MASK_VALUE(SECONDARY_VIDEO_ENABLE_DISABLE_MASK, secondary_video_enable_disable);
    value |= UO_MASK_VALUE(SECONDARY_VIDEO_CHANGE_MASK,         secondary_video_change);
    value |= UO_MASK_VALUE(SECONDARY_AUDIO_ENABLE_DISABLE_MASK, secondary_audio_enable_disable);
    value |= UO_MASK_VALUE(SECONDARY_AUDIO_CHANGE_MASK,         secondary_audio_change);
    value |= UO_MASK_VALUE(PIP_PG_TEXTST_CHANGE_MASK,           pip_pg_change);
#undef UO_MASK_VALUE
    return value;
}

static void _update_uo_mask(BLURAY *bd)
{
    BD_UO_MASK old_mask = bd->uo_mask;
    BD_UO_MASK new_mask;

    new_mask = uo_mask_combine(bd->title_uo_mask, bd->st0.uo_mask);
    new_mask = uo_mask_combine(bd->gc_uo_mask,    new_mask);
    if (_compressed_mask(old_mask) != _compressed_mask(new_mask)) {
        _queue_event(bd, BD_EVENT_UO_MASK_CHANGED, _compressed_mask(new_mask));
    }
    bd->uo_mask = new_mask;
}

static void _update_hdmv_uo_mask(BLURAY *bd)
{
    uint32_t mask = hdmv_vm_get_uo_mask(bd->hdmv_vm);
    bd->title_uo_mask.title_search = !!(mask & HDMV_TITLE_SEARCH_MASK);
    bd->title_uo_mask.menu_call    = !!(mask & HDMV_MENU_CALL_MASK);

    _update_uo_mask(bd);
}


/*
 * clip access (BD_STREAM)
 */

static void _close_m2ts(BD_STREAM *st)
{
    if (st->fp != NULL) {
        file_close(st->fp);
        st->fp = NULL;
    }

    m2ts_filter_close(&st->m2ts_filter);
}

static int _open_m2ts(BLURAY *bd, BD_STREAM *st)
{
    _close_m2ts(st);

    if (!st->clip) {
        return 0;
    }

    st->fp = disc_open_stream(bd->disc, st->clip->name);

    st->clip_size = 0;
    st->clip_pos = (uint64_t)st->clip->start_pkt * 192;
    st->clip_block_pos = (st->clip_pos / 6144) * 6144;
    st->eof_hit = 0;
    st->encrypted_block_cnt = 0;

    if (st->fp) {
        int64_t clip_size = file_size(st->fp);
        if (clip_size > 0) {

            if (file_seek(st->fp, st->clip_block_pos, SEEK_SET) < 0) {
                BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Unable to seek clip %s!\n", st->clip->name);
                _close_m2ts(st);
                return 0;
            }

            st->clip_size   = clip_size;
            st->int_buf_off = 6144;

            if (st == &bd->st0) {
                const MPLS_PL *pl = st->clip->title->pl;
                const MPLS_STN *stn = &pl->play_item[st->clip->ref].stn;

                st->uo_mask = uo_mask_combine(pl->app_info.uo_mask,
                                              pl->play_item[st->clip->ref].uo_mask);
                _update_uo_mask(bd);

                st->m2ts_filter = m2ts_filter_init((int64_t)st->clip->in_time << 1,
                                                   (int64_t)st->clip->out_time << 1,
                                                   stn->num_video, stn->num_audio,
                                                   stn->num_ig, stn->num_pg);

                _update_clip_psrs(bd, st->clip);

                _init_pg_stream(bd);

                _init_textst_timer(bd);
            }

            return 1;
        }

        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Clip %s empty!\n", st->clip->name);
        _close_m2ts(st);
    }

    BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Unable to open clip %s!\n", st->clip->name);

    return 0;
}

static int _validate_unit(BLURAY *bd, BD_STREAM *st, uint8_t *buf)
{
    /* Check TP_extra_header Copy_permission_indicator. If != 0, unit may be encrypted. */
    /* Check first sync byte. It should never be encrypted. */
    if (BD_UNLIKELY(buf[0] & 0xc0 || buf[4] != 0x47)) {

        /* Check first sync bytes. If not OK, drop unit. */
        if (buf[4] != 0x47 || buf [4 + 192] != 0x47 || buf[4 + 2*192] != 0x47 || buf[4 + 3*192] != 0x47) {

            /* Some streams have Copy_permission_indicator incorrectly set. */
            /* Check first TS sync byte. If unit is encrypted, first 16 bytes are plain, rest not. */
            /* not 100% accurate (can be random data too). But the unit is broken anyway ... */
            if (buf[4] == 0x47) {

                /* most likely encrypted stream. Check couple of blocks before erroring out. */
                st->encrypted_block_cnt++;

                if (st->encrypted_block_cnt > 10) {
                    /* error out */
                    BD_DEBUG(DBG_BLURAY | DBG_CRIT, "TP header copy permission indicator != 0. Stream seems to be encrypted.\n");
                    _queue_event(bd, BD_EVENT_ENCRYPTED, BD_ERROR_AACS);
                    return -1;
                }
            }

            /* broken block, ignore it */
            _queue_event(bd, BD_EVENT_READ_ERROR, 1);
            return 0;
        }
    }

    st->eof_hit = 0;
    st->encrypted_block_cnt = 0;
    return 1;
}

static int _skip_unit(BLURAY *bd, BD_STREAM *st)
{
    const size_t len = 6144;

    /* skip broken unit */
    st->clip_block_pos += len;
    st->clip_pos += len;

    _queue_event(bd, BD_EVENT_READ_ERROR, 0);

    /* seek to next unit start */
    if (file_seek(st->fp, st->clip_block_pos, SEEK_SET) < 0) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Unable to seek clip %s!\n", st->clip->name);
        return -1;
    }

    return 0;
}

static int _read_block(BLURAY *bd, BD_STREAM *st, uint8_t *buf)
{
    const size_t len = 6144;

    if (st->fp) {
        BD_DEBUG(DBG_STREAM, "Reading unit at %" PRIu64 "...\n", st->clip_block_pos);

        if (len + st->clip_block_pos <= st->clip_size) {
            size_t read_len;

            if ((read_len = file_read(st->fp, buf, len))) {
                int error;

                if (read_len != len) {
                    BD_DEBUG(DBG_STREAM | DBG_CRIT, "Read %d bytes at %" PRIu64 " ; requested %d !\n", (int)read_len, st->clip_block_pos, (int)len);
                    return _skip_unit(bd, st);
                }
                st->clip_block_pos += len;

                if ((error = _validate_unit(bd, st, buf)) <= 0) {
                    /* skip broken unit */
                    BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Skipping broken unit at %" PRId64 "\n", st->clip_block_pos - len);
                    st->clip_pos += len;
                    return error;
                }

                if (st->m2ts_filter) {
                    int result = m2ts_filter(st->m2ts_filter, buf);
                    if (result < 0) {
                        m2ts_filter_close(&st->m2ts_filter);
                        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "m2ts filter error\n");
                    }
                }

                BD_DEBUG(DBG_STREAM, "Read unit OK!\n");

#ifdef BLURAY_READ_ERROR_TEST
                /* simulate broken blocks */
                if (random() % 1000)
#else
                return 1;
#endif
            }

            BD_DEBUG(DBG_STREAM | DBG_CRIT, "Read unit at %" PRIu64 " failed !\n", st->clip_block_pos);

            return _skip_unit(bd, st);
        }

        /* This is caused by truncated .m2ts file or invalid clip length.
         *
         * Increase position to avoid infinite loops.
         * Next clip won't be selected until all packets of this clip have been read.
         */
        st->clip_block_pos += len;
        st->clip_pos += len;

        if (!st->eof_hit) {
            BD_DEBUG(DBG_STREAM | DBG_CRIT, "Read past EOF !\n");
            st->eof_hit = 1;
        }

        return 0;
    }

    BD_DEBUG(DBG_BLURAY, "No valid title selected!\n");

    return -1;
}

/*
 * clip preload (BD_PRELOAD)
 */

static void _close_preload(BD_PRELOAD *p)
{
    X_FREE(p->buf);
    memset(p, 0, sizeof(*p));
}

#define PRELOAD_SIZE_LIMIT  (512*1024*1024)  /* do not preload clips larger than 512M */

static int _preload_m2ts(BLURAY *bd, BD_PRELOAD *p)
{
    /* setup and open BD_STREAM */

    BD_STREAM st;

    memset(&st, 0, sizeof(st));
    st.clip = p->clip;

    if (st.clip_size > PRELOAD_SIZE_LIMIT) {
        BD_DEBUG(DBG_BLURAY|DBG_CRIT, "_preload_m2ts(): too large clip (%" PRId64 ")\n", st.clip_size);
        return 0;
    }

    if (!_open_m2ts(bd, &st)) {
        return 0;
    }

    /* allocate buffer */
    p->clip_size = (size_t)st.clip_size;
    uint8_t* tmp = (uint8_t*)realloc(p->buf, p->clip_size);
    if (!tmp) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_preload_m2ts(): out of memory\n");
        _close_m2ts(&st);
        _close_preload(p);
        return 0;
    }

    p->buf = tmp;

    /* read clip to buffer */

    uint8_t *buf = p->buf;
    uint8_t *end = p->buf + p->clip_size;

    for (; buf < end; buf += 6144) {
        if (_read_block(bd, &st, buf) <= 0) {
            BD_DEBUG(DBG_BLURAY|DBG_CRIT, "_preload_m2ts(): error loading %s at %" PRIu64 "\n",
                  st.clip->name, (uint64_t)(buf - p->buf));
            _close_m2ts(&st);
            _close_preload(p);
            return 0;
        }
    }

    /* */

    BD_DEBUG(DBG_BLURAY, "_preload_m2ts(): loaded %" PRIu64 " bytes from %s\n",
          st.clip_size, st.clip->name);

    _close_m2ts(&st);

    return 1;
}

static int64_t _seek_stream(BLURAY *bd, BD_STREAM *st,
                            const NAV_CLIP *clip, uint32_t clip_pkt)
{
    if (!clip)
        return -1;

    if (!st->fp || !st->clip || clip->ref != st->clip->ref) {
        // The position is in a new clip
        st->clip = clip;
        if (!_open_m2ts(bd, st)) {
            return -1;
        }
    }

    if (st->m2ts_filter) {
        m2ts_filter_seek(st->m2ts_filter, 0, (int64_t)st->clip->in_time << 1);
    }

    st->clip_pos = (uint64_t)clip_pkt * 192;
    st->clip_block_pos = (st->clip_pos / 6144) * 6144;

    if (file_seek(st->fp, st->clip_block_pos, SEEK_SET) < 0) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Unable to seek clip %s!\n", st->clip->name);
    }

    st->int_buf_off = 6144;
    st->seek_flag = 1;

    return st->clip_pos;
}

/*
 * Graphics controller interface
 */

static int _run_gc(BLURAY *bd, gc_ctrl_e msg, uint32_t param)
{
    int result = -1;

    if (!bd) {
        return -1;
    }

    if (bd->graphics_controller && bd->hdmv_vm) {
        GC_NAV_CMDS cmds = {-1, NULL, -1, 0, 0, EMPTY_UO_MASK};

        result = gc_run(bd->graphics_controller, msg, param, &cmds);

        if (cmds.num_nav_cmds > 0) {
            hdmv_vm_set_object(bd->hdmv_vm, cmds.num_nav_cmds, cmds.nav_cmds);
            bd->hdmv_suspended = !hdmv_vm_running(bd->hdmv_vm);
        }

        if (cmds.status != bd->gc_status) {
            uint32_t changed_flags = cmds.status ^ bd->gc_status;
            bd->gc_status = cmds.status;
            if (changed_flags & GC_STATUS_MENU_OPEN) {
                _queue_event(bd, BD_EVENT_MENU, !!(bd->gc_status & GC_STATUS_MENU_OPEN));
            }
            if (changed_flags & GC_STATUS_POPUP) {
                _queue_event(bd, BD_EVENT_POPUP, !!(bd->gc_status & GC_STATUS_POPUP));
            }
        }

        if (cmds.sound_id_ref >= 0 && cmds.sound_id_ref < 0xff) {
            _queue_event(bd, BD_EVENT_SOUND_EFFECT, cmds.sound_id_ref);
        }

        bd->gc_uo_mask = cmds.page_uo_mask;
        _update_uo_mask(bd);

    } else {
        if (bd->gc_status & GC_STATUS_MENU_OPEN) {
            _queue_event(bd, BD_EVENT_MENU, 0);
        }
        if (bd->gc_status & GC_STATUS_POPUP) {
            _queue_event(bd, BD_EVENT_POPUP, 0);
        }
        bd->gc_status = GC_STATUS_NONE;
    }

    return result;
}

/*
 * disc info
 */

static void _check_bdj(BLURAY *bd)
{
    if (!bd->disc_info.bdj_handled) {
        if (!bd->disc || bd->disc_info.bdj_detected) {

            /* Check if jvm + jar can be loaded ? */
            switch (bdj_jvm_available(&bd->bdj_config)) {
                case BDJ_CHECK_OK:
                    bd->disc_info.bdj_handled = 1;
                    /* fall thru */
                case BDJ_CHECK_NO_JAR:
                    bd->disc_info.libjvm_detected = 1;
                    /* fall thru */
                default:;
            }
        }
    }
}

static void _fill_disc_info(BLURAY *bd, BD_ENC_INFO *enc_info)
{
    INDX_ROOT *index = NULL;

    if (enc_info) {
        bd->disc_info.aacs_detected      = enc_info->aacs_detected;
        bd->disc_info.libaacs_detected   = enc_info->libaacs_detected;
        bd->disc_info.aacs_error_code    = enc_info->aacs_error_code;
        bd->disc_info.aacs_handled       = enc_info->aacs_handled;
        bd->disc_info.aacs_mkbv          = enc_info->aacs_mkbv;
        memcpy(bd->disc_info.disc_id, enc_info->disc_id, 20);
        bd->disc_info.bdplus_detected    = enc_info->bdplus_detected;
        bd->disc_info.libbdplus_detected = enc_info->libbdplus_detected;
        bd->disc_info.bdplus_handled     = enc_info->bdplus_handled;
        bd->disc_info.bdplus_gen         = enc_info->bdplus_gen;
        bd->disc_info.bdplus_date        = enc_info->bdplus_date;
        bd->disc_info.no_menu_support    = enc_info->no_menu_support;
    }

    bd->disc_info.bluray_detected        = 0;
    bd->disc_info.top_menu_supported     = 0;
    bd->disc_info.first_play_supported   = 0;
    bd->disc_info.num_hdmv_titles        = 0;
    bd->disc_info.num_bdj_titles         = 0;
    bd->disc_info.num_unsupported_titles = 0;

    bd->disc_info.bdj_detected    = 0;
    bd->disc_info.bdj_supported   = 1;

    bd->disc_info.num_titles  = 0;
    bd->disc_info.titles      = NULL;
    bd->disc_info.top_menu    = NULL;
    bd->disc_info.first_play  = NULL;

    array_free((void**)&bd->titles);

    memset(bd->disc_info.bdj_org_id,  0, sizeof(bd->disc_info.bdj_org_id));
    memset(bd->disc_info.bdj_disc_id, 0, sizeof(bd->disc_info.bdj_disc_id));

    if (bd->disc) {
        bd->disc_info.udf_volume_id = disc_volume_id(bd->disc);
        index = indx_get(bd->disc);
        if (!index) {
            /* check for incomplete disc */
            NAV_TITLE_LIST *title_list = nav_get_title_list(bd->disc, 0, 0);
            if (title_list && title_list->count > 0) {
                BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Possible incomplete BluRay image detected. No menu support.\n");
                bd->disc_info.bluray_detected = 1;
                bd->disc_info.no_menu_support = 1;
            }
            nav_free_title_list(&title_list);
        }
    }

    if (index) {
        INDX_PLAY_ITEM *pi;
        unsigned        ii;

        bd->disc_info.bluray_detected = 1;

        /* application info */
        bd->disc_info.video_format                      = index->app_info.video_format;
        bd->disc_info.frame_rate                        = index->app_info.frame_rate;
        bd->disc_info.initial_dynamic_range_type        = index->app_info.initial_dynamic_range_type;
        bd->disc_info.content_exist_3D                  = index->app_info.content_exist_flag;
        bd->disc_info.initial_output_mode_preference    = index->app_info.initial_output_mode_preference;
        memcpy(bd->disc_info.provider_data, index->app_info.user_data, sizeof(bd->disc_info.provider_data));

        /* allocate array for title info */
        BLURAY_TITLE **titles = (BLURAY_TITLE**)array_alloc(index->num_titles + 2, sizeof(BLURAY_TITLE));
        if (!titles) {
            BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Can't allocate memory\n");
            indx_free(&index);
            return;
        }
        bd->titles = titles;
        bd->disc_info.titles = (const BLURAY_TITLE * const *)titles;
        bd->disc_info.num_titles = index->num_titles;

        /* count titles and fill title info */

        for (ii = 0; ii < index->num_titles; ii++) {
            if (index->titles[ii].object_type == indx_object_type_hdmv) {
                bd->disc_info.num_hdmv_titles++;
                titles[ii + 1]->interactive = (index->titles[ii].hdmv.playback_type == indx_hdmv_playback_type_interactive);
                titles[ii + 1]->id_ref = index->titles[ii].hdmv.id_ref;
            }
            if (index->titles[ii].object_type == indx_object_type_bdj) {
                bd->disc_info.num_bdj_titles++;
                bd->disc_info.bdj_detected = 1;
                titles[ii + 1]->bdj = 1;
                titles[ii + 1]->interactive = (index->titles[ii].bdj.playback_type == indx_bdj_playback_type_interactive);
                titles[ii + 1]->id_ref = atoi(index->titles[ii].bdj.name);
            }

            titles[ii + 1]->accessible =  !(index->titles[ii].access_type & INDX_ACCESS_PROHIBITED_MASK);
            titles[ii + 1]->hidden     = !!(index->titles[ii].access_type & INDX_ACCESS_HIDDEN_MASK);
        }

        pi = &index->first_play;
        if (pi->object_type == indx_object_type_bdj) {
            bd->disc_info.bdj_detected = 1;
            titles[index->num_titles + 1]->bdj = 1;
            titles[index->num_titles + 1]->interactive = (pi->bdj.playback_type == indx_bdj_playback_type_interactive);
            titles[index->num_titles + 1]->id_ref = atoi(pi->bdj.name);
        }
        if (pi->object_type == indx_object_type_hdmv && pi->hdmv.id_ref != 0xffff) {
            titles[index->num_titles + 1]->interactive = (pi->hdmv.playback_type == indx_hdmv_playback_type_interactive);
            titles[index->num_titles + 1]->id_ref = pi->hdmv.id_ref;
        }

        pi = &index->top_menu;
        if (pi->object_type == indx_object_type_bdj) {
            bd->disc_info.bdj_detected = 1;
            titles[0]->bdj = 1;
            titles[0]->interactive = (pi->bdj.playback_type == indx_bdj_playback_type_interactive);
            titles[0]->id_ref = atoi(pi->bdj.name);
        }
        if (pi->object_type == indx_object_type_hdmv && pi->hdmv.id_ref != 0xffff) {
            titles[0]->interactive = (pi->hdmv.playback_type == indx_hdmv_playback_type_interactive);
            titles[0]->id_ref = pi->hdmv.id_ref;
        }

        /* mark supported titles */

        _check_bdj(bd);

        if (bd->disc_info.bdj_detected && !bd->disc_info.bdj_handled) {
            bd->disc_info.num_unsupported_titles = bd->disc_info.num_bdj_titles;
        }

        pi = &index->first_play;
        if (pi->object_type == indx_object_type_hdmv && pi->hdmv.id_ref != 0xffff) {
            bd->disc_info.first_play_supported = 1;
        }
        if (pi->object_type == indx_object_type_bdj) {
            bd->disc_info.first_play_supported = bd->disc_info.bdj_handled;
        }

        pi = &index->top_menu;
        if (pi->object_type == indx_object_type_hdmv && pi->hdmv.id_ref != 0xffff) {
            bd->disc_info.top_menu_supported = 1;
        }
        if (pi->object_type == indx_object_type_bdj) {
            bd->disc_info.top_menu_supported = bd->disc_info.bdj_handled;
        }

        /* */

        if (bd->disc_info.first_play_supported) {
            titles[index->num_titles + 1]->accessible = 1;
            bd->disc_info.first_play = titles[index->num_titles + 1];
        }
        if (bd->disc_info.top_menu_supported) {
            titles[0]->accessible = 1;
            bd->disc_info.top_menu = titles[0];
        }

        /* increase player profile and version when 3D or UHD disc is detected */

        if (index->indx_version >= ('0' << 24 | '3' << 16 | '0' << 8 | '0')) {
            BD_DEBUG(DBG_BLURAY, "Detected 4K UltraHD (profile 6) disc\n");
            /* Switch to UHD profile */
            psr_init_UHD(bd->regs, 1);
        }
        if (((index->indx_version >> 16) & 0xff) == '2') {
            if (index->app_info.content_exist_flag) {
                BD_DEBUG(DBG_BLURAY, "Detected Blu-Ray 3D (profile 5) disc\n");
                /* Switch to 3D profile */
                psr_init_3D(bd->regs, index->app_info.initial_output_mode_preference, 0);
            }
        }

        indx_free(&index);

        /* populate title names */
        bd_get_meta(bd);
    }

#if 0
    if (!bd->disc_info.first_play_supported || !bd->disc_info.top_menu_supported) {
        bd->disc_info.no_menu_support = 1;
    }
#endif

    if (bd->disc_info.bdj_detected) {
        BDID_DATA *bdid = bdid_get(bd->disc); /* parse id.bdmv */
        if (bdid) {
            memcpy(bd->disc_info.bdj_org_id,  bdid->org_id,  sizeof(bd->disc_info.bdj_org_id));
            memcpy(bd->disc_info.bdj_disc_id, bdid->disc_id, sizeof(bd->disc_info.bdj_disc_id));
            bdid_free(&bdid);
        }
    }

    _check_bdj(bd);
}

const BLURAY_DISC_INFO *bd_get_disc_info(BLURAY *bd)
{
    bd_mutex_lock(&bd->mutex);
    if (!bd->disc) {
        _fill_disc_info(bd, NULL);
    }
    bd_mutex_unlock(&bd->mutex);
    return &bd->disc_info;
}

/*
 * bdj callbacks
 */

void bd_set_bdj_uo_mask(BLURAY *bd, unsigned mask)
{
    bd->title_uo_mask.title_search = !!(mask & BDJ_TITLE_SEARCH_MASK);
    bd->title_uo_mask.menu_call    = !!(mask & BDJ_MENU_CALL_MASK);

    _update_uo_mask(bd);
}

uint64_t bd_get_uo_mask(BLURAY *bd)
{
    /* internal function. Used by BD-J (and UO masking checks). */
    union {
      uint64_t u64;
      BD_UO_MASK mask;
    } mask = {0};

    //bd_mutex_lock(&bd->mutex);
    memcpy(&mask.mask, &bd->uo_mask, sizeof(BD_UO_MASK));
    //bd_mutex_unlock(&bd->mutex);

    return mask.u64;
}

static int _is_uo_masked(BLURAY *bd, bd_uo_mask_index_e mask_index)
{
    return (bd->title_type != title_undef) && !!(bd_get_uo_mask(bd) & (1ull << mask_index));
}

void bd_set_bdj_kit(BLURAY *bd, int mask)
{
    _queue_event(bd, BD_EVENT_KEY_INTEREST_TABLE, mask);
}

int bd_bdj_sound_effect(BLURAY *bd, int id)
{
    if (bd->sound_effects && id >= bd->sound_effects->num_sounds) {
        return -1;
    }
    if (id < 0 || id > 0xff) {
        return -1;
    }

    _queue_event(bd, BD_EVENT_SOUND_EFFECT, id);
    return 0;
}

void bd_select_rate(BLURAY *bd, float rate, int reason)
{
    if (reason == BDJ_PLAYBACK_STOP) {
        /* playback stop. Might want to wait for buffers empty here. */
        return;
    }

    if (reason == BDJ_PLAYBACK_START) {
        /* playback is triggered by bd_select_rate() */
        bd->bdj_wait_start = 0;
    }

    if (rate < 0.5) {
        _queue_event(bd, BD_EVENT_STILL, 1);
    } else {
        _queue_event(bd, BD_EVENT_STILL, 0);
    }
}

static int64_t _seek_time(BLURAY *bd, uint64_t tick, uint8_t is_uo_mask_checked);

int bd_bdj_seek(BLURAY *bd, int playitem, int playmark, int64_t time)
{
    bd_mutex_lock(&bd->mutex);

    if (playitem > 0) {
        bd_seek_playitem(bd, playitem);
    }
    if (playmark >= 0) {
        bd_seek_mark(bd, playmark);
    }
    if (time >= 0) {
        _seek_time(bd, time, 0);
    }

    bd_mutex_unlock(&bd->mutex);

    return 1;
}

static int _bd_set_virtual_package(BLURAY *bd, const char *vp_path, int psr_init_backup)
{
    if (bd->title) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_set_virtual_package() failed: playlist is playing\n");
        return -1;
    }
    if (bd->title_type != title_bdj) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_set_virtual_package() failed: HDMV title\n");
        return -1;
    }

    if (psr_init_backup) {
        bd_psr_reset_backup_registers(bd->regs);
    }

    disc_update(bd->disc, vp_path);

    /* TODO: reload all cached information, update disc info, notify app */

    return 0;
}

int bd_set_virtual_package(BLURAY *bd, const char *vp_path, int psr_init_backup)
{
    int ret;
    bd_mutex_lock(&bd->mutex);
    ret = _bd_set_virtual_package(bd, vp_path, psr_init_backup);
    bd_mutex_unlock(&bd->mutex);
    return ret;
}

BD_DISC *bd_get_disc(BLURAY *bd)
{
    return bd ? bd->disc : NULL;
}

uint32_t bd_reg_read(BLURAY *bd, int psr, int reg)
{
    if (psr) {
        return bd_psr_read(bd->regs, reg);
    } else {
        return bd_gpr_read(bd->regs, reg);
    }
}

int bd_reg_write(BLURAY *bd, int psr, int reg, uint32_t value, uint32_t psr_value_mask)
{
    if (psr) {
        if (psr < 102) {
            /* avoid deadlocks (psr_write triggers callbacks that may lock this mutex) */
            bd_mutex_lock(&bd->mutex);
        }
        int res = bd_psr_write_bits(bd->regs, reg, value, psr_value_mask);
        if (psr < 102) {
            bd_mutex_unlock(&bd->mutex);
        }
        return res;
    } else {
        return bd_gpr_write(bd->regs, reg, value);
    }
}

BD_ARGB_BUFFER *bd_lock_osd_buffer(BLURAY *bd)
{
    bd_mutex_lock(&bd->argb_buffer_mutex);
    return bd->argb_buffer;
}

void bd_unlock_osd_buffer(BLURAY *bd)
{
    bd_mutex_unlock(&bd->argb_buffer_mutex);
}

/*
 * handle graphics updates from BD-J layer
 */
void bd_bdj_osd_cb(BLURAY *bd, const unsigned *img, int w, int h,
                   int x0, int y0, int x1, int y1)
{
    BD_ARGB_OVERLAY aov;

    if (!bd->argb_overlay_proc) {
        _queue_event(bd, BD_EVENT_MENU, 0);
        return;
    }

    memset(&aov, 0, sizeof(aov));
    aov.pts   = -1;
    aov.plane = BD_OVERLAY_IG;

    /* no image data -> init or close */
    if (!img) {
        if (w > 0 && h > 0) {
            aov.cmd = BD_ARGB_OVERLAY_INIT;
            aov.w   = w;
            aov.h   = h;
            _queue_event(bd, BD_EVENT_MENU, 1);
        } else {
            aov.cmd = BD_ARGB_OVERLAY_CLOSE;
            _queue_event(bd, BD_EVENT_MENU, 0);
        }

        bd->argb_overlay_proc(bd->argb_overlay_proc_handle, &aov);
        return;
    }

    /* no changed pixels ? */
    if (x1 < x0 || y1 < y0) {
        return;
    }

    /* pass only changed region */
    if (bd->argb_buffer && (bd->argb_buffer->width < w || bd->argb_buffer->height < h)) {
        aov.argb   = img;
    } else {
        aov.argb   = img + x0 + y0 * w;
    }
    aov.stride = w;
    aov.x      = x0;
    aov.y      = y0;
    aov.w      = x1 - x0 + 1;
    aov.h      = y1 - y0 + 1;

    if (bd->argb_buffer) {
        /* set dirty region */
        bd->argb_buffer->dirty[BD_OVERLAY_IG].x0 = x0;
        bd->argb_buffer->dirty[BD_OVERLAY_IG].x1 = x1;
        bd->argb_buffer->dirty[BD_OVERLAY_IG].y0 = y0;
        bd->argb_buffer->dirty[BD_OVERLAY_IG].y1 = y1;
    }

    /* draw */
    aov.cmd = BD_ARGB_OVERLAY_DRAW;
    bd->argb_overlay_proc(bd->argb_overlay_proc_handle, &aov);

    /* commit changes */
    aov.cmd = BD_ARGB_OVERLAY_FLUSH;
    bd->argb_overlay_proc(bd->argb_overlay_proc_handle, &aov);

    if (bd->argb_buffer) {
        /* reset dirty region */
        bd->argb_buffer->dirty[BD_OVERLAY_IG].x0 = bd->argb_buffer->width;
        bd->argb_buffer->dirty[BD_OVERLAY_IG].x1 = bd->argb_buffer->height;
        bd->argb_buffer->dirty[BD_OVERLAY_IG].y0 = 0;
        bd->argb_buffer->dirty[BD_OVERLAY_IG].y1 = 0;
    }
}

/*
 * BD-J
 */

static int _start_bdj(BLURAY *bd, unsigned title)
{
    if (bd->bdjava == NULL) {
        const char *root = disc_root(bd->disc);
        bd->bdjava = bdj_open(root, bd, bd->disc_info.bdj_disc_id, &bd->bdj_config);
        if (!bd->bdjava) {
            return 0;
        }
    }

    return !bdj_process_event(bd->bdjava, BDJ_EVENT_START, title);
}

static int _bdj_event(BLURAY *bd, unsigned ev, unsigned param)
{
    if (bd->bdjava != NULL) {
        return bdj_process_event(bd->bdjava, ev, param);
    }
    return -1;
}

static void _stop_bdj(BLURAY *bd)
{
    if (bd->bdjava != NULL) {
        bdj_process_event(bd->bdjava, BDJ_EVENT_STOP, 0);
        _queue_event(bd, BD_EVENT_STILL, 0);
        _queue_event(bd, BD_EVENT_KEY_INTEREST_TABLE, 0);
    }
}

static void _close_bdj(BLURAY *bd)
{
    if (bd->bdjava != NULL) {
        bdj_close(bd->bdjava);
        bd->bdjava = NULL;
    }
}

/*
 * open / close
 */

BLURAY *bd_init(void)
{
    char *env;

    BD_DEBUG(DBG_BLURAY, "libbluray version "BLURAY_VERSION_STRING"\n");

    BLURAY *bd = calloc(1, sizeof(BLURAY));

    if (!bd) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Can't allocate memory\n");
        return NULL;
    }

    bd->regs = bd_registers_init();
    if (!bd->regs) {
        BD_DEBUG(DBG_BLURAY, "bd_registers_init() failed\n");
        X_FREE(bd);
        return NULL;
    }

    bd->uo_restriction_level = BLURAY_PLAYER_SETTING_UO_RESTRICTION_RELAXED;

    bd_mutex_init(&bd->mutex);
    bd_mutex_init(&bd->argb_buffer_mutex);

    env = getenv("LIBBLURAY_PERSISTENT_STORAGE");
    if (env) {
        int v = (!strcmp(env, "yes")) ? 1 : (!strcmp(env, "no")) ? 0 : atoi(env);
        bd->bdj_config.no_persistent_storage = !v;
    }

    BD_DEBUG(DBG_BLURAY, "BLURAY initialized!\n");

    return bd;
}

static int _bd_open(BLURAY *bd,
                    const char *device_path, const char *keyfile_path,
                    fs_access *p_fs)
{
    BD_ENC_INFO enc_info;

    if (!bd) {
        return 0;
    }

    bd_mutex_lock(&bd->mutex);

    if (bd->disc) {
        bd_mutex_unlock(&bd->mutex);
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Disc already open\n");
        return 0;
    }

    bd->disc = disc_open(device_path, p_fs,
                         &enc_info, keyfile_path,
                         (void*)bd->regs, (void*)bd_psr_read, (void*)bd_psr_write);

    if (!bd->disc) {
        bd_mutex_unlock(&bd->mutex);
        return 0;
    }

    _fill_disc_info(bd, &enc_info);

    bd_mutex_unlock(&bd->mutex);

    return bd->disc_info.bluray_detected;
}

int bd_open_disc(BLURAY *bd, const char *device_path, const char *keyfile_path)
{
    if (!device_path) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "No device path provided!\n");
        return 0;
    }

    return _bd_open(bd, device_path, keyfile_path, NULL);
}

int bd_open_stream(BLURAY *bd,
                   void *read_blocks_handle,
                   int (*read_blocks)(void *handle, void *buf, int lba, int num_blocks))
{
    if (!read_blocks) {
        return 0;
    }

    fs_access fs = { read_blocks_handle, read_blocks, NULL, NULL };
    return _bd_open(bd, NULL, NULL, &fs);
}

int bd_open_files(BLURAY *bd,
                  void *handle,
                  struct bd_dir_s *(*open_dir)(void *handle, const char *rel_path),
                  struct bd_file_s *(*open_file)(void *handle, const char *rel_path))
{
    if (!open_dir || !open_file) {
        return 0;
    }

    fs_access fs = { handle, NULL, open_dir, open_file };
    return _bd_open(bd, NULL, NULL, &fs);
}

BLURAY *bd_open(const char *device_path, const char *keyfile_path)
{
    BLURAY *bd;

    bd = bd_init();
    if (!bd) {
        return NULL;
    }

    if (!bd_open_disc(bd, device_path, keyfile_path)) {
        bd_close(bd);
        return NULL;
    }

    return bd;
}

void bd_close(BLURAY *bd)
{
    if (!bd) {
        return;
    }

    _close_bdj(bd);

    _close_m2ts(&bd->st0);
    _close_preload(&bd->st_ig);
    _close_preload(&bd->st_textst);
    _open3d_mvc_reset_runtime(bd);

    nav_free_title_list(&bd->title_list);
    nav_title_close(&bd->title);

    hdmv_vm_free(&bd->hdmv_vm);

    gc_free(&bd->graphics_controller);
    meta_free(&bd->meta);
    sound_free(&bd->sound_effects);
    bd_registers_free(bd->regs);

    event_queue_destroy(&bd->event_queue);
    array_free((void**)&bd->titles);
    bdj_config_cleanup(&bd->bdj_config);

    disc_close(&bd->disc);

    bd_mutex_destroy(&bd->mutex);
    bd_mutex_destroy(&bd->argb_buffer_mutex);

    BD_DEBUG(DBG_BLURAY, "BLURAY destroyed!\n");

    X_FREE(bd);
}

/*
 * PlayMark tracking
 */

static void _find_next_playmark(BLURAY *bd)
{
    unsigned ii;

    bd->next_mark = -1;
    bd->next_mark_pos = (uint64_t)-1;
    for (ii = 0; ii < bd->title->mark_list.count; ii++) {
        uint64_t pos = (uint64_t)bd->title->mark_list.mark[ii].title_pkt * 192L;
        if (pos > bd->s_pos) {
            bd->next_mark = ii;
            bd->next_mark_pos = pos;
            break;
        }
    }

    _update_chapter_psr(bd);
}

static void _playmark_reached(BLURAY *bd)
{
    while (bd->next_mark >= 0 && bd->s_pos > bd->next_mark_pos) {

        BD_DEBUG(DBG_BLURAY, "PlayMark %d reached (%" PRIu64 ")\n", bd->next_mark, bd->next_mark_pos);

        _queue_event(bd, BD_EVENT_PLAYMARK, bd->next_mark);
        _bdj_event(bd, BDJ_EVENT_MARK, bd->next_mark);

        /* update next mark */
        bd->next_mark++;
        if ((unsigned)bd->next_mark < bd->title->mark_list.count) {
            bd->next_mark_pos = (uint64_t)bd->title->mark_list.mark[bd->next_mark].title_pkt * 192L;
        } else {
            /* no marks left */
            bd->next_mark = -1;
            bd->next_mark_pos = (uint64_t)-1;
        }
    };

    /* chapter tracking */
    _update_chapter_psr(bd);
}

/*
 * seeking and current position
 */

static int64_t _seek_internal(BLURAY *bd,
                           const NAV_CLIP *clip, uint32_t title_pkt, uint32_t clip_pkt)
{
    int64_t result;
    uint32_t actual_clip_pkt = clip_pkt;
    uint32_t actual_title_pkt = title_pkt;

    _open3d_mvc_reset_runtime(bd);

    actual_clip_pkt = _open3d_mvc_select_base_seek_candidate_locked(bd, clip, clip_pkt);
    actual_title_pkt = clip->title_pkt + actual_clip_pkt - clip->start_pkt;

    result = _seek_stream(bd, &bd->st0, clip, actual_clip_pkt);
    if (result >= 0) {
        uint32_t media_time;

        _open3d_mvc_prepare_dep_seek_locked(bd, clip, actual_clip_pkt);

        /* update title position */
        bd->s_pos = (uint64_t)actual_title_pkt * 192;

        /* Update PSR_TIME */
        media_time = _update_time_psr_from_stream(bd);

        /* emit notification events */
        if (media_time >= clip->in_time) {
            media_time = media_time - clip->in_time + clip->title_time;
        }
        _queue_event(bd, BD_EVENT_SEEK, media_time);
        _bdj_event(bd, BDJ_EVENT_SEEK, media_time);

        /* playmark tracking */
        _find_next_playmark(bd);

        /* reset PG decoder and controller */
        if (bd->graphics_controller) {
            gc_run(bd->graphics_controller, GC_CTRL_PG_RESET, 0, NULL);

            _init_textst_timer(bd);
        }

        BD_DEBUG(DBG_BLURAY, "Seek to %" PRIu64 "\n", bd->s_pos);
        return bd->s_pos;
    }

    return result;
}

/* _change_angle() should be used only before call to _seek_internal() ! */
static void _change_angle(BLURAY *bd)
{
    if (bd->seamless_angle_change) {
        nav_set_angle(bd->title, bd->request_angle);
        bd->seamless_angle_change = 0;
        bd_psr_write(bd->regs, PSR_ANGLE_NUMBER, bd->title->angle + 1);

        /* force re-opening .m2ts file in _seek_internal() */
        _close_m2ts(&bd->st0);
    }
}

static int64_t _seek_time(BLURAY *bd, uint64_t tick, uint8_t is_uo_mask_checked)
{
    uint32_t clip_pkt, out_pkt;
    const NAV_CLIP *clip;

    if (tick >> 33) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_seek_time(%" PRIu64 ") failed: invalid timestamp\n", tick);
        return bd->s_pos;
    }

    tick /= 2; // Convert to 45kHz clock ticks

    /* Check UO mask */
    if (is_uo_mask_checked) {
        const bd_uo_mask_index_e uo_mask_idx = UO_MASK_TIME_SEARCH_MASK_INDEX;

        if (_is_uo_masked(bd, uo_mask_idx)) {
            BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_seek_time(%" PRIu64 ") UO %d restricted\n", tick, uo_mask_idx);
            _bdj_event(bd, BDJ_EVENT_UO_MASKED, uo_mask_idx);
            return -1;
        }
    }

    if (bd->title &&
        tick < bd->title->duration) {

        _change_angle(bd);

        // Find the closest access unit to the requested position
        clip = nav_time_search(bd->title, (uint32_t)tick, &clip_pkt, &out_pkt);

        _seek_internal(bd, clip, out_pkt, clip_pkt);

    } else {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_seek_time(%" PRIu64 ") failed\n", tick);
    }

    return bd->s_pos;
}

int64_t bd_seek_time(BLURAY *bd, uint64_t tick)
{
    int64_t ret = 0;

    bd_mutex_lock(&bd->mutex);

    ret = _seek_time(bd, tick, BLURAY_PLAYER_SETTING_UO_RESTRICTION_SAFE <= bd->uo_restriction_level);

    bd_mutex_unlock(&bd->mutex);

    return ret;
}

uint64_t bd_tell_time(BLURAY *bd)
{
    uint32_t clip_pkt = 0, out_pkt = 0, out_time = 0;
    const NAV_CLIP *clip;

    if (!bd) {
        return 0;
    }

    bd_mutex_lock(&bd->mutex);

    if (bd->title) {
        clip = nav_packet_search(bd->title, SPN(bd->s_pos), &clip_pkt, &out_pkt, &out_time);
        if (clip) {
            out_time += clip->title_time;
        }
    }

    bd_mutex_unlock(&bd->mutex);

    return ((uint64_t)out_time) * 2;
}

int64_t bd_seek_chapter(BLURAY *bd, unsigned chapter)
{
    int is_uo_mask_checked;
    uint32_t clip_pkt, out_pkt;
    const NAV_CLIP *clip;
    int64_t ret_pos;

    bd_mutex_lock(&bd->mutex);
    is_uo_mask_checked = (BLURAY_PLAYER_SETTING_UO_RESTRICTION_SAFE < bd->uo_restriction_level);

    ret_pos = bd->s_pos; // Return current position by default
    if (bd->title && chapter < bd->title->chap_list.count) {
        /* Check chapter jump direction for UO restriction */
        const bd_uo_mask_index_e uo_mask_idx = UO_MASK_CHAPTER_SEARCH_INDEX;

        _change_angle(bd);

        if (is_uo_mask_checked && _is_uo_masked(bd, uo_mask_idx)) {
            BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_seek_chapter(%u) UO %d restricted\n", chapter, uo_mask_idx);
            _bdj_event(bd, BDJ_EVENT_UO_MASKED, uo_mask_idx);
            ret_pos = -1;
        } else {
            /* Seek chapter */
            _change_angle(bd);

            // Find the closest access unit to the requested position
            clip = nav_chapter_search(bd->title, chapter, &clip_pkt, &out_pkt);

            _seek_internal(bd, clip, out_pkt, clip_pkt);
            ret_pos = bd->s_pos; // Update
        }
    } else {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_seek_chapter(%u) failed\n", chapter);
    }

    bd_mutex_unlock(&bd->mutex);

    return ret_pos;
}

int64_t bd_chapter_pos(BLURAY *bd, unsigned chapter)
{
    uint32_t clip_pkt, out_pkt;
    int64_t ret = -1;

    bd_mutex_lock(&bd->mutex);

    if (bd->title &&
        chapter < bd->title->chap_list.count) {

        // Find the closest access unit to the requested position
        nav_chapter_search(bd->title, chapter, &clip_pkt, &out_pkt);
        ret = (int64_t)out_pkt * 192;
    }

    bd_mutex_unlock(&bd->mutex);

    return ret;
}

uint32_t bd_get_current_chapter(BLURAY *bd)
{
    uint32_t ret = 0;

    bd_mutex_lock(&bd->mutex);

    if (bd->title) {
        ret = nav_chapter_get_current(bd->title, SPN(bd->s_pos));
    }

    bd_mutex_unlock(&bd->mutex);

    return ret;
}

int64_t bd_seek_playitem(BLURAY *bd, unsigned clip_ref)
{
    uint32_t clip_pkt, out_pkt;
    const NAV_CLIP *clip;

    bd_mutex_lock(&bd->mutex);

    if (bd->title &&
        clip_ref < bd->title->clip_list.count) {

      _change_angle(bd);

      clip     = &bd->title->clip_list.clip[clip_ref];
      clip_pkt = clip->start_pkt;
      out_pkt  = clip->title_pkt;

      _seek_internal(bd, clip, out_pkt, clip_pkt);

    } else {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_seek_playitem(%u) failed\n", clip_ref);
    }

    bd_mutex_unlock(&bd->mutex);

    return bd->s_pos;
}

int64_t bd_seek_mark(BLURAY *bd, unsigned mark)
{
    uint32_t clip_pkt, out_pkt;
    const NAV_CLIP *clip;

    bd_mutex_lock(&bd->mutex);

    if (bd->title &&
        mark < bd->title->mark_list.count) {

        _change_angle(bd);

        // Find the closest access unit to the requested position
        clip = nav_mark_search(bd->title, mark, &clip_pkt, &out_pkt);

        _seek_internal(bd, clip, out_pkt, clip_pkt);

    } else {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_seek_mark(%u) failed\n", mark);
    }

    bd_mutex_unlock(&bd->mutex);

    return bd->s_pos;
}

int64_t bd_seek(BLURAY *bd, uint64_t pos)
{
    uint32_t pkt, clip_pkt, out_pkt, out_time;
    const NAV_CLIP *clip;

    bd_mutex_lock(&bd->mutex);

    if (bd->title &&
        pos < (uint64_t)bd->title->packets * 192) {

        pkt = SPN(pos);

        _change_angle(bd);

        // Find the closest access unit to the requested position
        clip = nav_packet_search(bd->title, pkt, &clip_pkt, &out_pkt, &out_time);

        _seek_internal(bd, clip, out_pkt, clip_pkt);
    }

    bd_mutex_unlock(&bd->mutex);

    return bd->s_pos;
}

uint64_t bd_get_title_size(BLURAY *bd)
{
    uint64_t ret = 0;

    if (!bd) {
        return 0;
    }

    bd_mutex_lock(&bd->mutex);

    if (bd->title) {
        ret = (uint64_t)bd->title->packets * 192;
    }

    bd_mutex_unlock(&bd->mutex);

    return ret;
}

uint64_t bd_tell(BLURAY *bd)
{
    uint64_t ret = 0;

    if (!bd) {
        return 0;
    }

    bd_mutex_lock(&bd->mutex);

    ret = bd->s_pos;

    bd_mutex_unlock(&bd->mutex);

    return ret;
}

/*
 * read
 */

static int64_t _clip_seek_time(BLURAY *bd, uint32_t tick)
{
    uint32_t clip_pkt, out_pkt;

    if (!bd->title || !bd->st0.clip) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_clip_seek_time(): no playlist playing\n");
        return -1;
    }

    if (tick >= bd->st0.clip->out_time) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_clip_seek_time(): timestamp after clip end (%u < %u)\n",
                 bd->st0.clip->out_time, tick);
        return -1;
    }

    // Find the closest access unit to the requested position
    nav_clip_time_search(bd->st0.clip, tick, &clip_pkt, &out_pkt);

    _seek_internal(bd, bd->st0.clip, out_pkt, clip_pkt);

    return bd->s_pos;
}

static int _bd_read(BLURAY *bd, unsigned char *buf, int len)
{
    BD_STREAM *st = &bd->st0;
    int out_len = 0;

        while (len > 0) {
            uint32_t clip_pkt;

            unsigned int size = len;
            // Do we need to read more data?
            clip_pkt = SPN(st->clip_pos);
            if (bd->seamless_angle_change) {
                if (clip_pkt >= bd->angle_change_pkt) {
                    if (clip_pkt >= st->clip->end_pkt) {
                        st->clip = nav_next_clip(bd->title, st->clip);
                        if (!_open_m2ts(bd, st)) {
                            return -1;
                        }
                        bd->s_pos = (uint64_t)st->clip->title_pkt * 192L;
                    } else {
                        _change_angle(bd);
                        _clip_seek_time(bd, bd->angle_change_time);
                    }
                    bd->seamless_angle_change = 0;
                } else {
                    uint64_t angle_pos;

                    angle_pos = (uint64_t)bd->angle_change_pkt * 192L;
                    if (angle_pos - st->clip_pos < size) {
                        size = (unsigned int)(angle_pos - st->clip_pos);
                    }
                }
            }
            if (st->int_buf_off == 6144 || clip_pkt >= st->clip->end_pkt) {

                // Do we need to get the next clip?
                if (clip_pkt >= st->clip->end_pkt) {

                    // split read()'s at clip boundary
                    if (out_len) {
                        return out_len;
                    }

                    // handle still mode clips
                    if (st->clip->still_mode == BLURAY_STILL_INFINITE) {
                        _queue_event(bd, BD_EVENT_STILL_TIME, 0);
                        return 0;
                    }
                    if (st->clip->still_mode == BLURAY_STILL_TIME) {
                        if (bd->event_queue) {
                            _queue_event(bd, BD_EVENT_STILL_TIME, st->clip->still_time);
                            return 0;
                        }
                    }

                    // find next clip
                    st->clip = nav_next_clip(bd->title, st->clip);
                    if (st->clip == NULL) {
                        BD_DEBUG(DBG_BLURAY | DBG_STREAM, "End of title\n");
                        _queue_event(bd, BD_EVENT_END_OF_TITLE, 0);
                        bd->end_of_playlist |= 1;
                        return 0;
                    }
                    if (!_open_m2ts(bd, st)) {
                        return -1;
                    }

                    if (st->clip->connection == CONNECT_NON_SEAMLESS) {
                        /* application layer demuxer buffers must be reset here */
                        _queue_event(bd, BD_EVENT_DISCONTINUITY, st->clip->in_time);
                    }

                }

                int r = _read_block(bd, st, bd->int_buf);
                if (r > 0) {
                    _open3d_mvc_process_block_locked(bd, bd->int_buf);

                    if (st->ig_pid > 0) {
                        if (gc_decode_ts(bd->graphics_controller, st->ig_pid, bd->int_buf, 1, -1) > 0) {
                            /* initialize menus */
                            _run_gc(bd, GC_CTRL_INIT_MENU, 0);
                        }
                    }
                    if (st->pg_pid > 0) {
                        if (gc_decode_ts(bd->graphics_controller, st->pg_pid, bd->int_buf, 1, -1) > 0) {
                            /* render subtitles */
                            gc_run(bd->graphics_controller, GC_CTRL_PG_UPDATE, 0, NULL);
                        }
                    }
                    if (bd->st_textst.clip) {
                        _update_textst_timer(bd);
                    }

                    st->int_buf_off = st->clip_pos % 6144;

                } else if (r == 0) {
                    /* recoverable error (EOF, broken block) */
                    return out_len;
                } else {
                    /* fatal error */
                    return -1;
                }

                /* finetune seek point (avoid skipping PAT/PMT/PCR) */
                if (BD_UNLIKELY(st->seek_flag)) {
                    st->seek_flag = 0;

                    /* rewind if previous packets contain PAT/PMT/PCR */
                    while (st->int_buf_off >= 192 && TS_PID(bd->int_buf + st->int_buf_off - 192) <= HDMV_PID_PCR) {
                        st->clip_pos -= 192;
                        st->int_buf_off -= 192;
                        bd->s_pos -= 192;
                    }
                }

            }
            if (size > (unsigned int)6144 - st->int_buf_off) {
                size = 6144 - st->int_buf_off;
            }

            /* cut read at clip end packet */
            uint32_t new_clip_pkt = SPN(st->clip_pos + size);
            if (new_clip_pkt > st->clip->end_pkt) {
                BD_DEBUG(DBG_STREAM, "cut %d bytes at end of block\n", (new_clip_pkt - st->clip->end_pkt) * 192);
                size -= (new_clip_pkt - st->clip->end_pkt) * 192;
            }

            /* copy chunk */
            memcpy(buf, bd->int_buf + st->int_buf_off, size);
            buf += size;
            len -= size;
            out_len += size;
            st->clip_pos += size;
            st->int_buf_off += size;
            bd->s_pos += size;
        }

        BD_DEBUG(DBG_STREAM, "%d bytes read OK!\n", out_len);
        return out_len;
}

static int _bd_read_locked(BLURAY *bd, unsigned char *buf, int len)
{
    BD_STREAM *st = &bd->st0;
    int r;

    if (!st->fp) {
        BD_DEBUG(DBG_STREAM | DBG_CRIT, "bd_read(): no valid title selected!\n");
        return -1;
    }

    if (st->clip == NULL) {
        // We previously reached the last clip.  Nothing
        // else to read.
        _queue_event(bd, BD_EVENT_END_OF_TITLE, 0);
        bd->end_of_playlist |= 1;
        return 0;
    }

    BD_DEBUG(DBG_STREAM, "Reading [%d bytes] at %" PRIu64 "...\n", len, bd->s_pos);

    r = _bd_read(bd, buf, len);

    /* mark tracking */
    if (bd->next_mark >= 0 && bd->s_pos > bd->next_mark_pos) {
        _playmark_reached(bd);
    }

    return r;
}

int bd_read(BLURAY *bd, unsigned char *buf, int len)
{
    int result;

    bd_mutex_lock(&bd->mutex);
    result = _bd_read_locked(bd, buf, len);
    bd_mutex_unlock(&bd->mutex);

    return result;
}

int bd_read_skip_still(BLURAY *bd)
{
    BD_STREAM *st = &bd->st0;
    int ret = 0;

    bd_mutex_lock(&bd->mutex);

    if (st->clip) {
        if (st->clip->still_mode == BLURAY_STILL_TIME) {
            st->clip = nav_next_clip(bd->title, st->clip);
            if (st->clip) {
                ret = _open_m2ts(bd, st);
            }
        }
    }

    bd_mutex_unlock(&bd->mutex);

    return ret;
}

/*
 * synchronous sub paths
 */

static int _preload_textst_subpath(BLURAY *bd)
{
    uint8_t        char_code      = BLURAY_TEXT_CHAR_CODE_UTF8;
    int            textst_subpath = -1;
    unsigned       textst_subclip = 0;
    uint16_t       textst_pid     = 0;
    unsigned       ii;
    char          *font_file;

    if (!bd->graphics_controller) {
        return 0;
    }

    if (!bd->decode_pg || !bd->title) {
        return 0;
    }

    _find_pg_stream(bd, &textst_pid, &textst_subpath, &textst_subclip, &char_code);
    if (textst_subpath < 0) {
        return 0;
    }
    if (textst_pid != 0x1800) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_preload_textst_subpath(): ignoring pid 0x%x\n", (unsigned)textst_pid);
        return 0;
    }

    if ((unsigned)textst_subpath >= bd->title->sub_path_count) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_preload_textst_subpath(): invalid subpath id\n");
        return -1;
    }
    if (textst_subclip >= bd->title->sub_path[textst_subpath].clip_list.count) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_preload_textst_subpath(): invalid subclip id\n");
        return -1;
    }

    if (bd->st_textst.clip == &bd->title->sub_path[textst_subpath].clip_list.clip[textst_subclip]) {
        BD_DEBUG(DBG_BLURAY, "_preload_textst_subpath(): subpath already loaded");
        return 1;
    }

    gc_run(bd->graphics_controller, GC_CTRL_PG_RESET, 0, NULL);

    bd->st_textst.clip = &bd->title->sub_path[textst_subpath].clip_list.clip[textst_subclip];
    if (!bd->st_textst.clip->cl) {
        /* required for fonts */
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_preload_textst_subpath(): missing clip data\n");
        return -1;
    }

    if (!_preload_m2ts(bd, &bd->st_textst)) {
        _close_preload(&bd->st_textst);
        return 0;
    }

    gc_decode_ts(bd->graphics_controller, textst_pid, bd->st_textst.buf, SPN(bd->st_textst.clip_size) / 32, -1);

    /* set fonts and encoding from clip info */
    gc_add_font(bd->graphics_controller, NULL, -1); /* reset fonts */
    for (ii = 0; NULL != (font_file = nav_clip_textst_font(bd->st_textst.clip, ii)); ii++) {
        uint8_t *data = NULL;
        size_t size = disc_read_file(bd->disc, "BDMV" DIR_SEP "AUXDATA", font_file, &data);
        if (data && size > 0 && gc_add_font(bd->graphics_controller, data, size) < 0) {
            X_FREE(data);
        }
        X_FREE(font_file);
    }

    gc_run(bd->graphics_controller, GC_CTRL_PG_CHARCODE, char_code, NULL);

    /* start presentation timer */
    _init_textst_timer(bd);

    return 1;
}

/*
 * preloader for asynchronous sub paths
 */

static int _find_ig_stream(BLURAY *bd, uint16_t *pid, int *sub_path_idx, unsigned *sub_clip_idx)
{
    unsigned  main_clip_idx = bd->st0.clip ? bd->st0.clip->ref : 0;
    unsigned  ig_stream = bd_psr_read(bd->regs, PSR_IG_STREAM_ID);
    const MPLS_STN *stn = &bd->title->pl->play_item[main_clip_idx].stn;

    if (ig_stream > 0 && ig_stream <= stn->num_ig) {
        ig_stream--; /* stream number to table index */
        if (stn->ig[ig_stream].stream_type == 2) {
            *sub_path_idx = stn->ig[ig_stream].subpath_id;
            *sub_clip_idx = stn->ig[ig_stream].subclip_id;
        }
        *pid = stn->ig[ig_stream].pid;

        BD_DEBUG(DBG_BLURAY, "_find_ig_stream(): current IG stream pid 0x%04x sub-path %d\n",
              *pid, *sub_path_idx);
        return 1;
    }

    return 0;
}

static int _preload_ig_subpath(BLURAY *bd)
{
    int      ig_subpath = -1;
    unsigned ig_subclip = 0;
    uint16_t ig_pid     = 0;

    if (!bd->graphics_controller) {
        return 0;
    }

    _find_ig_stream(bd, &ig_pid, &ig_subpath, &ig_subclip);

    if (ig_subpath < 0) {
        return 0;
    }

    if (ig_subclip >= bd->title->sub_path[ig_subpath].clip_list.count) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_preload_ig_subpath(): invalid subclip id\n");
        return -1;
    }

    if (bd->st_ig.clip == &bd->title->sub_path[ig_subpath].clip_list.clip[ig_subclip]) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_preload_ig_subpath(): subpath already loaded");
        //return 1;
    }

    bd->st_ig.clip = &bd->title->sub_path[ig_subpath].clip_list.clip[ig_subclip];

    if (bd->title->sub_path[ig_subpath].clip_list.count > 1) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_preload_ig_subpath(): multi-clip sub paths not supported\n");
    }

    if (!_preload_m2ts(bd, &bd->st_ig)) {
        _close_preload(&bd->st_ig);
        return 0;
    }

    return 1;
}

static int _preload_subpaths(BLURAY *bd)
{
    _close_preload(&bd->st_ig);
    _close_preload(&bd->st_textst);

    if (bd->title->sub_path_count <= 0) {
        return 0;
    }

    return _preload_ig_subpath(bd) | _preload_textst_subpath(bd);
}

static int _init_ig_stream(BLURAY *bd)
{
    int      ig_subpath = -1;
    unsigned ig_subclip = 0;
    uint16_t ig_pid     = 0;

    bd->st0.ig_pid = 0;

    if (!bd->title || !bd->graphics_controller) {
        return 0;
    }

    _find_ig_stream(bd, &ig_pid, &ig_subpath, &ig_subclip);

    /* decode already preloaded IG sub-path */
    if (bd->st_ig.clip) {
        gc_decode_ts(bd->graphics_controller, ig_pid, bd->st_ig.buf, SPN(bd->st_ig.clip_size) / 32, -1);
        return 1;
    }

    /* store PID of main path embedded IG stream */
    if (ig_subpath < 0) {
        bd->st0.ig_pid = ig_pid;
        return 1;
    }

    return 0;
}

/*
 * select title / angle
 */

static void _close_playlist(BLURAY *bd)
{
    if (bd->graphics_controller) {
        gc_run(bd->graphics_controller, GC_CTRL_RESET, 0, NULL);
    }

    /* stopping playback in middle of playlist ? */
    if (bd->title && bd->st0.clip) {
        if (bd->st0.clip->ref < bd->title->clip_list.count - 1) {
            /* not last clip of playlist */
            BD_DEBUG(DBG_BLURAY, "close playlist (not last clip)\n");
            _queue_event(bd, BD_EVENT_PLAYLIST_STOP, 0);
        } else {
            /* last clip of playlist */
            int clip_pkt = SPN(bd->st0.clip_pos);
            int skip = bd->st0.clip->end_pkt - clip_pkt;
            BD_DEBUG(DBG_BLURAY, "close playlist (last clip), packets skipped %d\n", skip);
            if (skip > 100) {
                _queue_event(bd, BD_EVENT_PLAYLIST_STOP, 0);
            }
        }
    }

    _close_m2ts(&bd->st0);
    _close_preload(&bd->st_ig);
    _close_preload(&bd->st_textst);
    _open3d_mvc_reset_runtime(bd);

    nav_title_close(&bd->title);

    bd->st0.clip = NULL;

    /* reset UO mask */
    memset(&bd->st0.uo_mask, 0, sizeof(BD_UO_MASK));
    memset(&bd->gc_uo_mask,  0, sizeof(BD_UO_MASK));
    _update_uo_mask(bd);
}

static int _add_known_playlist(BD_DISC *p, const char *mpls_id)
{
    char *old_mpls_ids;
    char *new_mpls_ids = NULL;
    int result = -1;

    old_mpls_ids = disc_property_get(p, DISC_PROPERTY_PLAYLISTS);
    if (!old_mpls_ids) {
        return disc_property_put(p, DISC_PROPERTY_PLAYLISTS, mpls_id);
    }

    /* no duplicates */
    if (str_strcasestr(old_mpls_ids, mpls_id)) {
        goto out;
    }

    new_mpls_ids = str_printf("%s,%s", old_mpls_ids, mpls_id);
    if (new_mpls_ids) {
        result = disc_property_put(p, DISC_PROPERTY_PLAYLISTS, new_mpls_ids);
    }

 out:
    X_FREE(old_mpls_ids);
    X_FREE(new_mpls_ids);
    return result;
}

static int _open_playlist(BLURAY *bd, unsigned playlist, unsigned angle)
{
    char f_name[12];

    if (playlist > 99999) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Invalid playlist %u!\n", playlist);
        return 0;
    }
    if (snprintf(f_name, sizeof(f_name), "%05u.mpls", playlist) != 10) {
        return 0;
    }

    if (!bd->title_list && bd->title_type == title_undef) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "open_playlist(%s): bd_play() or bd_get_titles() not called\n", f_name);
        disc_event(bd->disc, DISC_EVENT_START, bd->disc_info.num_titles);
    }

    _close_playlist(bd);

    bd->title = nav_title_open(bd->disc, f_name, angle);
    if (bd->title == NULL) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Unable to open title %s!\n", f_name);
        return 0;
    }

    bd->seamless_angle_change = 0;
    bd->s_pos = 0;
    bd->end_of_playlist = 0;
    bd->st0.ig_pid = 0;

    // Get the initial clip of the playlist
    bd->st0.clip = nav_next_clip(bd->title, NULL);

    _update_playlist_psrs(bd);

    if (_open_m2ts(bd, &bd->st0)) {
        BD_DEBUG(DBG_BLURAY, "Title %s selected\n", f_name);

        _find_next_playmark(bd);

        _preload_subpaths(bd);

        bd->st0.seek_flag = 1;

        /* remember played playlists when using menus */
        if (bd->title_type != title_undef) {
            _add_known_playlist(bd->disc, bd->title->name);
        }

        /* inform application about current streams (redundant) */
        bd_psr_lock(bd->regs);
        _queue_event(bd, BD_EVENT_AUDIO_STREAM, bd_psr_read(bd->regs, PSR_PRIMARY_AUDIO_ID));
        {
            uint32_t pgreg = bd_psr_read(bd->regs, PSR_PG_STREAM);
            _queue_event(bd, BD_EVENT_PG_TEXTST,        !!(pgreg & 0x80000000));
            _queue_event(bd, BD_EVENT_PG_TEXTST_STREAM,    pgreg & 0xfff);
        }
        bd_psr_unlock(bd->regs);

        return 1;
    }
    return 0;
}

int bd_select_playlist(BLURAY *bd, uint32_t playlist)
{
    int result;

    bd_mutex_lock(&bd->mutex);

    if (bd->title_list) {
        /* update current title */
        unsigned i;
        for (i = 0; i < bd->title_list->count; i++) {
            if (playlist == bd->title_list->title_info[i].mpls_id) {
                bd->title_idx = i;
                break;
            }
        }
    }

    result = _open_playlist(bd, playlist, 0);

    bd_mutex_unlock(&bd->mutex);

    return result;
}

/* BD-J callback */
static int _play_playlist_at(BLURAY *bd, int playlist, int playitem, int playmark, int64_t time)
{
    if (playlist < 0) {
        _close_playlist(bd);
        return 1;
    }

    if (!_open_playlist(bd, playlist, 0)) {
        return 0;
    }

    bd->bdj_wait_start = 1;  /* playback is triggered by bd_select_rate() */

    bd_bdj_seek(bd, playitem, playmark, time);

    return 1;
}

/* BD-J callback */
int bd_play_playlist_at(BLURAY *bd, int playlist, int playitem, int playmark, int64_t time)
{
    int result;

    /* select + seek should be atomic (= player can't read data between select and seek to start position) */
    bd_mutex_lock(&bd->mutex);
    result = _play_playlist_at(bd, playlist, playitem, playmark, time);
    bd_mutex_unlock(&bd->mutex);

    return result;
}

// Select a title for playback
// The title index is an index into the list
// established by bd_get_titles()
static int _select_title(BLURAY *bd, uint32_t title_idx)
{
    // Open the playlist
    if (bd->title_list == NULL) {
        BD_DEBUG(DBG_CRIT | DBG_BLURAY, "Title list not yet read!\n");
        return 0;
    }
    if (bd->title_list->count <= title_idx) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Invalid title index %d!\n", title_idx);
        return 0;
    }

    bd->title_idx = title_idx;

    return _open_playlist(bd, bd->title_list->title_info[title_idx].mpls_id, 0);
}

int bd_select_title(BLURAY *bd, uint32_t title_idx)
{
    int result;

    bd_mutex_lock(&bd->mutex);
    result = _select_title(bd, title_idx);
    bd_mutex_unlock(&bd->mutex);

    return result;
}

uint32_t bd_get_current_title(BLURAY *bd)
{
    return bd->title_idx;
}

static int _bd_select_angle(BLURAY *bd, unsigned angle, uint8_t is_uo_mask_checked)
{
    unsigned orig_angle;

    if (bd->title == NULL) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Can't select angle: title not yet selected!\n");
        return 0;
    }

    /* Check UO mask */
    if (is_uo_mask_checked) {
        const bd_uo_mask_index_e uo_mask_idx = UO_MASK_ANGLE_CHANGE_MASK_INDEX;

        if (_is_uo_masked(bd, uo_mask_idx)) {
            BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_select_angle(%u) UO %d restricted\n", angle, uo_mask_idx);
            _bdj_event(bd, BDJ_EVENT_UO_MASKED, uo_mask_idx);
            return 0;
        }
    }

    orig_angle = bd->title->angle;

    nav_set_angle(bd->title, angle);

    if (orig_angle == bd->title->angle) {
        return 1;
    }

    bd_psr_write(bd->regs, PSR_ANGLE_NUMBER, bd->title->angle + 1);

    if (!_open_m2ts(bd, &bd->st0)) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Error selecting angle %d !\n", angle);
        return 0;
    }

    return 1;
}

int bd_select_angle(BLURAY *bd, unsigned angle)
{
    int result;

    bd_mutex_lock(&bd->mutex);
    result = _bd_select_angle(bd, angle, BLURAY_PLAYER_SETTING_UO_RESTRICTION_COMPLIANT <= bd->uo_restriction_level);
    bd_mutex_unlock(&bd->mutex);

    return result;
}

unsigned bd_get_current_angle(BLURAY *bd)
{
    int angle = 0;

    bd_mutex_lock(&bd->mutex);
    if (bd->title) {
        angle = bd->title->angle;
    }
    bd_mutex_unlock(&bd->mutex);

    return angle;
}


void bd_seamless_angle_change(BLURAY *bd, unsigned angle)
{
    uint32_t clip_pkt;

    bd_mutex_lock(&bd->mutex);

    clip_pkt = SPN(bd->st0.clip_pos + 191);
    bd->angle_change_pkt = nav_clip_angle_change_search(bd->st0.clip, clip_pkt,
                                                        &bd->angle_change_time);
    bd->request_angle = angle;
    bd->seamless_angle_change = 1;

    bd_mutex_unlock(&bd->mutex);
}

/*
 * title lists
 */

uint32_t bd_get_titles(BLURAY *bd, uint8_t flags, uint32_t min_title_length)
{
    NAV_TITLE_LIST *title_list;
    uint32_t count;

    if (!bd) {
        return 0;
    }

    title_list = nav_get_title_list(bd->disc, flags, min_title_length);
    if (!title_list) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "nav_get_title_list(%s) failed\n", disc_root(bd->disc));
        return 0;
    }

    bd_mutex_lock(&bd->mutex);

    nav_free_title_list(&bd->title_list);
    bd->title_list = title_list;

    disc_event(bd->disc, DISC_EVENT_START, bd->disc_info.num_titles);
    count = bd->title_list->count;

    bd_mutex_unlock(&bd->mutex);

    return count;
}

int bd_get_main_title(BLURAY *bd)
{
    int main_title_idx = -1;

    if (!bd) {
        return -1;
    }

    bd_mutex_lock(&bd->mutex);

    if (bd->title_type != title_undef) {
        BD_DEBUG(DBG_CRIT | DBG_BLURAY, "bd_get_main_title() can't be used with BluRay menus\n");
    }

    if (bd->title_list == NULL) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Title list not yet read!\n");
    } else {
        main_title_idx = bd->title_list->main_title_idx;
    }

    bd_mutex_unlock(&bd->mutex);

    return main_title_idx;
}

static int _copy_streams(const NAV_CLIP *clip, BLURAY_STREAM_INFO **pstreams,
                         const MPLS_STREAM *si, int count)
{
    BLURAY_STREAM_INFO *streams;
    int ii;

    if (!count) {
        return 1;
    }
    streams = *pstreams = calloc(count, sizeof(BLURAY_STREAM_INFO));
    if (!streams) {
        return 0;
    }

    for (ii = 0; ii < count; ii++) {
        streams[ii].coding_type = si[ii].coding_type;
        streams[ii].format = si[ii].format;
        streams[ii].rate = si[ii].rate;
        streams[ii].char_code = si[ii].char_code;
        memcpy(streams[ii].lang, si[ii].lang, 4);
        streams[ii].pid = si[ii].pid;
        streams[ii].aspect = nav_clip_lookup_aspect(clip, si[ii].pid);
        if ((si->stream_type == 2) || (si->stream_type == 3))
            streams[ii].subpath_id = si->subpath_id;
        else
            streams[ii].subpath_id = -1;
    }

    return 1;
}

static BLURAY_TITLE_INFO* _fill_title_info(NAV_TITLE* title, uint32_t title_idx, uint32_t playlist)
{
    BLURAY_TITLE_INFO *title_info;
    unsigned int ii;

    title_info = calloc(1, sizeof(BLURAY_TITLE_INFO));
    if (!title_info) {
        goto error;
    }
    title_info->idx = title_idx;
    title_info->playlist = playlist;
    title_info->duration = (uint64_t)title->duration * 2;
    title_info->angle_count = title->angle_count;
    title_info->chapter_count = title->chap_list.count;
    if (title_info->chapter_count) {
        title_info->chapters = calloc(title_info->chapter_count, sizeof(BLURAY_TITLE_CHAPTER));
        if (!title_info->chapters) {
            goto error;
        }
        for (ii = 0; ii < title_info->chapter_count; ii++) {
            title_info->chapters[ii].idx = ii;
            title_info->chapters[ii].start = (uint64_t)title->chap_list.mark[ii].title_time * 2;
            title_info->chapters[ii].duration = (uint64_t)title->chap_list.mark[ii].duration * 2;
            title_info->chapters[ii].offset = (uint64_t)title->chap_list.mark[ii].title_pkt * 192L;
            title_info->chapters[ii].clip_ref = title->chap_list.mark[ii].clip_ref;
        }
    }
    title_info->mark_count = title->mark_list.count;
    if (title_info->mark_count) {
        title_info->marks = calloc(title_info->mark_count, sizeof(BLURAY_TITLE_MARK));
        if (!title_info->marks) {
            goto error;
        }
        for (ii = 0; ii < title_info->mark_count; ii++) {
            title_info->marks[ii].idx = ii;
            title_info->marks[ii].type = title->mark_list.mark[ii].mark_type;
            title_info->marks[ii].start = (uint64_t)title->mark_list.mark[ii].title_time * 2;
            title_info->marks[ii].duration = (uint64_t)title->mark_list.mark[ii].duration * 2;
            title_info->marks[ii].offset = (uint64_t)title->mark_list.mark[ii].title_pkt * 192L;
            title_info->marks[ii].clip_ref = title->mark_list.mark[ii].clip_ref;
        }
    }
    title_info->clip_count = title->clip_list.count;
    if (title_info->clip_count) {
        title_info->clips = calloc(title_info->clip_count, sizeof(BLURAY_CLIP_INFO));
        if (!title_info->clips) {
            goto error;
        }
        for (ii = 0; ii < title_info->clip_count; ii++) {
            BLURAY_CLIP_INFO *ci = &title_info->clips[ii];
            const MPLS_PI *pi = &title->pl->play_item[ii];
            const NAV_CLIP *nc = &title->clip_list.clip[ii];

            memcpy(ci->clip_id, pi->clip->clip_id, sizeof(ci->clip_id));
            ci->pkt_count = nc->end_pkt - nc->start_pkt;
            ci->start_time = (uint64_t)nc->title_time * 2;
            ci->in_time = (uint64_t)pi->in_time * 2;
            ci->out_time = (uint64_t)pi->out_time * 2;
            ci->still_mode = pi->still_mode;
            ci->still_time = pi->still_time;
            ci->video_stream_count = pi->stn.num_video;
            ci->audio_stream_count = pi->stn.num_audio;
            ci->pg_stream_count = pi->stn.num_pg + pi->stn.num_pip_pg;
            ci->ig_stream_count = pi->stn.num_ig;
            ci->sec_video_stream_count = pi->stn.num_secondary_video;
            ci->sec_audio_stream_count = pi->stn.num_secondary_audio;
            if (!_copy_streams(nc, &ci->video_streams, pi->stn.video, ci->video_stream_count) ||
                !_copy_streams(nc, &ci->audio_streams, pi->stn.audio, ci->audio_stream_count) ||
                !_copy_streams(nc, &ci->pg_streams, pi->stn.pg, ci->pg_stream_count) ||
                !_copy_streams(nc, &ci->ig_streams, pi->stn.ig, ci->ig_stream_count) ||
                !_copy_streams(nc, &ci->sec_video_streams, pi->stn.secondary_video, ci->sec_video_stream_count) ||
                !_copy_streams(nc, &ci->sec_audio_streams, pi->stn.secondary_audio, ci->sec_audio_stream_count)) {

                goto error;
            }
        }
    }

    title_info->mvc_base_view_r_flag = title->pl->app_info.mvc_base_view_r_flag;

    return title_info;

 error:
    BD_DEBUG(DBG_CRIT, "Out of memory\n");
    bd_free_title_info(title_info);
    return NULL;
}

static BLURAY_TITLE_INFO *_get_mpls_info(BLURAY *bd, uint32_t title_idx, uint32_t playlist, unsigned angle)
{
    NAV_TITLE *title;
    BLURAY_TITLE_INFO *title_info;
    char mpls_name[11];

    if (playlist > 99999) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Invalid playlist %u!\n", playlist);
        return NULL;
    }

    if (snprintf(mpls_name, sizeof(mpls_name), "%05u.mpls", playlist) != 10) {
        return NULL;
    }

    /* current title ? => no need to load mpls file */
    bd_mutex_lock(&bd->mutex);
    if (bd->title && bd->title->angle == angle && !strcmp(bd->title->name, mpls_name)) {
        title_info = _fill_title_info(bd->title, title_idx, playlist);
        bd_mutex_unlock(&bd->mutex);
        return title_info;
    }
    bd_mutex_unlock(&bd->mutex);

    title = nav_title_open(bd->disc, mpls_name, angle);
    if (title == NULL) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Unable to open title %s!\n", mpls_name);
        return NULL;
    }

    title_info = _fill_title_info(title, title_idx, playlist);

    nav_title_close(&title);
    return title_info;
}

BLURAY_TITLE_INFO* bd_get_title_info(BLURAY *bd, uint32_t title_idx, unsigned angle)
{
    int  mpls_id = -1;

    bd_mutex_lock(&bd->mutex);

    if (bd->title_list == NULL) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Title list not yet read!\n");
    } else if (bd->title_list->count <= title_idx) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Invalid title index %d!\n", title_idx);
    } else {
        mpls_id = bd->title_list->title_info[title_idx].mpls_id;
    }

    bd_mutex_unlock(&bd->mutex);

    if (mpls_id < 0)
        return NULL;

    return _get_mpls_info(bd, title_idx, mpls_id, angle);
}

BLURAY_TITLE_INFO* bd_get_playlist_info(BLURAY *bd, uint32_t playlist, unsigned angle)
{
    return _get_mpls_info(bd, 0, playlist, angle);
}

void bd_free_title_info(BLURAY_TITLE_INFO *title_info)
{
    unsigned int ii;

    if (title_info) {
        X_FREE(title_info->chapters);
        X_FREE(title_info->marks);
        if (title_info->clips) {
            for (ii = 0; ii < title_info->clip_count; ii++) {
                X_FREE(title_info->clips[ii].video_streams);
                X_FREE(title_info->clips[ii].audio_streams);
                X_FREE(title_info->clips[ii].pg_streams);
                X_FREE(title_info->clips[ii].ig_streams);
                X_FREE(title_info->clips[ii].sec_video_streams);
                X_FREE(title_info->clips[ii].sec_audio_streams);
            }
            X_FREE(title_info->clips);
        }
        X_FREE(title_info);
    }
}

/*
 * player settings
 */

int bd_set_player_setting(BLURAY *bd, uint32_t idx, uint32_t value)
{
    static const struct { uint32_t idx; uint32_t  psr; } map[] = {
        { BLURAY_PLAYER_SETTING_PARENTAL,       PSR_PARENTAL },
        { BLURAY_PLAYER_SETTING_AUDIO_CAP,      PSR_AUDIO_CAP },
        { BLURAY_PLAYER_SETTING_AUDIO_LANG,     PSR_AUDIO_LANG },
        { BLURAY_PLAYER_SETTING_PG_LANG,        PSR_PG_AND_SUB_LANG },
        { BLURAY_PLAYER_SETTING_MENU_LANG,      PSR_MENU_LANG },
        { BLURAY_PLAYER_SETTING_COUNTRY_CODE,   PSR_COUNTRY },
        { BLURAY_PLAYER_SETTING_REGION_CODE,    PSR_REGION },
        { BLURAY_PLAYER_SETTING_OUTPUT_PREFER,  PSR_OUTPUT_PREFER },
        { BLURAY_PLAYER_SETTING_DISPLAY_CAP,    PSR_DISPLAY_CAP },
        { BLURAY_PLAYER_SETTING_3D_CAP,         PSR_3D_CAP },
        { BLURAY_PLAYER_SETTING_UHD_CAP,         PSR_UHD_CAP },
        { BLURAY_PLAYER_SETTING_UHD_DISPLAY_CAP, PSR_UHD_DISPLAY_CAP },
        { BLURAY_PLAYER_SETTING_HDR_PREFERENCE,  PSR_UHD_HDR_PREFER },
        { BLURAY_PLAYER_SETTING_SDR_CONV_PREFER, PSR_UHD_SDR_CONV_PREFER },
        { BLURAY_PLAYER_SETTING_VIDEO_CAP,      PSR_VIDEO_CAP },
        { BLURAY_PLAYER_SETTING_TEXT_CAP,       PSR_TEXT_CAP },
        { BLURAY_PLAYER_SETTING_PLAYER_PROFILE, PSR_PROFILE_VERSION },
    };

    unsigned i;
    int result;

    if (idx == BLURAY_PLAYER_SETTING_DECODE_PG) {
        bd_mutex_lock(&bd->mutex);

        bd->decode_pg = !!value;
        result = !bd_psr_write_bits(bd->regs, PSR_PG_STREAM,
                                    (!!value) << 31,
                                    0x80000000);

        bd_mutex_unlock(&bd->mutex);
        return result;
    }

    if (idx == BLURAY_PLAYER_SETTING_PERSISTENT_STORAGE) {
        if (bd->title_type != title_undef) {
            BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Can't disable persistent storage during playback\n");
            return 0;
        }
        bd->bdj_config.no_persistent_storage = !value;
        return 1;
    }

    if (idx == BLURAY_PLAYER_SETTING_UO_RESTRICTION_LEVEL) {
        if (BLURAY_PLAYER_SETTING_UO_RESTRICTION_COMPLIANT < value) {
            BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Invalid UO restriction level\n");
            return 0;
        }

        bd_mutex_lock(&bd->mutex);
        bd->uo_restriction_level = value;
        bd_mutex_unlock(&bd->mutex);
        return 1;
    }

    for (i = 0; i < sizeof(map) / sizeof(map[0]); i++) {
        if (idx == map[i].idx) {
            bd_mutex_lock(&bd->mutex);
            result = !bd_psr_setting_write(bd->regs, map[i].psr, value);
            bd_mutex_unlock(&bd->mutex);
            return result;
        }
    }

    return 0;
}

int bd_set_player_setting_str(BLURAY *bd, uint32_t idx, const char *s)
{
    switch (idx) {
        case BLURAY_PLAYER_SETTING_AUDIO_LANG:
        case BLURAY_PLAYER_SETTING_PG_LANG:
        case BLURAY_PLAYER_SETTING_MENU_LANG:
            return bd_set_player_setting(bd, idx, str_to_uint32(s, 3));

        case BLURAY_PLAYER_SETTING_COUNTRY_CODE:
            return bd_set_player_setting(bd, idx, str_to_uint32(s, 2));

        case BLURAY_PLAYER_CACHE_ROOT:
            bd_mutex_lock(&bd->mutex);
            X_FREE(bd->bdj_config.cache_root);
            bd->bdj_config.cache_root = str_dup(s);
            bd_mutex_unlock(&bd->mutex);
            BD_DEBUG(DBG_BDJ, "Cache root dir set to %s\n", bd->bdj_config.cache_root);
            return 1;

        case BLURAY_PLAYER_PERSISTENT_ROOT:
            bd_mutex_lock(&bd->mutex);
            X_FREE(bd->bdj_config.persistent_root);
            bd->bdj_config.persistent_root = str_dup(s);
            bd_mutex_unlock(&bd->mutex);
            BD_DEBUG(DBG_BDJ, "Persistent root dir set to %s\n", bd->bdj_config.persistent_root);
            return 1;

        case BLURAY_PLAYER_JAVA_HOME:
            bd_mutex_lock(&bd->mutex);
            X_FREE(bd->bdj_config.java_home);
            bd->bdj_config.java_home = s ? str_dup(s) : NULL;
            bd_mutex_unlock(&bd->mutex);
            BD_DEBUG(DBG_BDJ, "Java home set to %s\n", bd->bdj_config.java_home ? bd->bdj_config.java_home : "<auto>");
            return 1;

        default:
            return 0;
    }
}

static int _select_audio_stream(BLURAY *bd, uint32_t stream_id, int is_checked_uo_mask)
{
    /* Check Primary Audio Stream Number Change UO */
    if (is_checked_uo_mask) {
        const bd_uo_mask_index_e uo_mask_idx = UO_MASK_PRIMARY_AUDIO_CHANGE_MASK_INDEX;

        if (_is_uo_masked(bd, uo_mask_idx)) {
            BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_select_audio_stream(%" PRIu32 ") UO %d restricted\n",
                stream_id, uo_mask_idx);
            _bdj_event(bd, BDJ_EVENT_UO_MASKED, uo_mask_idx);
            return 0;
        }
    }

    bd_psr_write(bd->regs, PSR_PRIMARY_AUDIO_ID, stream_id & 0xff);
    return 1;
}

static int _select_pg_textst_stream(BLURAY *bd, uint32_t stream_id, uint32_t enable_flag, int is_checked_uo_mask)
{
    uint32_t psr_initial_value = 0x0;
    uint32_t psr_update_value = 0x0;
    uint32_t psr_update_mask = 0x0;

    /* Get initial PSR value to check for changes */
    psr_initial_value = bd_psr_read(bd->regs, PSR_PG_STREAM);

    /* Check PG textST Enable Disable UO */
    if (!enable_flag != !(psr_initial_value & 0x80000000)) {
        const bd_uo_mask_index_e uo_mask_idx = UO_MASK_PG_TEXTST_ENABLE_DISABLE_MASK_INDEX;

        if (is_checked_uo_mask && _is_uo_masked(bd, uo_mask_idx)) {
            BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_select_pg_textst_stream(%" PRIu32 ", %" PRIu32 ") UO %d restricted\n",
                stream_id, enable_flag, uo_mask_idx);
            _bdj_event(bd, BDJ_EVENT_UO_MASKED, uo_mask_idx);
        } else {
            // Enable/Disable PSR value
            psr_update_value |= (!!enable_flag) << 31;
            psr_update_mask  |= 0x80000000;
        }
    }

    /* Check PG textST Stream Number Change UO */
    if (stream_id != (psr_initial_value & 0xfff)) {
        const bd_uo_mask_index_e uo_mask_idx = UO_MASK_PG_TEXTST_CHANGE_MASK_INDEX;

        if (is_checked_uo_mask && _is_uo_masked(bd, uo_mask_idx)) {
            BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_select_pg_textst_stream(%" PRIu32 ", %" PRIu32 ") UO %d restricted\n",
                stream_id, enable_flag, uo_mask_idx);
            _bdj_event(bd, BDJ_EVENT_UO_MASKED, uo_mask_idx);
        } else {
            psr_update_value |= stream_id & 0xfff;
            psr_update_mask  |= 0xfff;
        }
    }

    if (psr_update_mask) {
        bd_psr_write_bits(bd->regs, PSR_PG_STREAM, psr_update_value, psr_update_mask);
    }

    return !!psr_update_mask;
}

int bd_select_stream(BLURAY *bd, uint32_t stream_type, uint32_t stream_id, uint32_t enable_flag)
{
    int is_checked_uo_mask = 0;
    int ret = -1;

    bd_mutex_lock(&bd->mutex);

    /* Selecting stream should be a safe UO */
    is_checked_uo_mask = (BLURAY_PLAYER_SETTING_UO_RESTRICTION_COMPLIANT <= bd->uo_restriction_level);

    switch (stream_type) {
        case BLURAY_AUDIO_STREAM:
            ret = _select_audio_stream(bd, stream_id, is_checked_uo_mask);
            break;
        case BLURAY_PG_TEXTST_STREAM:
            ret = _select_pg_textst_stream(bd, stream_id, enable_flag, is_checked_uo_mask);
            break;
        /*
        case BLURAY_SECONDARY_VIDEO_STREAM:
        case BLURAY_SECONDARY_AUDIO_STREAM:
        */
    }

    bd_mutex_unlock(&bd->mutex);

    return ret;
}

/*
 * BD-J testing
 */

int bd_start_bdj(BLURAY *bd, const char *start_object)
{
    const BLURAY_TITLE *t;
    unsigned int title_num = atoi(start_object);
    unsigned ii;

    if (!bd) {
        return 0;
    }

    /* first play object ? */
    if (bd->disc_info.first_play_supported) {
        t = bd->disc_info.first_play;
        if (t && t->bdj && t->id_ref == title_num) {
            return _start_bdj(bd, BLURAY_TITLE_FIRST_PLAY);
        }
    }

    /* valid BD-J title from disc index ? */
    if (bd->disc_info.titles) {
        for (ii = 0; ii <= bd->disc_info.num_titles; ii++) {
            t = bd->disc_info.titles[ii];
            if (t && t->bdj && t->id_ref == title_num) {
                return _start_bdj(bd, ii);
            }
        }
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "No %s.bdjo in disc index\n", start_object);
    } else {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "No disc index\n");
    }

    return 0;
 }

void bd_stop_bdj(BLURAY *bd)
{
    bd_mutex_lock(&bd->mutex);
    _close_bdj(bd);
    bd_mutex_unlock(&bd->mutex);
}

/*
 * Navigation mode interface
 */

static void _set_scr(BLURAY *bd, int64_t pts)
{
    if (pts >= 0) {
        uint32_t tick = (uint32_t)(((uint64_t)pts) >> 1);
        _update_time_psr(bd, tick);

    } else if (!bd->app_scr) {
        _update_time_psr_from_stream(bd);
    }
}

static void _process_psr_restore_event(BLURAY *bd, const BD_PSR_EVENT *ev)
{
    /* PSR restore events are handled internally.
     * Restore stored playback position.
     */

    BD_DEBUG(DBG_BLURAY, "PSR restore: psr%u = %u\n", ev->psr_idx, ev->new_val);

    switch (ev->psr_idx) {
        case PSR_ANGLE_NUMBER:
            /* can't set angle before playlist is opened */
            return;
        case PSR_TITLE_NUMBER:
            /* pass to the application */
            _queue_event(bd, BD_EVENT_TITLE, ev->new_val);
            return;
        case PSR_CHAPTER:
            /* will be selected automatically */
            return;
        case PSR_PLAYLIST:
            bd_select_playlist(bd, ev->new_val);
            nav_set_angle(bd->title, bd_psr_read(bd->regs, PSR_ANGLE_NUMBER) - 1);
            return;
        case PSR_PLAYITEM:
            bd_seek_playitem(bd, ev->new_val);
            return;
        case PSR_TIME:
            _clip_seek_time(bd, ev->new_val);
            _init_ig_stream(bd);
            _run_gc(bd, GC_CTRL_INIT_MENU, 0);
            return;

        case PSR_SELECTED_BUTTON_ID:
        case PSR_MENU_PAGE_ID:
            /* handled by graphics controller */
            return;

        default:
            /* others: ignore */
            return;
    }
}

/*
 * notification events to APP
 */

static void _process_psr_write_event(BLURAY *bd, const BD_PSR_EVENT *ev)
{
    if (ev->ev_type == BD_PSR_WRITE) {
        BD_DEBUG(DBG_BLURAY, "PSR write: psr%u = %u\n", ev->psr_idx, ev->new_val);
    }

    switch (ev->psr_idx) {

        /* current playback position */

        case PSR_ANGLE_NUMBER:
            _bdj_event  (bd, BDJ_EVENT_ANGLE,   ev->new_val);
            _queue_event(bd, BD_EVENT_ANGLE,    ev->new_val);
            break;
        case PSR_TITLE_NUMBER:
            _queue_event(bd, BD_EVENT_TITLE,    ev->new_val);
            break;
        case PSR_PLAYLIST:
            _bdj_event  (bd, BDJ_EVENT_PLAYLIST,ev->new_val);
            _queue_event(bd, BD_EVENT_PLAYLIST, ev->new_val);
            break;
        case PSR_PLAYITEM:
            _bdj_event  (bd, BDJ_EVENT_PLAYITEM,ev->new_val);
            _queue_event(bd, BD_EVENT_PLAYITEM, ev->new_val);
            break;
        case PSR_TIME:
            _bdj_event  (bd, BDJ_EVENT_PTS,     ev->new_val);
            break;

        case 102:
            _bdj_event  (bd, BDJ_EVENT_PSR102,  ev->new_val);
            break;
        case 103:
            disc_event(bd->disc, DISC_EVENT_APPLICATION, ev->new_val);
            break;

        default:;
    }
}

static void _process_psr_change_event(BLURAY *bd, const BD_PSR_EVENT *ev)
{
    BD_DEBUG(DBG_BLURAY, "PSR change: psr%u = %u\n", ev->psr_idx, ev->new_val);

    _process_psr_write_event(bd, ev);

    switch (ev->psr_idx) {

        /* current playback position */

        case PSR_TITLE_NUMBER:
            disc_event(bd->disc, DISC_EVENT_TITLE, ev->new_val);
            break;

        case PSR_CHAPTER:
            _bdj_event  (bd, BDJ_EVENT_CHAPTER, ev->new_val);
            if (ev->new_val != 0xffff) {
                _queue_event(bd, BD_EVENT_CHAPTER,  ev->new_val);
            }
            break;

        /* stream selection */

        case PSR_IG_STREAM_ID:
            _queue_event(bd, BD_EVENT_IG_STREAM, ev->new_val);
            break;

        case PSR_PRIMARY_AUDIO_ID:
            _bdj_event(bd, BDJ_EVENT_AUDIO_STREAM, ev->new_val);
            _queue_event(bd, BD_EVENT_AUDIO_STREAM, ev->new_val);
            break;

        case PSR_PG_STREAM:
            _bdj_event(bd, BDJ_EVENT_SUBTITLE, ev->new_val);
            if ((ev->new_val & 0x80000fff) != (ev->old_val & 0x80000fff)) {
                _queue_event(bd, BD_EVENT_PG_TEXTST,        !!(ev->new_val & 0x80000000));
                _queue_event(bd, BD_EVENT_PG_TEXTST_STREAM,    ev->new_val & 0xfff);
            }

            bd_mutex_lock(&bd->mutex);
            if (bd->st0.clip) {
                _init_pg_stream(bd);
                if (bd->st_textst.clip) {
                    BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Changing TextST stream\n");
                    _preload_textst_subpath(bd);
                }
            }
            bd_mutex_unlock(&bd->mutex);

            break;

        case PSR_SECONDARY_AUDIO_VIDEO:
            /* secondary video */
            if ((ev->new_val & 0x8f00ff00) != (ev->old_val & 0x8f00ff00)) {
                _queue_event(bd, BD_EVENT_SECONDARY_VIDEO, !!(ev->new_val & 0x80000000));
                _queue_event(bd, BD_EVENT_SECONDARY_VIDEO_SIZE, (ev->new_val >> 24) & 0xf);
                _queue_event(bd, BD_EVENT_SECONDARY_VIDEO_STREAM, (ev->new_val & 0xff00) >> 8);
            }
            /* secondary audio */
            if ((ev->new_val & 0x400000ff) != (ev->old_val & 0x400000ff)) {
                _queue_event(bd, BD_EVENT_SECONDARY_AUDIO, !!(ev->new_val & 0x40000000));
                _queue_event(bd, BD_EVENT_SECONDARY_AUDIO_STREAM, ev->new_val & 0xff);
            }
            _bdj_event(bd, BDJ_EVENT_SECONDARY_STREAM, ev->new_val);
            break;

        /* 3D status */
        case PSR_3D_STATUS:
            _queue_event(bd, BD_EVENT_STEREOSCOPIC_STATUS, ev->new_val & 1);
            break;

        default:;
    }
}

static void _process_psr_event(void *handle, const BD_PSR_EVENT *ev)
{
    BLURAY *bd = (BLURAY*)handle;

    switch(ev->ev_type) {
        case BD_PSR_WRITE:
            _process_psr_write_event(bd, ev);
            break;
        case BD_PSR_CHANGE:
            _process_psr_change_event(bd, ev);
            break;
        case BD_PSR_RESTORE:
            _process_psr_restore_event(bd, ev);
            break;

        case BD_PSR_SAVE:
            BD_DEBUG(DBG_BLURAY, "PSR save event\n");
            break;
        default:
            BD_DEBUG(DBG_BLURAY, "PSR event %d: psr%u = %u\n", ev->ev_type, ev->psr_idx, ev->new_val);
            break;
    }
}

static void _queue_initial_psr_events(BLURAY *bd)
{
    const uint32_t psrs[] = {
        PSR_ANGLE_NUMBER,
        PSR_TITLE_NUMBER,
        PSR_IG_STREAM_ID,
        PSR_PRIMARY_AUDIO_ID,
        PSR_PG_STREAM,
        PSR_SECONDARY_AUDIO_VIDEO,
    };
    unsigned ii;
    BD_PSR_EVENT ev;

    ev.ev_type = BD_PSR_CHANGE;
    ev.old_val = 0;

    for (ii = 0; ii < sizeof(psrs) / sizeof(psrs[0]); ii++) {
        ev.psr_idx = psrs[ii];
        ev.new_val = bd_psr_read(bd->regs, psrs[ii]);

        _process_psr_change_event(bd, &ev);
    }
}

static int _play_bdj(BLURAY *bd, unsigned title)
{
    int result;

    bd->title_type = title_bdj;

    result = _start_bdj(bd, title);
    if (result <= 0) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Can't play BD-J title %d\n", title);
        bd->title_type = title_undef;
        _queue_event(bd, BD_EVENT_ERROR, BD_ERROR_BDJ);
    }

    return result;
}

static int _play_hdmv(BLURAY *bd, unsigned id_ref)
{
    int result = 1;

    _stop_bdj(bd);

    bd->title_type = title_hdmv;

    if (!bd->hdmv_vm) {
        bd->hdmv_vm = hdmv_vm_init(bd->disc, bd->regs, bd->disc_info.num_titles,
                                   bd->disc_info.first_play_supported, bd->disc_info.top_menu_supported);
    }

    if (hdmv_vm_select_object(bd->hdmv_vm, id_ref)) {
        result = 0;
    }

    bd->hdmv_suspended = !hdmv_vm_running(bd->hdmv_vm);

    if (result <= 0) {
        bd->title_type = title_undef;
        _queue_event(bd, BD_EVENT_ERROR, BD_ERROR_HDMV);
    }

    return result;
}

static int _play_title(BLURAY *bd, unsigned title)
{
    if (!bd->disc_info.titles) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_play_title(#%d): No disc index\n", title);
        return 0;
    }

    if (bd->disc_info.no_menu_support) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_play_title(): no menu support\n");
        return 0;
    }

    /* first play object ? */
    if (title == BLURAY_TITLE_FIRST_PLAY) {

        bd_psr_write(bd->regs, PSR_TITLE_NUMBER, BLURAY_TITLE_FIRST_PLAY); /* 5.2.3.3 */

        if (!bd->disc_info.first_play_supported) {
            /* no first play title (5.2.3.3) */
            BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_play_title(): No first play title\n");
            bd->title_type = title_hdmv;
            return 1;
        }

        if (bd->disc_info.first_play->bdj) {
            return _play_bdj(bd, title);
        } else {
            return _play_hdmv(bd, bd->disc_info.first_play->id_ref);
        }
    }

    /* bd_play not called ? */
    if (bd->title_type == title_undef) {
        BD_DEBUG(DBG_BLURAY|DBG_CRIT, "bd_call_title(): bd_play() not called !\n");
        return 0;
    }

    /* top menu ? */
    if (title == BLURAY_TITLE_TOP_MENU) {
        if (!bd->disc_info.top_menu_supported) {
            /* no top menu (5.2.3.3) */
            BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_play_title(): No top menu title\n");
            bd->title_type = title_hdmv;
            return 0;
        }
    }

    /* valid title from disc index ? */
    if (title <= bd->disc_info.num_titles) {

        bd_psr_write(bd->regs, PSR_TITLE_NUMBER, title); /* 5.2.3.3 */
        if (bd->disc_info.titles[title]->bdj) {
            return _play_bdj(bd, title);
        } else {
            return _play_hdmv(bd, bd->disc_info.titles[title]->id_ref);
        }
    } else {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "_play_title(#%d): Title not found\n", title);
    }

    return 0;
}

/* BD-J callback */
int bd_play_title_internal(BLURAY *bd, unsigned title)
{
    /* used by BD-J. Like bd_play_title() but bypasses UO mask checks. */
    int ret;
    bd_mutex_lock(&bd->mutex);
    ret = _play_title(bd, title);
    bd_mutex_unlock(&bd->mutex);
    return ret;
}

int bd_play(BLURAY *bd)
{
    int result;

    bd_mutex_lock(&bd->mutex);

    /* reset player state */

    bd->title_type = title_undef;

    if (bd->hdmv_vm) {
        hdmv_vm_free(&bd->hdmv_vm);
    }

    if (!bd->event_queue) {
        bd->event_queue = event_queue_new(sizeof(BD_EVENT));

        bd_psr_lock(bd->regs);
        bd_psr_register_cb(bd->regs, _process_psr_event, bd);
        _queue_initial_psr_events(bd);
        bd_psr_unlock(bd->regs);
    }

    disc_event(bd->disc, DISC_EVENT_START, 0);

    /* start playback from FIRST PLAY title */

    result = _play_title(bd, BLURAY_TITLE_FIRST_PLAY);

    bd_mutex_unlock(&bd->mutex);

    return result;
}

static int _try_play_title(BLURAY *bd, unsigned title)
{
    if (bd->title_type == title_undef && title != BLURAY_TITLE_FIRST_PLAY) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_play_title(): bd_play() not called\n");
        return 0;
    }

    if (BLURAY_PLAYER_SETTING_UO_RESTRICTION_RELAXED <= bd->uo_restriction_level && bd->uo_mask.title_search) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_play_title(): UO title search masked\n");
        // FIXME: UO_MASK_TITLE_SEARCH_INDEX is not part of BD-J API UOMaskTableControl
        _bdj_event(bd, BDJ_EVENT_UO_MASKED, UO_MASK_TITLE_SEARCH_INDEX);

        return 0;
    }

    /* TODO: Check if the title can be accessed */

    return _play_title(bd, title);
}

int bd_play_title(BLURAY *bd, unsigned title)
{
    int ret;

    if (title == BLURAY_TITLE_TOP_MENU) {
        /* menu call uses different UO mask */
        return bd_menu_call(bd, -1);
    }

    bd_mutex_lock(&bd->mutex);
    ret = _try_play_title(bd, title);
    bd_mutex_unlock(&bd->mutex);
    return ret;
}

static int _try_menu_call(BLURAY *bd, int64_t pts)
{
    _set_scr(bd, pts);

    if (bd->title_type == title_undef) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_menu_call(): bd_play() not called\n");
        return 0;
    }

    if (BLURAY_PLAYER_SETTING_UO_RESTRICTION_RELAXED <= bd->uo_restriction_level && bd->uo_mask.menu_call) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_menu_call(): UO menu call masked\n");
        // FIXME: UO_MASK_MENU_CALL_INDEX is not part of BD-J API UOMaskTableControl
        _bdj_event(bd, BDJ_EVENT_UO_MASKED, UO_MASK_MENU_CALL_INDEX);

        return 0;
    }

    if (bd->title_type == title_hdmv) {
        if (hdmv_vm_suspend_pl(bd->hdmv_vm) < 0) {
            BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_menu_call(): error storing playback location\n");
        }
    }

    return _play_title(bd, BLURAY_TITLE_TOP_MENU);
}

int bd_menu_call(BLURAY *bd, int64_t pts)
{
    int ret;
    bd_mutex_lock(&bd->mutex);
    ret = _try_menu_call(bd, pts);
    bd_mutex_unlock(&bd->mutex);
    return ret;
}

static void _process_hdmv_vm_event(BLURAY *bd, HDMV_EVENT *hev)
{
    BD_DEBUG(DBG_BLURAY, "HDMV event: %s(%d): %d\n", hdmv_event_str(hev->event), hev->event, hev->param);

    switch (hev->event) {
        case HDMV_EVENT_TITLE:
            _close_playlist(bd);
            _play_title(bd, hev->param);
            break;

        case HDMV_EVENT_PLAY_PL:
        case HDMV_EVENT_PLAY_PL_PI:
        case HDMV_EVENT_PLAY_PL_PM:
            if (!_open_playlist(bd, hev->param, 0)) {
                /* Missing playlist ?
                 * Seen on some discs while checking UHD capability.
                 * It seems only error message playlist is present, on success
                 * non-existing playlist is selected ...
                 */
                bd->hdmv_num_invalid_pl++;
                if (bd->hdmv_num_invalid_pl < 10) {
                    hdmv_vm_resume(bd->hdmv_vm);
                    bd->hdmv_suspended = !hdmv_vm_running(bd->hdmv_vm);
                    BD_DEBUG(DBG_BLURAY | DBG_CRIT, "Ignoring non-existing playlist %05d.mpls in HDMV mode\n", hev->param);
                    break;
                }
            } else {
                if (hev->event == HDMV_EVENT_PLAY_PL_PM) {
                    bd_seek_mark(bd, hev->param2);
                } else if (hev->event == HDMV_EVENT_PLAY_PL_PI) {
                    bd_seek_playitem(bd, hev->param2);
                }
                bd->hdmv_num_invalid_pl = 0;
            }

            /* initialize menus */
            _init_ig_stream(bd);
            _run_gc(bd, GC_CTRL_INIT_MENU, 0);
            break;

        case HDMV_EVENT_PLAY_PI:
            bd_seek_playitem(bd, hev->param);
            break;

        case HDMV_EVENT_PLAY_PM:
            bd_seek_mark(bd, hev->param);
            break;

        case HDMV_EVENT_PLAY_STOP:
            // stop current playlist
            _close_playlist(bd);

            bd->hdmv_suspended = !hdmv_vm_running(bd->hdmv_vm);
            break;

        case HDMV_EVENT_STILL:
            _queue_event(bd, BD_EVENT_STILL, hev->param);
            break;

        case HDMV_EVENT_ENABLE_BUTTON:
            _run_gc(bd, GC_CTRL_ENABLE_BUTTON, hev->param);
            break;

        case HDMV_EVENT_DISABLE_BUTTON:
            _run_gc(bd, GC_CTRL_DISABLE_BUTTON, hev->param);
            break;

        case HDMV_EVENT_SET_BUTTON_PAGE:
            _run_gc(bd, GC_CTRL_SET_BUTTON_PAGE, hev->param);
            break;

        case HDMV_EVENT_POPUP_OFF:
            _run_gc(bd, GC_CTRL_POPUP, 0);
            break;

        case HDMV_EVENT_IG_END:
            _run_gc(bd, GC_CTRL_IG_END, 0);
            break;

        case HDMV_EVENT_END:
        case HDMV_EVENT_NONE:
      //default:
            break;
    }
}

static int _run_hdmv(BLURAY *bd)
{
    HDMV_EVENT hdmv_ev;

    /* run VM */
    if (hdmv_vm_run(bd->hdmv_vm, &hdmv_ev) < 0) {
        _queue_event(bd, BD_EVENT_ERROR, BD_ERROR_HDMV);
        bd->hdmv_suspended = !hdmv_vm_running(bd->hdmv_vm);
        return -1;
    }

    /* process all events */
    do {
        _process_hdmv_vm_event(bd, &hdmv_ev);

    } while (!hdmv_vm_get_event(bd->hdmv_vm, &hdmv_ev));

    /* update VM state */
    bd->hdmv_suspended = !hdmv_vm_running(bd->hdmv_vm);

    /* update UO mask */
    _update_hdmv_uo_mask(bd);

    return 0;
}

static int _read_ext(BLURAY *bd, unsigned char *buf, int len, BD_EVENT *event)
{
    if (_get_event(bd, event)) {
        return 0;
    }

    /* run HDMV VM ? */
    if (bd->title_type == title_hdmv) {

        int loops = 0;
        while (!bd->hdmv_suspended) {

            if (_run_hdmv(bd) < 0) {
                BD_DEBUG(DBG_BLURAY|DBG_CRIT, "bd_read_ext(): HDMV VM error\n");
                bd->title_type = title_undef;
                return -1;
            }
            if (loops++ > 100) {
                /* Detect infinite loops.
                 * Broken disc may cause infinite loop between graphics controller and HDMV VM.
                 * This happens ex. with "Butterfly on a Wheel":
                 * Triggering unmasked "Menu Call" UO in language selection menu skips
                 * menu system initialization code, resulting in infinite loop in root menu.
                 */
                BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_read_ext(): detected possible HDMV mode live lock (%d loops)\n", loops);
                _queue_event(bd, BD_EVENT_ERROR, BD_ERROR_HDMV);
            }
            if (_get_event(bd, event)) {
                return 0;
            }
        }

        if (bd->gc_status & GC_STATUS_ANIMATE) {
            _run_gc(bd, GC_CTRL_NOP, 0);
        }
    }

    if (len < 1) {
        /* just polled events ? */
        return 0;
    }

    if (bd->title_type == title_bdj) {
        if (bd->end_of_playlist == 1) {
            _bdj_event(bd, BDJ_EVENT_END_OF_PLAYLIST, bd_psr_read(bd->regs, PSR_PLAYLIST));
            bd->end_of_playlist |= 2;
        }

        if (!bd->title) {
            /* BD-J title running but no playlist playing */
            _queue_event(bd, BD_EVENT_IDLE, 0);
            return 0;
        }

        if (bd->bdj_wait_start) {
            /* BD-J playlist prefethed but not yet playing */
            _queue_event(bd, BD_EVENT_IDLE, 1);
            return 0;
        }
    }

    int bytes = _bd_read_locked(bd, buf, len);

    if (bytes == 0) {

        // if no next clip (=end of title), resume HDMV VM
        if (!bd->st0.clip && bd->title_type == title_hdmv) {
            hdmv_vm_resume(bd->hdmv_vm);
            bd->hdmv_suspended = !hdmv_vm_running(bd->hdmv_vm);
            BD_DEBUG(DBG_BLURAY, "bd_read_ext(): reached end of playlist. hdmv_suspended=%d\n", bd->hdmv_suspended);
        }
    }

    _get_event(bd, event);

    return bytes;
}

int bd_read_ext(BLURAY *bd, unsigned char *buf, int len, BD_EVENT *event)
{
    int ret;
    bd_mutex_lock(&bd->mutex);
    ret = _read_ext(bd, buf, len, event);
    bd_mutex_unlock(&bd->mutex);
    return ret;
}

int bd_get_event(BLURAY *bd, BD_EVENT *event)
{
    if (!bd->event_queue) {
        bd->event_queue = event_queue_new(sizeof(BD_EVENT));

        bd_psr_register_cb(bd->regs, _process_psr_event, bd);
        _queue_initial_psr_events(bd);
    }

    if (event) {
        return _get_event(bd, event);
    }

    return 0;
}

/*
 * user interaction
 */

void bd_set_scr(BLURAY *bd, int64_t pts)
{
    bd_mutex_lock(&bd->mutex);
    bd->app_scr = 1;
    _set_scr(bd, pts);
    bd_mutex_unlock(&bd->mutex);
}

static int _get_rate_uo_index(BLURAY *bd, uint32_t rate, bd_uo_mask_index_e *uo_index)
{
    if (BLURAY_RATE_PAUSED == rate) {
        /* Pause On should be a safe UO */
        if (bd->uo_restriction_level <= BLURAY_PLAYER_SETTING_UO_RESTRICTION_SAFE) {
            return 0; // ignore UO restriction
        }
        *uo_index = UO_MASK_PAUSE_ON_MASK_INDEX;
        return 1;
    }

    /* If current clip is freezed, Still Off UO will certainly break playback */
    if (bd->st0.clip->still_mode >= BLURAY_STILL_TIME) {
        if (bd->uo_restriction_level <= BLURAY_PLAYER_SETTING_UO_RESTRICTION_SAFE) {
            return 0; // ignore UO restriction
        }
        *uo_index = UO_MASK_STILL_OFF_MASK_INDEX;
        return 1;
    }

    /* use Forward Play UO, as libbluray does not consider backward play */
    if (bd->uo_restriction_level <= BLURAY_PLAYER_SETTING_UO_RESTRICTION_SAFE) {
        return 0; // ignore UO restriction
    }

    *uo_index = UO_MASK_FORWARD_PLAY_MASK_INDEX;
    return 1;
}

static int _set_rate(BLURAY *bd, uint32_t rate)
{
    bd_uo_mask_index_e uo_mask_idx = 0;

    if (!bd->title) {
        return -1;
    }

    if (_get_rate_uo_index(bd, rate, &uo_mask_idx) && _is_uo_masked(bd, uo_mask_idx)) {
        BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_set_rate(%" PRIu32 ") UO %d restricted\n",
            rate, uo_mask_idx);
        _bdj_event(bd, BDJ_EVENT_UO_MASKED, (unsigned) uo_mask_idx);
        return -1;
    }

    if (bd->title_type == title_bdj) {
        return _bdj_event(bd, BDJ_EVENT_RATE, rate);
    }

    return 0;
}

int bd_set_rate(BLURAY *bd, uint32_t rate)
{
    int result;

    bd_mutex_lock(&bd->mutex);
    result = _set_rate(bd, rate);
    bd_mutex_unlock(&bd->mutex);

    return result;
}

int bd_mouse_select(BLURAY *bd, int64_t pts, uint16_t x, uint16_t y)
{
    uint32_t param = (x << 16) | y;
    int result = -1;

    bd_mutex_lock(&bd->mutex);

    _set_scr(bd, pts);

    if (bd->title_type == title_hdmv) {
        result = _run_gc(bd, GC_CTRL_MOUSE_MOVE, param);
    } else if (bd->title_type == title_bdj) {
        result = _bdj_event(bd, BDJ_EVENT_MOUSE, param);
    }

    bd_mutex_unlock(&bd->mutex);

    return result;
}

static int _get_key_uo_index(BLURAY *bd, uint32_t key_id, bd_uo_mask_index_e *uo_index)
{
    unsigned i;

    static const struct {
        bd_vk_key_e key;
        bd_uo_mask_index_e uo_index;
        bd_player_setting_uo_restriction_level uo_min_level;
    } key_uo_map[] = {
#define D(k, i, l)  { k, UO_MASK_ ## i, BLURAY_PLAYER_SETTING_UO_RESTRICTION_ ## l }
        D(BD_VK_ROOT_MENU, MENU_CALL_INDEX, SAFE),
        /* BD_VK_POPUP handled specifically */
        D(BD_VK_UP, MOVE_UP_SELECTED_BUTTON_MASK_INDEX, COMPLIANT),
        D(BD_VK_DOWN, MOVE_DOWN_SELECTED_BUTTON_MASK_INDEX, COMPLIANT),
        D(BD_VK_LEFT, MOVE_LEFT_SELECTED_BUTTON_MASK_INDEX, COMPLIANT),
        D(BD_VK_RIGHT, MOVE_RIGHT_SELECTED_BUTTON_MASK_INDEX, COMPLIANT),
        D(BD_VK_ENTER, ACTIVATE_BUTTON_MASK_INDEX, COMPLIANT),
#undef D
    };

    if (BD_VK_POPUP == key_id) {
        /* check if pop-up menu is on, if so key is treated as Pop-up Off UO */
        if (bd->uo_restriction_level <= BLURAY_PLAYER_SETTING_UO_RESTRICTION_SAFE) {
            return 0; // Not enforced
        }
        return (bd->gc_status & GC_STATUS_POPUP) ? UO_MASK_POPUP_OFF_MASK_INDEX : UO_MASK_POPUP_ON_MASK_INDEX;
    }

    for (i = 0; i < sizeof(key_uo_map) / sizeof(key_uo_map[0]); i++) {
        if (key_uo_map[i].key == key_id) {
            if (bd->uo_restriction_level < key_uo_map[i].uo_min_level) {
                return 0; // Not enforced
            }
            *uo_index = key_uo_map[i].uo_index;
            return 1;
        }
    }

    return 0;
}

#define BD_VK_FLAGS_MASK (BD_VK_KEY_PRESSED | BD_VK_KEY_TYPED | BD_VK_KEY_RELEASED)
#define BD_VK_KEY(k)     ((k) & ~(BD_VK_FLAGS_MASK))
#define BD_VK_FLAGS(k)   ((k) & BD_VK_FLAGS_MASK)
/* HDMV: key is triggered when pressed down */
#define BD_KEY_TYPED(k)  (!((k) & (BD_VK_KEY_TYPED | BD_VK_KEY_RELEASED)))

int bd_user_input(BLURAY *bd, int64_t pts, uint32_t key)
{
    bd_uo_mask_index_e uo_mask_idx = 0;
    int uo_is_masked = 0;
    int result = -1;

    if (BD_VK_KEY(key) == BD_VK_ROOT_MENU) {
        if (BD_KEY_TYPED(key)) {
            return bd_menu_call(bd, pts);
        }
        return 0;
    }

    bd_mutex_lock(&bd->mutex);

    _set_scr(bd, pts);
    if (_get_key_uo_index(bd, BD_VK_KEY(key), &uo_mask_idx)) {
        uo_is_masked = _is_uo_masked(bd, uo_mask_idx);
    }

    if (bd->title_type == title_hdmv) {
        if (BD_KEY_TYPED(key)) {
            if (uo_is_masked) {
                BD_DEBUG(DBG_BLURAY | DBG_CRIT, "bd_user_input(%" PRIu32 ") UO %d restricted\n", key, uo_mask_idx);
                result = 0;
            } else {
                result = _run_gc(bd, GC_CTRL_VK_KEY, BD_VK_KEY(key));
            }
        } else {
            result = 0;
        }

    } else if (bd->title_type == title_bdj) {
        if (!BD_VK_FLAGS(key)) {
            /* No flags --> single key press event */
            key |= BD_VK_KEY_PRESSED | BD_VK_KEY_TYPED | BD_VK_KEY_RELEASED;
        }
        result = _bdj_event(bd, BDJ_EVENT_VK_KEY, key);
    }

    bd_mutex_unlock(&bd->mutex);

    return result;
}

void bd_register_overlay_proc(BLURAY *bd, void *handle, bd_overlay_proc_f func)
{
    if (!bd) {
        return;
    }

    bd_mutex_lock(&bd->mutex);

    gc_free(&bd->graphics_controller);

    if (func) {
        bd->graphics_controller = gc_init(bd->regs, handle, func);
    }

    bd_mutex_unlock(&bd->mutex);
}

void bd_register_argb_overlay_proc(BLURAY *bd, void *handle, bd_argb_overlay_proc_f func, BD_ARGB_BUFFER *buf)
{
    if (!bd) {
        return;
    }

    bd_mutex_lock(&bd->argb_buffer_mutex);

    bd->argb_overlay_proc        = func;
    bd->argb_overlay_proc_handle = handle;
    bd->argb_buffer              = buf;

    bd_mutex_unlock(&bd->argb_buffer_mutex);
}

int bd_get_sound_effect(BLURAY *bd, unsigned sound_id, BLURAY_SOUND_EFFECT *effect)
{
    if (!bd || !effect) {
        return -1;
    }

    if (!bd->sound_effects) {

        bd->sound_effects = sound_get(bd->disc);
        if (!bd->sound_effects) {
            return -1;
        }
    }

    if (sound_id < bd->sound_effects->num_sounds) {
        SOUND_OBJECT *o = &bd->sound_effects->sounds[sound_id];

        effect->num_channels = o->num_channels;
        effect->num_frames   = o->num_frames;
        effect->samples      = (const int16_t *)o->samples;

        return 1;
    }

    return 0;
}

/*
 * Direct file access
 */

static int _bd_read_file(BLURAY *bd, const char *dir, const char *file, void **data, int64_t *size)
{
    if (!bd || !bd->disc || !file || !data || !size) {
        BD_DEBUG(DBG_CRIT, "Invalid arguments for bd_read_file()\n");
        return 0;
    }

    *data = NULL;
    *size = (int64_t)disc_read_file(bd->disc, dir, file, (uint8_t**)data);
    if (!*data || *size < 0) {
        BD_DEBUG(DBG_BLURAY, "bd_read_file() failed\n");
        X_FREE(*data);
        return 0;
    }

    BD_DEBUG(DBG_BLURAY, "bd_read_file(): read %" PRId64 " bytes from %s" DIR_SEP "%s\n",
             *size, dir ? dir : "", file);
    return 1;
}

int bd_read_file(BLURAY *bd, const char *path, void **data, int64_t *size)
{
    return _bd_read_file(bd, NULL, path, data, size);
}

struct bd_dir_s *bd_open_dir(BLURAY *bd, const char *dir)
{
    if (!bd || dir == NULL) {
        return NULL;
    }
    return disc_open_dir(bd->disc, dir);
}

struct bd_file_s *bd_open_file_dec(BLURAY *bd, const char *path)
{
    if (!bd || path == NULL) {
        return NULL;
    }
    return disc_open_path_dec(bd->disc, path);
}

/*
 * Metadata
 */

const struct meta_dl *bd_get_meta(BLURAY *bd)
{
    const struct meta_dl *meta = NULL;

    if (!bd) {
        return NULL;
    }

    if (!bd->meta) {
        bd->meta = meta_parse(bd->disc);
    }

    uint32_t psr_menu_lang = bd_psr_read(bd->regs, PSR_MENU_LANG);

    if (psr_menu_lang != 0 && psr_menu_lang != 0xffffff) {
        const char language_code[] = {(psr_menu_lang >> 16) & 0xff, (psr_menu_lang >> 8) & 0xff, psr_menu_lang & 0xff, 0 };
        meta = meta_get(bd->meta, language_code);
    } else {
        meta = meta_get(bd->meta, NULL);
    }

    /* assign title names to disc_info */
    if (meta && bd->titles) {
        unsigned ii;
        for (ii = 0; ii < meta->toc_count; ii++) {
            if (meta->toc_entries[ii].title_number > 0 && meta->toc_entries[ii].title_number <= bd->disc_info.num_titles) {
                bd->titles[meta->toc_entries[ii].title_number]->name = meta->toc_entries[ii].title_name;
            }
        }
        bd->disc_info.disc_name = meta->di_name;
    }

    return meta;
}

int bd_get_meta_file(BLURAY *bd, const char *name, void **data, int64_t *size)
{
    return _bd_read_file(bd, DIR_SEP "BDMV" DIR_SEP "META" DIR_SEP "DL", name, data, size);
}

int bd_open3d_mvc_get_info(BLURAY *bd, BD_OPEN3D_MVC_INFO *info)
{
    int ret = 0;

    if (!bd || !info) {
        return 0;
    }

    memset(info, 0, sizeof(*info));

    bd_mutex_lock(&bd->mutex);
    if (_open3d_mvc_refresh_runtime_locked(bd)) {
        *info = bd->open3d_mvc.info;
        ret = 1;
    }
    bd_mutex_unlock(&bd->mutex);

    return ret;
}

int bd_open3d_mvc_read_unit(BLURAY *bd, BD_OPEN3D_MVC_UNIT *unit,
                            uint8_t *buf, uint32_t *buf_size)
{
    int ret = 0;
    uint32_t capacity = buf_size ? *buf_size : 0;

    if (!bd) {
        return 0;
    }

    if (unit) {
        memset(unit, 0, sizeof(*unit));
    }
    if (buf_size) {
        *buf_size = 0;
    }

    bd_mutex_lock(&bd->mutex);
    if (bd->open3d_mvc.unit_head) {
        BD_OPEN3D_MVC_UNIT_NODE *node = bd->open3d_mvc.unit_head;
        uint32_t need = node->unit.merged_size;

        if (unit) {
            *unit = node->unit;
        }

        if (buf_size) {
            *buf_size = need;
        }

        if ((need > 0 && (!buf || !buf_size || capacity < need)) ||
            (need == 0 && !buf_size)) {
            ret = -1;
        } else {
            if (need > 0) {
                memcpy(buf, node->buf, need);
            }
            bd->open3d_mvc.unit_head = node->next;
            if (!bd->open3d_mvc.unit_head) {
                bd->open3d_mvc.unit_tail = NULL;
            }
            X_FREE(node->buf);
            X_FREE(node);
            ret = 1;
        }
    }
    bd_mutex_unlock(&bd->mutex);

    return ret;
}

/*
 * Database access
 */

#include "bdnav/clpi_parse.h"
#include "bdnav/mpls_parse.h"

struct clpi_cl *bd_get_clpi(BLURAY *bd, unsigned clip_ref)
{
    if (bd->title && clip_ref < bd->title->clip_list.count) {
        const NAV_CLIP *clip = &bd->title->clip_list.clip[clip_ref];
        return clpi_copy(clip->cl);
    }
    return NULL;
}

struct clpi_cl *bd_read_clpi(const char *path)
{
    return clpi_parse(path);
}

void bd_free_clpi(struct clpi_cl *cl)
{
    clpi_free(&cl);
}

struct mpls_pl *bd_read_mpls(const char *mpls_file)
{
    return mpls_parse(mpls_file);
}

void bd_free_mpls(struct mpls_pl *pl)
{
    mpls_free(&pl);
}

struct mobj_objects *bd_read_mobj(const char *mobj_file)
{
    return mobj_parse(mobj_file);
}

void bd_free_mobj(struct mobj_objects *obj)
{
    mobj_free(&obj);
}

struct bdjo_data *bd_read_bdjo(const char *bdjo_file)
{
    return bdjo_parse(bdjo_file);
}

void bd_free_bdjo(struct bdjo_data *obj)
{
    bdjo_free(&obj);
}
