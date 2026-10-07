/*
 * Copyright (C) 2017 wangchenyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package code.name.monkey.retromusic.lyrics;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.Scroller;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import code.name.monkey.retromusic.BuildConfig;
import code.name.monkey.retromusic.R;

/**
 * 歌词 Created by wcy on 2015/11/9.
 */
@SuppressLint("StaticFieldLeak")
public class LrcView extends View {
    private static final long ADJUST_DURATION = 0;
    private static final long TIMELINE_KEEP_TIME = 4 * DateUtils.SECOND_IN_MILLIS;

    // Material FastOutSlowIn 曲线（视觉更"贴手"，替代 AccelerateDecelerate）
    private static final PathInterpolator EASE_OUT_IN =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    // 距离淡出：离当前行每远 1 行，alpha 减少 0.17；最远不低于 0.32
    private static final float FADE_STEP = 0.17f;
    private static final float FADE_MIN = 0.32f;
    // 当前行锥点：屏幕 42% 高度（原 50%），下留更多预览空间
    private static final float ANCHOR_RATIO = 0.42f;
    // 边缘渐隐高度（dp）
    private static final float EDGE_FADE_DP = 72f;
    // Karaoke 默认行时长（下一行时间缺失时的兵底）
    private static final long DEFAULT_LINE_DURATION_MS = 3000L;
    private static final long MIN_LINE_DURATION_MS = 500L;

    private final List<LrcEntry> mLrcEntryList = new ArrayList<>();
    private final TextPaint mLrcPaint = new TextPaint();
    private final TextPaint mTimePaint = new TextPaint();
    private Paint.FontMetrics mTimeFontMetrics;
    private Drawable mPlayDrawable;
    private float mDividerHeight;
    private long mAnimationDuration;
    private int mNormalTextColor;
    private float mNormalTextSize;
    private int mCurrentTextColor;
    private float mCurrentTextSize;
    private int mTimelineTextColor;
    private int mTimelineColor;
    private int mTimeTextColor;
    private int mDrawableWidth;
    private int mTimeTextWidth;
    private String mDefaultLabel;
    private float mLrcPadding;
    private OnPlayClickListener mOnPlayClickListener;
    private ValueAnimator mAnimator;
    private GestureDetector mGestureDetector;
    private Scroller mScroller;
    private float mOffset;
    private int mCurrentLine;
    private int mPrevLine = -1;
    private float mHighlightProgress = 1f;
    private ValueAnimator mHighlightAnimator;
    // Karaoke 扫光内部状态
    private long mLineStartMs = 0;
    private long mLineDurationMs = 0;
    private float mLineProgress = 0f;
    private ValueAnimator mKaraokeAnimator;
    // 边缘渐隐
    private final Paint mFadePaint = new Paint();
    private int mEdgeFadeHeight = 0;
    private LinearGradient mTopFadeShader;
    private LinearGradient mBottomFadeShader;
    private int mCachedFadeCanvasH = -1;
    private Object mFlag;
    private boolean isShowTimeline;
    private boolean isTouching;
    private boolean isFling;
    private int mTextGravity;
    private final Runnable hideTimelineRunnable =
            new Runnable() {
                @Override
                public void run() {
                    if (hasLrc() && isShowTimeline) {
                        isShowTimeline = false;
                        smoothScrollTo(mCurrentLine);
                    }
                }
            };

    private final GestureDetector.SimpleOnGestureListener mSimpleOnGestureListener =
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    if (hasLrc() && mOnPlayClickListener != null) {
                        mScroller.forceFinished(true);
                        removeCallbacks(hideTimelineRunnable);
                        isTouching = true;
                        isShowTimeline = true;
                        invalidate();
                        return true;
                    }
                    return super.onDown(e);
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                    if (hasLrc()) {
                        mOffset -= distanceY;
                        mOffset = Math.min(mOffset, getOffset(0));
                        mOffset = Math.max(mOffset, getOffset(mLrcEntryList.size() - 1));
                        invalidate();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    return super.onScroll(e1, e2, distanceX, distanceY);
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                    if (hasLrc()) {
                        mScroller.fling(
                                0,
                                (int) mOffset,
                                0,
                                (int) velocityY,
                                0,
                                0,
                                (int) getOffset(mLrcEntryList.size() - 1),
                                (int) getOffset(0));
                        isFling = true;
                        invalidate();
                        return true;
                    }
                    return super.onFling(e1, e2, velocityX, velocityY);
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (hasLrc() && mOnPlayClickListener != null) {
                        float y = e.getY() - mOffset;
                        int tappedLine = getTappedLine(y);
                        if (tappedLine >= 0 && tappedLine < mLrcEntryList.size()) {
                            long tappedLineTime = mLrcEntryList.get(tappedLine).getTime();
                            if (mOnPlayClickListener.onPlayClick(tappedLineTime)) {
                                isShowTimeline = false;
                                removeCallbacks(hideTimelineRunnable);
                                mCurrentLine = tappedLine;
                                smoothScrollTo(mCurrentLine);
                                invalidate();
                                return true;
                            }
                        }
                    }
                    return super.onSingleTapConfirmed(e);
                }
            };

    private int getTappedLine(float y) {
        float cumulativeHeight = 0;
        for (int i = 0; i < mLrcEntryList.size(); i++) {
            LrcEntry entry = mLrcEntryList.get(i);
            float lineHeight = entry.getHeight();
            float lineCenter = cumulativeHeight + (lineHeight / 2);

            if (Math.abs(y - lineCenter) < lineHeight / 2) {
                return i;
            }
            cumulativeHeight += lineHeight + mDividerHeight;
        }
        return -1;
    }


    public LrcView(Context context) {
        this(context, null);
    }

    public LrcView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LrcView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        TypedArray ta = getContext().obtainStyledAttributes(attrs, R.styleable.LrcView);
        mCurrentTextSize =
                ta.getDimension(
                        R.styleable.LrcView_lrcTextSize, getResources().getDimension(R.dimen.lrc_text_size));
        mNormalTextSize =
                ta.getDimension(
                        R.styleable.LrcView_lrcNormalTextSize,
                        getResources().getDimension(R.dimen.lrc_text_size));
        if (mNormalTextSize == 0) {
            mNormalTextSize = mCurrentTextSize;
        }

        mDividerHeight =
                ta.getDimension(
                        R.styleable.LrcView_lrcDividerHeight,
                        getResources().getDimension(R.dimen.lrc_divider_height));
        int defDuration = getResources().getInteger(R.integer.lrc_animation_duration);
        mAnimationDuration = ta.getInt(R.styleable.LrcView_lrcAnimationDuration, defDuration);
        mAnimationDuration = (mAnimationDuration < 0) ? defDuration : mAnimationDuration;
        mNormalTextColor =
                ta.getColor(
                        R.styleable.LrcView_lrcNormalTextColor,
                        getResources().getColor(R.color.lrc_normal_text_color));
        mCurrentTextColor =
                ta.getColor(
                        R.styleable.LrcView_lrcCurrentTextColor,
                        getResources().getColor(R.color.lrc_current_text_color));
        mTimelineTextColor =
                ta.getColor(
                        R.styleable.LrcView_lrcTimelineTextColor,
                        getResources().getColor(R.color.lrc_timeline_text_color));
        mDefaultLabel = ta.getString(R.styleable.LrcView_lrcLabel);
        mDefaultLabel =
                TextUtils.isEmpty(mDefaultLabel) ? getContext().getString(R.string.empty) : mDefaultLabel;
        mLrcPadding = ta.getDimension(R.styleable.LrcView_lrcPadding, 0);
        mTimelineColor =
                ta.getColor(
                        R.styleable.LrcView_lrcTimelineColor,
                        getResources().getColor(R.color.lrc_timeline_color));
        float timelineHeight =
                ta.getDimension(
                        R.styleable.LrcView_lrcTimelineHeight,
                        getResources().getDimension(R.dimen.lrc_timeline_height));
        mPlayDrawable = ta.getDrawable(R.styleable.LrcView_lrcPlayDrawable);
        mPlayDrawable =
                (mPlayDrawable == null)
                        ? ContextCompat.getDrawable(getContext(), R.drawable.ic_play_arrow)
                        : mPlayDrawable;
        mTimeTextColor =
                ta.getColor(
                        R.styleable.LrcView_lrcTimeTextColor,
                        getResources().getColor(R.color.lrc_time_text_color));
        float timeTextSize =
                ta.getDimension(
                        R.styleable.LrcView_lrcTimeTextSize,
                        getResources().getDimension(R.dimen.lrc_time_text_size));
        mTextGravity = ta.getInteger(R.styleable.LrcView_lrcTextGravity, LrcEntry.GRAVITY_CENTER);

        ta.recycle();

        mDrawableWidth = (int) getResources().getDimension(R.dimen.lrc_drawable_width);
        mTimeTextWidth = (int) getResources().getDimension(R.dimen.lrc_time_width);

        mLrcPaint.setAntiAlias(true);
        mLrcPaint.setTextSize(mCurrentTextSize);
        mLrcPaint.setTextAlign(Paint.Align.LEFT);
        mTimePaint.setAntiAlias(true);
        mTimePaint.setTextSize(timeTextSize);
        mTimePaint.setTextAlign(Paint.Align.CENTER);
        mTimePaint.setStrokeWidth(timelineHeight);
        mTimePaint.setStrokeCap(Paint.Cap.ROUND);
        mTimeFontMetrics = mTimePaint.getFontMetrics();

        mGestureDetector = new GestureDetector(getContext(), mSimpleOnGestureListener);
        mGestureDetector.setIsLongpressEnabled(false);
        mScroller = new Scroller(getContext());

        mEdgeFadeHeight =
                (int) (EDGE_FADE_DP * getResources().getDisplayMetrics().density + 0.5f);
        mFadePaint.setFlags(Paint.ANTI_ALIAS_FLAG);
        mFadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    public void setCurrentColor(int currentColor) {
        mCurrentTextColor = currentColor;
        postInvalidate();
    }

    public void setTimelineTextColor(int timelineTextColor) {
        mTimelineTextColor = timelineTextColor;
        postInvalidate();
    }

    public void setTimelineColor(int timelineColor) {
        mTimelineColor = timelineColor;
        postInvalidate();
    }


    public void setTimeTextColor(int timeTextColor) {
        mTimeTextColor = timeTextColor;
        postInvalidate();
    }


    public void setDraggable(boolean draggable, OnPlayClickListener onPlayClickListener) {
        if (draggable) {
            if (onPlayClickListener == null) {
                throw new IllegalArgumentException(
                        "if draggable == true, onPlayClickListener must not be null");
            }
            mOnPlayClickListener = onPlayClickListener;
        } else {
            mOnPlayClickListener = null;
        }
    }

    public void setLabel(String label) {
        runOnUi(
                () -> {
                    mDefaultLabel = label;
                    invalidate();
                });
    }

    public void loadLrc(File lrcFile) {
        loadLrc(lrcFile, null);
    }

    public void loadLrc(File mainLrcFile, File secondLrcFile) {
        runOnUi(() -> {
            reset();

            StringBuilder sb = new StringBuilder("file://");
            sb.append(mainLrcFile.getPath());
            if (secondLrcFile != null) {
                sb.append("#").append(secondLrcFile.getPath());
            }
            String flag = sb.toString();
            setFlag(flag);
            new AsyncTask<File, Integer, List<LrcEntry>>() {
                @Override
                protected List<LrcEntry> doInBackground(File... params) {
                    return LrcUtils.parseLrc(params);
                }

                @Override
                protected void onPostExecute(List<LrcEntry> lrcEntries) {
                    if (getFlag() == flag) {
                        onLrcLoaded(lrcEntries);
                        setFlag(null);
                    }
                }
            }.execute(mainLrcFile, secondLrcFile);
        });
    }

    public void loadLrc(String lrcText) {
        loadLrc(lrcText, null);
    }

    public void loadLrc(String mainLrcText, String secondLrcText) {
        runOnUi(
                () -> {
                    reset();

                    StringBuilder sb = new StringBuilder("file://");
                    sb.append(mainLrcText);
                    if (secondLrcText != null) {
                        sb.append("#").append(secondLrcText);
                    }
                    String flag = sb.toString();
                    setFlag(flag);
                    new AsyncTask<String, Integer, List<LrcEntry>>() {
                        @Override
                        protected List<LrcEntry> doInBackground(String... params) {
                            return LrcUtils.parseLrc(params);
                        }

                        @Override
                        protected void onPostExecute(List<LrcEntry> lrcEntries) {
                            if (getFlag() == flag) {
                                onLrcLoaded(lrcEntries);
                                setFlag(null);
                            }
                        }
                    }.execute(mainLrcText, secondLrcText);
                });
    }

    public boolean hasLrc() {
        return !mLrcEntryList.isEmpty();
    }

    public void updateTime(long time) {
        runOnUi(
                () -> {
                    if (!hasLrc()) {
                        return;
                    }

                    int line = findShowLine(time);
                    if (line != mCurrentLine) {
                        mPrevLine = mCurrentLine;
                        mCurrentLine = line;
                        if (!isShowTimeline) {
                            smoothScrollTo(line);
                        } else {
                            invalidate();
                        }
                        startKaraoke(line, time);
                    } else {
                        syncKaraoke(time);
                    }
                });
    }

    @Deprecated
    public void onDrag(long time) {
        updateTime(time);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            initPlayDrawable();
            initEntryList();
            if (hasLrc()) {
                smoothScrollTo(mCurrentLine, 0L);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int viewH = getHeight();
        int centerY = viewH / 2;

        if (!hasLrc()) {
            mLrcPaint.setColor(mCurrentTextColor);
            @SuppressLint("DrawAllocation")
            StaticLayout staticLayout =
                    new StaticLayout(
                            mDefaultLabel,
                            mLrcPaint,
                            (int) getLrcWidth(),
                            Layout.Alignment.ALIGN_CENTER,
                            1f,
                            0f,
                            false);
            drawText(canvas, staticLayout, centerY);
            return;
        }

        // 拖动预览：当前靠锚点最近的一行（仅在 isShowTimeline 时有意义）
        int anchorY = (int) (viewH * ANCHOR_RATIO);
        int timelineLine = isShowTimeline ? findNearestLineToAnchor() : -1;

        // 开一个 offscreen layer，便于用 DST_OUT 将歌词顶部/底部渐隐掉
        canvas.saveLayer(0, 0, getWidth(), viewH, null);
        canvas.translate(0, mOffset);

        float y = 0;
        for (int i = 0; i < mLrcEntryList.size(); i++) {
            if (i > 0) {
                y +=
                        ((mLrcEntryList.get(i - 1).getHeight() + mLrcEntryList.get(i).getHeight()) >> 1)
                                + mDividerHeight;
            }
            LrcEntry entry = mLrcEntryList.get(i);
            StaticLayout sl = entry.getStaticLayout();
            if (sl == null) {
                continue;
            }
            // 行当前性 blend
            float blend = 0f;
            if (i == mCurrentLine) {
                blend = mHighlightProgress;
            } else if (mPrevLine >= 0 && i == mPrevLine && mPrevLine != mCurrentLine) {
                blend = 1f - mHighlightProgress;
            }
            int dist = Math.abs(i - mCurrentLine);
            float distAlpha = Math.max(FADE_MIN, 1f - dist * FADE_STEP);
            TextPaint linePaint = sl.getPaint();

            // 拖动预览：锚点行用 timeline 色整行高亮，给用户明确的"停在这一句"反馈
            if (timelineLine == i) {
                linePaint.setColor(mTimelineTextColor);
                linePaint.setFakeBoldText(true);
                drawText(canvas, sl, y);
                continue;
            }
            float lineTop = y - (sl.getHeight() >> 1);
            float lineBottom = y + (sl.getHeight() >> 1);

            if (i == mCurrentLine && mLineDurationMs > 0) {
                // Karaoke 扫光：已唱 (左) = currentColor、未唱 (右) = normalColor
                float textLeft = mLrcPadding;
                float sweepX = textLeft + sl.getWidth() * Math.max(0f, Math.min(1f, mLineProgress));
                int sungColor = applyAlpha(mCurrentTextColor, 1f);
                int unsungColor = applyAlpha(mNormalTextColor, distAlpha);

                // 未唱部分
                linePaint.setColor(unsungColor);
                linePaint.setFakeBoldText(true);
                canvas.save();
                canvas.clipRect(sweepX, lineTop, getWidth(), lineBottom);
                drawText(canvas, sl, y);
                canvas.restore();

                // 已唱部分
                linePaint.setColor(sungColor);
                canvas.save();
                canvas.clipRect(0, lineTop, sweepX, lineBottom);
                drawText(canvas, sl, y);
                canvas.restore();
            } else {
                int base = blendColor(mNormalTextColor, mCurrentTextColor, blend);
                linePaint.setColor(applyAlpha(base, distAlpha));
                linePaint.setFakeBoldText(blend > 0.5f);
                drawText(canvas, sl, y);
            }
        }

        canvas.translate(0, -mOffset);  // 取消 translate（仍在 layer 内，不能 restore）

        // 顶部/底部渐隐遮罩（仍在 layer 内）
        if (mEdgeFadeHeight > 0 && viewH > mEdgeFadeHeight * 2) {
            ensureFadeShaders(viewH);
            mFadePaint.setShader(mTopFadeShader);
            canvas.drawRect(0, 0, getWidth(), mEdgeFadeHeight, mFadePaint);
            mFadePaint.setShader(mBottomFadeShader);
            canvas.drawRect(0, viewH - mEdgeFadeHeight, getWidth(), viewH, mFadePaint);
        }
        canvas.restore();  // 弹出 saveLayer

        // 拖动预览覆盖层（在 layer 之外，不受边缘渐隐影响）
        if (timelineLine >= 0) {
            drawTimeline(canvas, timelineLine, anchorY);
        }
    }

    /** 找到屏幕上最靠近锚点（当前行位置）的一行 */
    private int findNearestLineToAnchor() {
        int best = mCurrentLine;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < mLrcEntryList.size(); i++) {
            float d = Math.abs(mOffset - getOffset(i));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** 画拖动预览：分割线 + 播放箭头 + 时间戳 */
    private void drawTimeline(Canvas canvas, int line, int centerY) {
        LrcEntry entry = mLrcEntryList.get(line);
        String timeText = formatTime(entry.getTime());
        float left = mLrcPadding;
        float dividerStart = left + mTimeTextWidth;
        float dividerEnd = getWidth() - left;

        // 分割线
        mTimePaint.setColor(mTimelineColor);
        canvas.drawLine(dividerStart, centerY, dividerEnd, centerY, mTimePaint);

        // 播放箭头（线上方）
        int dw = mDrawableWidth;
        int arrowTop = centerY - dw - 4;
        int arrowCx = (int) (left + mTimeTextWidth / 2f);
        mPlayDrawable.setTint(mTimelineColor);
        mPlayDrawable.setBounds(arrowCx - dw / 2, arrowTop, arrowCx + dw / 2, arrowTop + dw);
        mPlayDrawable.draw(canvas);

        // 时间戳（线下方）
        mTimePaint.setColor(mTimeTextColor);
        mTimePaint.setTextAlign(Paint.Align.CENTER);
        float textY = centerY + dw + 4 - mTimeFontMetrics.ascent;
        canvas.drawText(timeText, left + mTimeTextWidth / 2f, textY, mTimePaint);
    }

    private String formatTime(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long m = totalSec / 60;
        long s = totalSec % 60;
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }

    private void ensureFadeShaders(int viewH) {
        if (mCachedFadeCanvasH == viewH
                && mTopFadeShader != null
                && mBottomFadeShader != null) {
            return;
        }
        mTopFadeShader =
                new LinearGradient(
                        0, 0, 0, mEdgeFadeHeight,
                        0xFF000000, 0x00000000,
                        Shader.TileMode.CLAMP);
        mBottomFadeShader =
                new LinearGradient(
                        0, viewH - mEdgeFadeHeight, 0, viewH,
                        0x00000000, 0xFF000000,
                        Shader.TileMode.CLAMP);
        mCachedFadeCanvasH = viewH;
    }

    /** ARGB 两色按 t∈[0,1] 线性插值 */
    private static int blendColor(int from, int to, float t) {
        int a = (int) ((from >>> 24) + (((to >>> 24) - (from >>> 24)) * t));
        int r = (int) (((from >> 16) & 0xFF) + ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t));
        int g = (int) (((from >> 8) & 0xFF) + ((((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t));
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 把整体 alpha 乘以 factor∈[0,1] */
    private static int applyAlpha(int color, float factor) {
        int a = (int) (((color >>> 24)) * factor);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private void drawText(Canvas canvas, StaticLayout staticLayout, float y) {
        canvas.save();
        canvas.translate(mLrcPadding, y - (staticLayout.getHeight() >> 1));
        staticLayout.draw(canvas);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP
                || event.getAction() == MotionEvent.ACTION_CANCEL) {
            isTouching = false;
            if (hasLrc() && !isFling) {
                postDelayed(hideTimelineRunnable, TIMELINE_KEEP_TIME);
            }
        }
        return mGestureDetector.onTouchEvent(event);
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            mOffset = mScroller.getCurrY();
            invalidate();
        }

        if (isFling && mScroller.isFinished()) {
            isFling = false;
            if (hasLrc() && !isTouching) {
                postDelayed(hideTimelineRunnable, TIMELINE_KEEP_TIME);
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(hideTimelineRunnable);
        super.onDetachedFromWindow();
    }

    private void onLrcLoaded(List<LrcEntry> entryList) {
        if (entryList != null && !entryList.isEmpty()) {
            mLrcEntryList.addAll(entryList);
        }

        Collections.sort(mLrcEntryList);

        initEntryList();
        invalidate();
    }

    private void initPlayDrawable() {
        int l = (mTimeTextWidth - mDrawableWidth) / 2;
        int t = getHeight() / 2 - mDrawableWidth / 2;
        int r = l + mDrawableWidth;
        int b = t + mDrawableWidth;
        mPlayDrawable.setBounds(l, t, r, b);
    }

    private void initEntryList() {
        if (!hasLrc() || getWidth() == 0) {
            return;
        }

        for (LrcEntry lrcEntry : mLrcEntryList) {
            lrcEntry.init(mLrcPaint, (int) getLrcWidth(), mTextGravity);
        }

        mOffset = getHeight() * ANCHOR_RATIO;
    }

    private void reset() {
        endAnimation();
        endHighlightAnimation();
        endKaraoke();
        mScroller.forceFinished(true);
        isShowTimeline = false;
        isTouching = false;
        isFling = false;
        removeCallbacks(hideTimelineRunnable);
        mLrcEntryList.clear();
        mOffset = 0;
        mCurrentLine = 0;
        mPrevLine = -1;
        mHighlightProgress = 1f;
        mLineStartMs = 0;
        mLineDurationMs = 0;
        mLineProgress = 0f;
    }

    /** 行切换时启动 karaoke 扫光动画（以行时长为周期） */
    private void startKaraoke(int line, long nowMs) {
        endKaraoke();
        if (!hasLrc() || line < 0 || line >= mLrcEntryList.size()) {
            mLineProgress = 0f;
            return;
        }
        mLineStartMs = mLrcEntryList.get(line).getTime();
        long nextTime =
                (line + 1 < mLrcEntryList.size())
                        ? mLrcEntryList.get(line + 1).getTime()
                        : mLineStartMs + DEFAULT_LINE_DURATION_MS;
        mLineDurationMs = Math.max(MIN_LINE_DURATION_MS, nextTime - mLineStartMs);
        float p0 = clamp01((float) (nowMs - mLineStartMs) / mLineDurationMs);
        mLineProgress = p0;
        long remainMs = (long) (mLineDurationMs * (1f - p0));
        if (remainMs <= 0) {
            return;
        }
        mKaraokeAnimator = ValueAnimator.ofFloat(p0, 1f);
        mKaraokeAnimator.setDuration(remainMs);
        mKaraokeAnimator.setInterpolator(new LinearInterpolator());
        mKaraokeAnimator.addUpdateListener(
                a -> {
                    mLineProgress = (float) a.getAnimatedValue();
                    invalidate();
                });
        mKaraokeAnimator.start();
    }

    /** 外部 500ms tick 时同行人纠正漂移 */
    private void syncKaraoke(long nowMs) {
        if (mKaraokeAnimator == null || !mKaraokeAnimator.isRunning()) {
            // 首行、seek、或自然结束 —— 重新开一个
            startKaraoke(mCurrentLine, nowMs);
            return;
        }
        if (mLineDurationMs <= 0) {
            return;
        }
        float p = clamp01((float) (nowMs - mLineStartMs) / mLineDurationMs);
        if (Math.abs(p - mLineProgress) > 0.05f) {
            mKaraokeAnimator.setCurrentFraction(p);
        }
    }

    private void endKaraoke() {
        if (mKaraokeAnimator != null) {
            if (mKaraokeAnimator.isRunning()) {
                mKaraokeAnimator.cancel();
            }
            mKaraokeAnimator = null;
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private void smoothScrollTo(int line) {
        smoothScrollTo(line, mAnimationDuration);
    }

    private void smoothScrollTo(int line, long duration) {
        float offset = getOffset(line);

        endAnimation();
        mAnimator = ValueAnimator.ofFloat(mOffset, offset);
        mAnimator.setDuration(duration);
        mAnimator.setInterpolator(EASE_OUT_IN);
        mAnimator.addUpdateListener(
                animation -> {
                    mOffset = (float) animation.getAnimatedValue();
                    invalidate();
                });
        mAnimator.start();

        // 同步跑一次高亮过渡动画：blend 0→1
        endHighlightAnimation();
        if (duration > 0 && mPrevLine != line) {
            mHighlightProgress = 0f;
            mHighlightAnimator = ValueAnimator.ofFloat(0f, 1f);
            mHighlightAnimator.setDuration(duration);
            mHighlightAnimator.setInterpolator(EASE_OUT_IN);
            mHighlightAnimator.addUpdateListener(
                    animation -> {
                        mHighlightProgress = (float) animation.getAnimatedValue();
                        invalidate();
                    });
            mHighlightAnimator.start();
        } else {
            mHighlightProgress = 1f;
        }
    }

    private void endAnimation() {
        if (mAnimator != null && mAnimator.isRunning()) {
            mAnimator.end();
        }
    }

    private void endHighlightAnimation() {
        if (mHighlightAnimator != null && mHighlightAnimator.isRunning()) {
            mHighlightAnimator.end();
        }
    }

    private int findShowLine(long time) {
        int left = 0;
        int right = mLrcEntryList.size();
        while (left <= right) {
            int middle = (left + right) / 2;
            long middleTime = mLrcEntryList.get(middle).getTime();

            if (time < middleTime) {
                right = middle - 1;
            } else {
                if (middle + 1 >= mLrcEntryList.size() || time < mLrcEntryList.get(middle + 1).getTime()) {
                    return middle;
                }

                left = middle + 1;
            }
        }

        return 0;
    }

    private float getOffset(int line) {
        if (mLrcEntryList.get(line).getOffset() == Float.MIN_VALUE) {
            float offset = getHeight() * ANCHOR_RATIO;
            for (int i = 1; i <= line; i++) {
                offset -=
                        ((mLrcEntryList.get(i - 1).getHeight() + mLrcEntryList.get(i).getHeight()) >> 1)
                                + mDividerHeight;
            }
            mLrcEntryList.get(line).setOffset(offset);
        }

        return mLrcEntryList.get(line).getOffset();
    }

    private float getLrcWidth() {
        return getWidth() - mLrcPadding * 2;
    }

    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            post(r);
        }
    }

    private Object getFlag() {
        return mFlag;
    }

    private void setFlag(Object flag) {
        this.mFlag = flag;
    }

    public interface OnPlayClickListener {
        boolean onPlayClick(long time);
    }
}
