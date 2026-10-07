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
package code.name.monkey.retromusic.lyrics

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.Scroller
import androidx.core.content.ContextCompat
import androidx.core.graphics.withSave
import code.name.monkey.retromusic.R
import kotlinx.coroutines.*
import java.io.File
import java.lang.Runnable
import kotlin.math.abs

/**
 * 歌词 Created by wcy on 2015/11/9.
 */
@SuppressLint("StaticFieldLeak")
class CoverLrcView @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val mLrcEntryList: MutableList<LrcEntry> = ArrayList()
    private val mLrcPaint = TextPaint()
    private val mTimePaint = TextPaint()
    private var mTimeFontMetrics: Paint.FontMetrics? = null
    private var mPlayDrawable: Drawable? = null
    private var mDividerHeight = 0f
    private var mAnimationDuration: Long = 0
    private var mNormalTextColor = 0
    private var mNormalTextSize = 0f
    private var mCurrentTextColor = 0
    private var mCurrentTextSize = 0f
    private var mTimelineTextColor = 0
    private var mTimelineColor = 0
    private var mTimeTextColor = 0
    private var mDrawableWidth = 0
    private var mTimeTextWidth = 0
    private var mDefaultLabel: String? = null
    private var mLrcPadding = 0f
    private var mOnPlayClickListener: OnPlayClickListener? = null
    private var mAnimator: ValueAnimator? = null
    private var mGestureDetector: GestureDetector? = null
    private var mScroller: Scroller? = null
    private var mOffset = 0f
    private var mCurrentLine = 0
    private var isShowTimeline = false
    private var isTouching = false
    private var isFling = false
    // 高亮 blend：上一行→当前行颜色/alpha 平滑过渡
    private var mPrevLine = -1
    private var mHighlightProgress = 1f
    private var mHighlightAnimator: ValueAnimator? = null
    // 边缘渐隐
    private val mFadePaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private var mEdgeFadeHeight = 0
    private var mTopFadeShader: LinearGradient? = null
    private var mBottomFadeShader: LinearGradient? = null
    private var mCachedFadeCanvasH = -1
    private var mTextGravity // 歌词显示位置，靠左/居中/靠右
            = 0
    private val hideTimelineRunnable = Runnable {
        if (hasLrc() && isShowTimeline) {
            isShowTimeline = false
            smoothScrollTo(mCurrentLine)
        }
    }

    private val viewScope = CoroutineScope(Dispatchers.Main + Job())

    private val mSimpleOnGestureListener: SimpleOnGestureListener =
        object : SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                if (hasLrc() && mOnPlayClickListener != null) {
                    if (mOffset != getOffset(0)) {
                        parent.requestDisallowInterceptTouchEvent(true)
                    }
                    mScroller!!.forceFinished(true)
                    removeCallbacks(hideTimelineRunnable)
                    isTouching = true
                    isShowTimeline = true
                    invalidate()
                    return true
                }
                return super.onDown(e)
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (mOffset == getOffset(0) && distanceY < 0F) {
                    return super.onScroll(e1, e2, distanceX, distanceY)
                }
                if (hasLrc()) {
                    mOffset += -distanceY
                    mOffset = mOffset.coerceAtMost(getOffset(0))
                    mOffset = mOffset.coerceAtLeast(getOffset(mLrcEntryList.size - 1))
                    invalidate()
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return super.onScroll(e1, e2, distanceX, distanceY)
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (hasLrc()) {
                    mScroller!!.fling(
                        0,
                        mOffset.toInt(),
                        0,
                        velocityY.toInt(),
                        0,
                        0,
                        getOffset(mLrcEntryList.size - 1).toInt(),
                        getOffset(0).toInt()
                    )
                    isFling = true
                    return true
                }
                return super.onFling(e1, e2, velocityX, velocityY)
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (hasLrc()
                    && isShowTimeline
                    && mPlayDrawable!!.bounds.contains(e.x.toInt(), e.y.toInt())
                ) {
                    val centerLine = centerLine
                    val centerLineTime = mLrcEntryList[centerLine].time
                    // onPlayClick 消费了才更新 UI
                    if (mOnPlayClickListener != null && mOnPlayClickListener!!.onPlayClick(
                            centerLineTime
                        )
                    ) {
                        isShowTimeline = false
                        removeCallbacks(hideTimelineRunnable)
                        mPrevLine = mCurrentLine
                        mCurrentLine = centerLine
                        smoothScrollTo(centerLine)
                        return true
                    }
                } else {
                    callOnClick()
                    return true
                }
                return super.onSingleTapConfirmed(e)
            }
        }

    private fun init(attrs: AttributeSet?) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LrcView)
        mCurrentTextSize = ta.getDimension(
            R.styleable.LrcView_lrcTextSize, resources.getDimension(R.dimen.lrc_current_text_size)
        )
        mNormalTextSize = ta.getDimension(
            R.styleable.LrcView_lrcNormalTextSize,
            resources.getDimension(R.dimen.lrc_text_size)
        )
        if (mNormalTextSize == 0f) {
            mNormalTextSize = mCurrentTextSize
        }
        mDividerHeight = ta.getDimension(
            R.styleable.LrcView_lrcDividerHeight,
            resources.getDimension(R.dimen.lrc_divider_height)
        )
        val defDuration = resources.getInteger(R.integer.lrc_animation_duration)
        mAnimationDuration =
            ta.getInt(R.styleable.LrcView_lrcAnimationDuration, defDuration).toLong()
        mAnimationDuration =
            if (mAnimationDuration < 0) defDuration.toLong() else mAnimationDuration
        mNormalTextColor = ta.getColor(
            R.styleable.LrcView_lrcNormalTextColor,
            ContextCompat.getColor(context, R.color.lrc_normal_text_color)
        )
        mCurrentTextColor = ta.getColor(
            R.styleable.LrcView_lrcCurrentTextColor,
            ContextCompat.getColor(context, R.color.lrc_current_text_color)
        )
        mTimelineTextColor = ta.getColor(
            R.styleable.LrcView_lrcTimelineTextColor,
            ContextCompat.getColor(context, R.color.lrc_timeline_text_color)
        )
        mDefaultLabel = ta.getString(R.styleable.LrcView_lrcLabel)
        mDefaultLabel =
            if (mDefaultLabel.isNullOrEmpty()) context.getString(R.string.empty) else mDefaultLabel
        mLrcPadding = ta.getDimension(R.styleable.LrcView_lrcPadding, 0f)
        mTimelineColor = ta.getColor(
            R.styleable.LrcView_lrcTimelineColor,
            ContextCompat.getColor(context, R.color.lrc_timeline_color)
        )
        val timelineHeight = ta.getDimension(
            R.styleable.LrcView_lrcTimelineHeight,
            resources.getDimension(R.dimen.lrc_timeline_height)
        )
        mPlayDrawable = ta.getDrawable(R.styleable.LrcView_lrcPlayDrawable)
        mPlayDrawable =
            if (mPlayDrawable == null) ContextCompat.getDrawable(
                context,
                R.drawable.ic_play_arrow
            ) else mPlayDrawable
        mTimeTextColor = ta.getColor(
            R.styleable.LrcView_lrcTimeTextColor,
            ContextCompat.getColor(context, R.color.lrc_time_text_color)
        )
        val timeTextSize = ta.getDimension(
            R.styleable.LrcView_lrcTimeTextSize,
            resources.getDimension(R.dimen.lrc_time_text_size)
        )
        mTextGravity = ta.getInteger(R.styleable.LrcView_lrcTextGravity, LrcEntry.GRAVITY_CENTER)
        ta.recycle()
        mDrawableWidth = resources.getDimension(R.dimen.lrc_drawable_width).toInt()
        mTimeTextWidth = resources.getDimension(R.dimen.lrc_time_width).toInt()
        mLrcPaint.isAntiAlias = true
        mLrcPaint.textSize = mCurrentTextSize
        mLrcPaint.textAlign = Paint.Align.LEFT
        mTimePaint.isAntiAlias = true
        mTimePaint.textSize = timeTextSize
        mTimePaint.textAlign = Paint.Align.CENTER
        mTimePaint.strokeWidth = timelineHeight
        mTimePaint.strokeCap = Paint.Cap.ROUND
        mTimeFontMetrics = mTimePaint.fontMetrics
        mGestureDetector = GestureDetector(context, mSimpleOnGestureListener)
        mGestureDetector!!.setIsLongpressEnabled(false)
        mScroller = Scroller(context)
        mEdgeFadeHeight =
            (EDGE_FADE_DP * resources.displayMetrics.density + 0.5f).toInt()
    }

    fun setNormalColor(normalColor: Int) {
        mNormalTextColor = normalColor
        postInvalidate()
    }

    fun setCurrentColor(currentColor: Int) {
        mCurrentTextColor = currentColor
        postInvalidate()
    }

    fun setTimelineTextColor(timelineTextColor: Int) {
        mTimelineTextColor = timelineTextColor
        postInvalidate()
    }

    fun setTimelineColor(timelineColor: Int) {
        mTimelineColor = timelineColor
        postInvalidate()
    }

    fun setTimeTextColor(timeTextColor: Int) {
        mTimeTextColor = timeTextColor
        postInvalidate()
    }

    fun setDraggable(draggable: Boolean, onPlayClickListener: OnPlayClickListener?) {
        mOnPlayClickListener = if (draggable) {
            requireNotNull(onPlayClickListener) { "if draggable == true, onPlayClickListener must not be null" }
            onPlayClickListener
        } else {
            null
        }
    }

    fun setLabel(label: String?) {
        runOnUi {
            mDefaultLabel = label
            invalidate()
        }
    }

    fun loadLrc(lrcFile: File) {
        runOnUi {
            reset()
            viewScope.launch(Dispatchers.IO) {
                val lrcEntries = LrcUtils.parseLrc(arrayOf(lrcFile, null))
                withContext(Dispatchers.Main) {
                    onLrcLoaded(lrcEntries)
                }
            }
        }
    }

    fun loadLrc(lrcText: String?) {
        runOnUi {
            reset()
            viewScope.launch(Dispatchers.IO) {
                val lrcEntries = LrcUtils.parseLrc(arrayOf(lrcText, null))
                withContext(Dispatchers.Main) {
                    onLrcLoaded(lrcEntries)
                }
            }
        }
    }

    fun hasLrc(): Boolean {
        return mLrcEntryList.isNotEmpty()
    }

    fun updateTime(time: Long) {
        runOnUi {
            if (!hasLrc()) {
                return@runOnUi
            }
            val line = findShowLine(time + 300L)
            if (line != mCurrentLine) {
                mPrevLine = mCurrentLine
                mCurrentLine = line
                if (!isShowTimeline) {
                    smoothScrollTo(line)
                } else {
                    invalidate()
                }
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            initPlayDrawable()
            initEntryList()
            if (hasLrc()) {
                smoothScrollTo(mCurrentLine, 0L)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewH = height
        val centerY = viewH / 2

        if (!hasLrc()) {
            mLrcPaint.color = mCurrentTextColor
            @Suppress("Deprecation")
            @SuppressLint("DrawAllocation") val staticLayout = StaticLayout(
                mDefaultLabel,
                mLrcPaint,
                lrcWidth.toInt(),
                Layout.Alignment.ALIGN_CENTER,
                1f,
                0f,
                false
            )
            drawText(canvas, staticLayout, centerY.toFloat())
            return
        }

        val anchorY = (viewH * ANCHOR_RATIO).toInt()
        val timelineLine = if (isShowTimeline) centerLine else -1

        // 开一个 offscreen layer，便于用 DST_OUT 将顶部/底部渐隐
        canvas.saveLayer(0f, 0f, width.toFloat(), viewH.toFloat(), null)
        canvas.translate(0f, mOffset)

        var y = 0f
        for (i in mLrcEntryList.indices) {
            if (i > 0) {
                y += ((mLrcEntryList[i - 1].height + mLrcEntryList[i].height shr 1)
                        + mDividerHeight)
            }
            val staticLayout = mLrcEntryList[i].staticLayout
            // StaticLayout 内部克隆了 paint，必须改它自身才生效（原代码改 mLrcPaint 是死代码）
            val linePaint = staticLayout.paint

            var blend = 0f
            when {
                i == mCurrentLine -> blend = mHighlightProgress
                mPrevLine >= 0 && i == mPrevLine && mPrevLine != mCurrentLine ->
                    blend = 1f - mHighlightProgress
            }
            val dist = abs(i - mCurrentLine)
            val distAlpha = maxOf(FADE_MIN, 1f - dist * FADE_STEP)

            // 拖动预览：锥点行用 timeline 色高亮
            if (timelineLine == i) {
                linePaint.color = mTimelineTextColor
                linePaint.isFakeBoldText = true
                drawText(canvas, staticLayout, y)
                continue
            }

            val base = blendColor(mNormalTextColor, mCurrentTextColor, blend)
            linePaint.color = applyAlpha(base, distAlpha)
            linePaint.isFakeBoldText = blend > 0.5f
            drawText(canvas, staticLayout, y)
        }

        canvas.translate(0f, -mOffset)  // 取消位移（仍在 layer 内）

        if (mEdgeFadeHeight > 0 && viewH > mEdgeFadeHeight * 2) {
            ensureFadeShaders(viewH)
            mFadePaint.shader = mTopFadeShader
            canvas.drawRect(0f, 0f, width.toFloat(), mEdgeFadeHeight.toFloat(), mFadePaint)
            mFadePaint.shader = mBottomFadeShader
            canvas.drawRect(
                0f, (viewH - mEdgeFadeHeight).toFloat(),
                width.toFloat(), viewH.toFloat(), mFadePaint
            )
        }
        canvas.restore()

        // 拖动预览覆盖层（在 layer 之外，不受边缘渐隐影响）
        if (timelineLine >= 0) {
            drawTimelineOverlay(canvas, timelineLine, anchorY)
        }
    }

    /** 画拖动预览：分割线 + 播放箭头 + 时间戳 */
    private fun drawTimelineOverlay(canvas: Canvas, line: Int, anchorY: Int) {
        val timeText = LrcUtils.formatTime(mLrcEntryList[line].time)
        val left = mLrcPadding

        mTimePaint.color = mTimelineColor
        mTimePaint.textAlign = Paint.Align.LEFT
        canvas.drawLine(
            left + mTimeTextWidth, anchorY.toFloat(),
            width - left, anchorY.toFloat(), mTimePaint
        )

        val dw = mDrawableWidth
        val arrowCx = (left + mTimeTextWidth / 2f).toInt()
        val arrowTop = anchorY - dw - 4
        mPlayDrawable?.let { d ->
            d.setTint(mTimelineColor)
            d.setBounds(arrowCx - dw / 2, arrowTop, arrowCx + dw / 2, arrowTop + dw)
            d.draw(canvas)
        }

        mTimePaint.color = mTimeTextColor
        mTimePaint.textAlign = Paint.Align.CENTER
        val textY = anchorY + dw + 4 - mTimeFontMetrics!!.ascent
        canvas.drawText(timeText, left + mTimeTextWidth / 2f, textY, mTimePaint)
    }

    /** ARGB 两色按 t∈[0,1] 线性插值 */
    private fun blendColor(from: Int, to: Int, t: Float): Int {
        val a = (from ushr 24) + (((to ushr 24) - (from ushr 24)) * t).toInt()
        val r = ((from shr 16) and 0xFF) + ((((to shr 16) and 0xFF) - ((from shr 16) and 0xFF)) * t).toInt()
        val g = ((from shr 8) and 0xFF) + ((((to shr 8) and 0xFF) - ((from shr 8) and 0xFF)) * t).toInt()
        val b = (from and 0xFF) + (((to and 0xFF) - (from and 0xFF)) * t).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 把整体 alpha 乘以 factor∈[0,1] */
    private fun applyAlpha(color: Int, factor: Float): Int {
        val a = ((color ushr 24) * factor).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun ensureFadeShaders(viewH: Int) {
        if (mCachedFadeCanvasH == viewH && mTopFadeShader != null && mBottomFadeShader != null) {
            return
        }
        mTopFadeShader = LinearGradient(
            0f, 0f, 0f, mEdgeFadeHeight.toFloat(),
            0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP
        )
        mBottomFadeShader = LinearGradient(
            0f, (viewH - mEdgeFadeHeight).toFloat(), 0f, viewH.toFloat(),
            0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP
        )
        mCachedFadeCanvasH = viewH
    }

    private fun drawText(canvas: Canvas, staticLayout: StaticLayout, y: Float) {
        canvas.withSave {
            translate(mLrcPadding, y - (staticLayout.height shr 1))
            staticLayout.draw(this)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP
            || event.action == MotionEvent.ACTION_CANCEL
        ) {
            isTouching = false
            if (hasLrc() && !isFling) {
                adjustCenter()
                postDelayed(hideTimelineRunnable, TIMELINE_KEEP_TIME)
            }
        }
        return mGestureDetector!!.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (mScroller!!.computeScrollOffset()) {
            mOffset = mScroller!!.currY.toFloat()
            invalidate()
        }
        if (isFling && mScroller!!.isFinished) {
            isFling = false
            if (hasLrc() && !isTouching) {
                adjustCenter()
                postDelayed(hideTimelineRunnable, TIMELINE_KEEP_TIME)
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideTimelineRunnable)
        viewScope.cancel()
        super.onDetachedFromWindow()
    }

    private fun onLrcLoaded(entryList: List<LrcEntry>?) {
        if (entryList != null && entryList.isNotEmpty()) {
            mLrcEntryList.addAll(entryList)
        }
        mLrcEntryList.sort()
        initEntryList()
        invalidate()
    }

    private fun initPlayDrawable() {
        val l = (mTimeTextWidth - mDrawableWidth) / 2
        val t = (height * ANCHOR_RATIO).toInt() - mDrawableWidth / 2
        val r = l + mDrawableWidth
        val b = t + mDrawableWidth
        mPlayDrawable!!.setBounds(l, t, r, b)
    }

    private fun initEntryList() {
        if (!hasLrc() || width == 0) {
            return
        }
        for (lrcEntry in mLrcEntryList) {
            lrcEntry.init(mLrcPaint, lrcWidth.toInt(), mTextGravity)
        }
        mOffset = height * ANCHOR_RATIO
    }

    fun reset() {
        endAnimation()
        endHighlightAnimation()
        mScroller!!.forceFinished(true)
        isShowTimeline = false
        isTouching = false
        isFling = false
        removeCallbacks(hideTimelineRunnable)
        mLrcEntryList.clear()
        mOffset = 0f
        mCurrentLine = 0
        mPrevLine = -1
        mHighlightProgress = 1f
        invalidate()
    }

    private fun adjustCenter() {
        smoothScrollTo(centerLine, ADJUST_DURATION)
    }

    private fun smoothScrollTo(line: Int, duration: Long = mAnimationDuration) {
        val offset = getOffset(line)
        endAnimation()
        mAnimator = ValueAnimator.ofFloat(mOffset, offset).apply {
            this.duration = duration
            interpolator = EASE_OUT_IN
            addUpdateListener { animation: ValueAnimator ->
                mOffset = animation.animatedValue as Float
                invalidate()
            }
            start()
        }

        // 同步跑一次高亮 blend：0→1
        endHighlightAnimation()
        if (duration > 0 && mPrevLine != line) {
            mHighlightProgress = 0f
            mHighlightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = EASE_OUT_IN
                addUpdateListener {
                    mHighlightProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            mHighlightProgress = 1f
        }
    }

    private fun endHighlightAnimation() {
        if (mHighlightAnimator != null && mHighlightAnimator!!.isRunning) {
            mHighlightAnimator!!.end()
        }
    }

    private fun endAnimation() {
        if (mAnimator != null && mAnimator!!.isRunning) {
            mAnimator!!.end()
        }
    }

    private fun findShowLine(time: Long): Int {
        var left = 0
        var right = mLrcEntryList.size
        while (left <= right) {
            val middle = (left + right) / 2
            val middleTime = mLrcEntryList[middle].time
            if (time < middleTime) {
                right = middle - 1
            } else {
                if (middle + 1 >= mLrcEntryList.size || time < mLrcEntryList[middle + 1].time) {
                    return middle
                }
                left = middle + 1
            }
        }
        return 0
    }

    private val centerLine: Int
        get() {
            var centerLine = 0
            var minDistance = Float.MAX_VALUE
            for (i in mLrcEntryList.indices) {
                if (abs(mOffset - getOffset(i)) < minDistance) {
                    minDistance = abs(mOffset - getOffset(i))
                    centerLine = i
                }
            }
            return centerLine
        }

    private fun getOffset(line: Int): Float {
        if (mLrcEntryList.isEmpty()) return 0F
        if (mLrcEntryList[line].offset == Float.MIN_VALUE) {
            var offset = height * ANCHOR_RATIO
            for (i in 1..line) {
                offset -= ((mLrcEntryList[i - 1].height + mLrcEntryList[i].height shr 1)
                        + mDividerHeight)
            }
            mLrcEntryList[line].offset = offset
        }
        return mLrcEntryList[line].offset
    }

    private val lrcWidth: Float
        get() = width - mLrcPadding * 2

    private fun runOnUi(r: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run()
        } else {
            post(r)
        }
    }

    fun interface OnPlayClickListener {
        fun onPlayClick(time: Long): Boolean
    }

    companion object {
        private const val ADJUST_DURATION: Long = 100
        private const val TIMELINE_KEEP_TIME = 4 * DateUtils.SECOND_IN_MILLIS
        // Material FastOutSlowIn 曲线（与全屏 LrcView 对齐）
        private val EASE_OUT_IN = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        // 距离淡出：离当前行每远 1 行 alpha 减少 0.17；最远不低于 0.32
        private const val FADE_STEP = 0.17f
        private const val FADE_MIN = 0.32f
        // 当前行锥点：屏幕 42% 高度（下留更多预览空间）
        private const val ANCHOR_RATIO = 0.42f
        // 边缘渐隐高度（dp）
        private const val EDGE_FADE_DP = 72f
    }

    init {
        init(attrs)
    }
}