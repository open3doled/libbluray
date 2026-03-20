/*
 * Open3D-specific libbluray MVC probe interface.
 *
 * This header intentionally keeps the first exported surface narrow while the
 * libbluray-owned MVC path is being brought up.
 */

#ifndef BLURAY_OPEN3D_MVC_H_
#define BLURAY_OPEN3D_MVC_H_

#include "bluray.h"

#ifndef BD_PUBLIC
#define BD_PUBLIC
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    BD_OPEN3D_MVC_SUBPATH_NONE = 0,
    BD_OPEN3D_MVC_SUBPATH_NORMAL = 1,
    BD_OPEN3D_MVC_SUBPATH_EXTENSION = 2,
} bd_open3d_mvc_subpath_kind_e;

typedef struct bd_open3d_mvc_info {
    uint8_t  available;
    uint8_t  subpath_kind;
    uint8_t  subpath_type;
    uint8_t  reserved0;
    uint16_t base_pid;
    uint16_t dependent_pid;
    int32_t  playitem_index;
    int32_t  sync_play_item_id;
    uint32_t sync_pts;
    uint32_t subpath_index;
    uint32_t subclip_index;
    char     base_clip_id[6];
    char     dependent_clip_id[6];
} BD_OPEN3D_MVC_INFO;

typedef enum {
    BD_OPEN3D_MVC_UNIT_FLAG_NONE        = 0,
    BD_OPEN3D_MVC_UNIT_FLAG_MATCHED     = 1 << 0,
    BD_OPEN3D_MVC_UNIT_FLAG_BASE_ONLY   = 1 << 1,
    BD_OPEN3D_MVC_UNIT_FLAG_RELAXED_DTS = 1 << 2,
} bd_open3d_mvc_unit_flags_e;

typedef struct bd_open3d_mvc_unit {
    uint32_t flags;
    uint32_t base_size;
    uint32_t dependent_size;
    uint32_t merged_size;
    int64_t  base_pts;
    int64_t  base_dts;
    int64_t  dependent_pts;
    int64_t  dependent_dts;
} BD_OPEN3D_MVC_UNIT;

BD_PUBLIC int bd_open3d_mvc_get_info(BLURAY *bd, BD_OPEN3D_MVC_INFO *info);
BD_PUBLIC int bd_open3d_mvc_read_unit(BLURAY *bd, BD_OPEN3D_MVC_UNIT *unit,
                                      uint8_t *buf, uint32_t *buf_size);

#ifdef __cplusplus
}
#endif

#endif /* BLURAY_OPEN3D_MVC_H_ */
