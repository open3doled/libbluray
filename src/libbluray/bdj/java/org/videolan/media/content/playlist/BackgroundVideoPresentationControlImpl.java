/*
 * This file is part of libbluray
 * Copyright (C) 2010  William Hahne
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

package org.videolan.media.content.playlist;

import java.awt.Dimension;
import java.awt.Rectangle;

import javax.tv.media.AWTVideoSize;
import javax.tv.media.AWTVideoSizeControl;

import org.dvb.media.BackgroundVideoPresentationControl;
import org.dvb.media.VideoTransformation;
import org.havi.ui.HScreenPoint;
import org.havi.ui.HScreenRectangle;
import org.videolan.BDJDebug;
import org.videolan.Logger;
import org.videolan.StreamInfo;
import org.videolan.TIClip;

public class BackgroundVideoPresentationControlImpl extends VideoControl
                implements BackgroundVideoPresentationControl, AWTVideoSizeControl {
    protected BackgroundVideoPresentationControlImpl(Handler player) {
        super(0);
        this.player = player;
    }

    protected StreamInfo[] getStreams() {
        TIClip ci = player.getCurrentClipInfo();
        if (ci == null)
            return null;
        return ci.getVideoStreams();
    }

    protected void setStreamNumber(int num) {
    }

    public int getCurrentStreamNumber() {
        return 1;
    }

    public boolean setVideoTransformation(VideoTransformation transform) {
        if (transform == null)
            return false;
        logger.info("TRACE bgvideo-control class=" + getClass().getName() +
                    " op=setVideoTransformation clip=" + transform.getClipRegion() +
                    " pos=" + transform.getVideoPosition() +
                    " scales=" + describeScales(transform.getScalingFactors()) +
                    BDJDebug.callerSummary());
        setClipRegion(transform.getClipRegion());
        HScreenPoint pos = transform.getVideoPosition();
        float[] scales = transform.getScalingFactors();
        Dimension vd = getInputVideoSize();
        Dimension sd = getScreenSize();
        setVideoArea(new HScreenRectangle(
                                          pos.x, pos.y,
                                          vd.width * scales[0] / sd.width,
                                          vd.height * scales[0] / sd.height));
        return true;
    }

    public VideoTransformation getVideoTransformation() {
        Dimension vd = getInputVideoSize();
        HScreenRectangle rect = getActiveVideoArea();
        float xscale, yscale;
        if ((vd.width == 0) || (vd.height == 0)) {
            xscale = 0.0f;
            yscale = 0.0f;
        } else {
            Dimension sd = getScreenSize();
            xscale = rect.width * sd.width / vd.width;
            yscale = rect.height * sd.height / vd.height;
        }
        VideoTransformation transform = new VideoTransformation(
                                       getClipRegion(),
                                       xscale, yscale,
                                       new HScreenPoint(rect.x, rect.y));
        logger.info("TRACE bgvideo-control class=" + getClass().getName() +
                    " op=getVideoTransformation active=" + rect.x + "," + rect.y +
                    " " + rect.width + "x" + rect.height +
                    " scales=" + xscale + "," + yscale +
                    " clip=" + getClipRegion() +
                    BDJDebug.callerSummary());
        return transform;
    }

    public VideoTransformation getClosestMatch(VideoTransformation transform) {
        return transform;
    }

    public AWTVideoSize getSize() {
        AWTVideoSize size = new AWTVideoSize(
                                getClipRegion(),
                                getRectangle(getScreenSize(), getActiveVideoArea()));
        logger.info("TRACE bgvideo-control class=" + getClass().getName() +
                    " op=getSize source=" + size.getSource() +
                    " destination=" + size.getDestination() +
                    BDJDebug.callerSummary());
        return size;
    }

    public AWTVideoSize getDefaultSize() {
        Dimension vd = getInputVideoSize();
        Dimension sd = getScreenSize();
        AWTVideoSize size = new AWTVideoSize(
                                new Rectangle(vd.width, vd.height),
                                new Rectangle(sd.width, sd.height));
        logger.info("TRACE bgvideo-control class=" + getClass().getName() +
                    " op=getDefaultSize source=" + size.getSource() +
                    " destination=" + size.getDestination() +
                    BDJDebug.callerSummary());
        return size;
    }

    public Dimension getSourceVideoSize() {
        return getVideoSize();
    }

    public boolean setSize(AWTVideoSize size) {
        logger.info("TRACE bgvideo-control class=" + getClass().getName() +
                    " op=setSize source=" + size.getSource() +
                    " destination=" + size.getDestination() +
                    BDJDebug.callerSummary());
        setClipRegion(size.getSource());
        setVideoArea(getNormalizedRectangle(getScreenSize(), size.getDestination()));
        return true;
    }

    public AWTVideoSize checkSize(AWTVideoSize size) {
        Rectangle requestedSource = new Rectangle(size.getSource());
        Rectangle requestedDestination = new Rectangle(size.getDestination());
        Dimension vd = getInputVideoSize();
        Rectangle sr = size.getSource();
        if (sr.x < 0)
            sr.x = 0;
        if ((sr.x + sr.width) > vd.width) {
            sr.width = vd.width - sr.x;
            if (sr.width <= 0) {
                sr.x = 0;
                sr.width = 0;
            }
        }
        if (sr.y < 0)
            sr.y = 0;
        if ((sr.y + sr.height) > vd.height) {
            sr.height = vd.height - sr.y;
            if (sr.height <= 0) {
                sr.y = 0;
                sr.height = 0;
            }
        }
        Rectangle dr = size.getDestination();
        AWTVideoSize checked = new AWTVideoSize(sr, dr);
        logger.info("TRACE bgvideo-control class=" + getClass().getName() +
                    " op=checkSize requestSource=" + requestedSource +
                    " requestDestination=" + requestedDestination +
                    " resultSource=" + checked.getSource() +
                    " resultDestination=" + checked.getDestination() +
                    BDJDebug.callerSummary());
        return checked;
    }

    private Handler player;
    private static final Logger logger =
        Logger.getLogger(BackgroundVideoPresentationControlImpl.class.getName());

    private static String describeScales(float[] scales) {
        if (scales == null) {
            return "null";
        }
        if (scales.length < 2) {
            return "[" + scales.length + "]";
        }
        return scales[0] + "," + scales[1];
    }
}
