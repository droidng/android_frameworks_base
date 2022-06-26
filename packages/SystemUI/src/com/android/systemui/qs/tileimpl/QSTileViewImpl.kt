/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tileimpl

import android.animation.ArgbEvaluator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources.ID_NULL
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.service.quicksettings.Tile
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import com.android.settingslib.Utils
import com.android.systemui.FontSizeUtils
import com.android.systemui.R
import com.android.systemui.animation.LaunchableView
import com.android.systemui.plugins.qs.QSIconView
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.qs.QSTileView
import com.android.systemui.qs.tileimpl.QSIconViewImpl.QS_ANIM_LENGTH
import java.util.Objects

private const val TAG = "QSTileViewImpl"
open class QSTileViewImpl @JvmOverloads constructor(
        context: Context,
        protected val _icon: QSIconView,
        protected val collapsed: Boolean = false,
        private val isPrc: Boolean = false
) : QSTileView(context), HeightOverrideable, LaunchableView {

    companion object {
        @JvmStatic
        protected val INVALID = -1
        @JvmStatic
        protected val BACKGROUND_NAME = "background"
        @JvmStatic
        protected val LABEL_NAME = "label"
        @JvmStatic
        protected val SECONDARY_LABEL_NAME = "secondaryLabel"
        @JvmStatic
        protected val CHEVRON_NAME = "chevron"
        const val UNAVAILABLE_ALPHA = 0.3f
        @VisibleForTesting
        internal const val TILE_STATE_RES_PREFIX = "tile_states_"
    }

    override var heightOverride: Int = HeightOverrideable.NO_OVERRIDE
        set(value) {
            if (field == value) return
            field = value
            updateHeight()
        }

    override var squishinessFraction: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            updateHeight()
        }

    protected val colorActive = Utils.getColorAttrDefaultColor(context,
            android.R.attr.colorAccent)
    protected val colorInactive = Utils.getColorAttrDefaultColor(context, R.attr.offStateColor)
    protected val colorUnavailable = Utils.applyAlpha(UNAVAILABLE_ALPHA, colorInactive)

    protected val colorLabelActive =
            Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimaryInverse)
    protected val colorLabelInactive =
            Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary)
    protected val colorLabelUnavailable = Utils.applyAlpha(UNAVAILABLE_ALPHA, colorLabelInactive)

    protected val colorSecondaryLabelActive =
            Utils.getColorAttrDefaultColor(context, android.R.attr.textColorSecondaryInverse)
    protected val colorSecondaryLabelInactive =
            Utils.getColorAttrDefaultColor(context, android.R.attr.textColorSecondary)
    private val colorSecondaryLabelUnavailable =
            Utils.applyAlpha(UNAVAILABLE_ALPHA, colorSecondaryLabelInactive)

    protected lateinit var label: TextView
    protected lateinit var secondaryLabel: TextView
    protected lateinit var labelContainer: IgnorableChildLinearLayout
    protected lateinit var sideView: ViewGroup
    protected lateinit var customDrawableView: ImageView
    protected lateinit var chevronView: ImageView

    protected var showRippleEffect = true

    protected lateinit var ripple: RippleDrawable
    protected lateinit var colorBackgroundDrawable: Drawable
    protected var paintColor: Int = 0
    protected val singleAnimator: ValueAnimator = ValueAnimator().apply {
        setDuration(QS_ANIM_LENGTH)
        addUpdateListener { animation ->
            setAllColors(
                // These casts will throw an exception if some property is missing. We should
                // always have all properties.
                animation.getAnimatedValue(BACKGROUND_NAME) as Int,
                animation.getAnimatedValue(LABEL_NAME) as Int,
                animation.getAnimatedValue(SECONDARY_LABEL_NAME) as Int,
                animation.getAnimatedValue(CHEVRON_NAME) as Int
            )
        }
    }

    protected var accessibilityClass: String? = null
    protected var stateDescriptionDeltas: CharSequence? = null
    protected var lastStateDescription: CharSequence? = null
    protected var tileState = false
    protected var lastState = INVALID
    protected var blockVisibilityChanges = false
    protected var lastVisibility = View.VISIBLE

    protected val locInScreen = IntArray(2)

    init {
        setId(generateViewId())
        if (!isPrc) {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            clipChildren = false
            clipToPadding = false
            isFocusable = true
            background = createTileBackground()
            setColor(getBackgroundColorForState(QSTile.State.DEFAULT_STATE))

            val padding = resources.getDimensionPixelSize(R.dimen.qs_tile_padding)
            val startPadding = resources.getDimensionPixelSize(R.dimen.qs_tile_start_padding)
            setPaddingRelative(startPadding, padding, padding, padding)

            val iconSize = resources.getDimensionPixelSize(R.dimen.qs_icon_size)
            addView(_icon, LayoutParams(iconSize, iconSize))

            createAndAddLabels()
            createAndAddSideView()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateResources()
    }

    override fun resetOverride() {
        heightOverride = HeightOverrideable.NO_OVERRIDE
        updateHeight()
    }

    open fun updateResources() {
        FontSizeUtils.updateFontSize(label, R.dimen.qs_tile_text_size)
        FontSizeUtils.updateFontSize(secondaryLabel, R.dimen.qs_tile_text_size)

        val iconSize = context.resources.getDimensionPixelSize(R.dimen.qs_icon_size)
        _icon.layoutParams.apply {
            height = iconSize
            width = iconSize
        }

        val padding = resources.getDimensionPixelSize(R.dimen.qs_tile_padding)
        val startPadding = resources.getDimensionPixelSize(R.dimen.qs_tile_start_padding)
        setPaddingRelative(startPadding, padding, padding, padding)

        val labelMargin = resources.getDimensionPixelSize(R.dimen.qs_label_container_margin)
        (labelContainer.layoutParams as MarginLayoutParams).apply {
            marginStart = labelMargin
        }

        (sideView.layoutParams as MarginLayoutParams).apply {
            marginStart = labelMargin
        }
        (chevronView.layoutParams as MarginLayoutParams).apply {
            height = iconSize
            width = iconSize
        }

        val endMargin = resources.getDimensionPixelSize(R.dimen.qs_drawable_end_margin)
        (customDrawableView.layoutParams as MarginLayoutParams).apply {
            height = iconSize
            marginEnd = endMargin
        }
    }

    protected open fun createAndAddLabels() {
        labelContainer = LayoutInflater.from(context)
                .inflate(R.layout.qs_tile_label, this, false) as IgnorableChildLinearLayout
        label = labelContainer.requireViewById(R.id.tile_label)
        secondaryLabel = labelContainer.requireViewById(R.id.app_label)
        if (collapsed) {
            labelContainer.ignoreLastView = true
            // Ideally, it'd be great if the parent could set this up when measuring just this child
            // instead of the View class having to support this. However, due to the mysteries of
            // LinearLayout's double measure pass, we cannot overwrite `measureChild` or any of its
            // sibling methods to have special behavior for labelContainer.
            labelContainer.forceUnspecifiedMeasure = true
            secondaryLabel.alpha = 0f
        }
        setLabelColor(getLabelColorForState(QSTile.State.DEFAULT_STATE))
        setSecondaryLabelColor(getSecondaryLabelColorForState(QSTile.State.DEFAULT_STATE))
        addView(labelContainer)
    }

    protected open fun createAndAddSideView() {
        sideView = LayoutInflater.from(context)
                .inflate(R.layout.qs_tile_side_icon, this, false) as ViewGroup
        customDrawableView = sideView.requireViewById(R.id.customDrawable)
        chevronView = sideView.requireViewById(R.id.chevron)
        setChevronColor(getChevronColorForState(QSTile.State.DEFAULT_STATE))
        addView(sideView)
    }

    open fun createTileBackground(): Drawable {
        ripple = mContext.getDrawable(R.drawable.qs_tile_background) as RippleDrawable
        colorBackgroundDrawable = ripple.findDrawableByLayerId(R.id.background)
        return ripple
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        updateHeight()
    }

    protected open fun updateHeight() {
        val actualHeight = if (heightOverride != HeightOverrideable.NO_OVERRIDE) {
            heightOverride
        } else {
            measuredHeight
        }
        // Limit how much we affect the height, so we don't have rounding artifacts when the tile
        // is too short.
        val constrainedSquishiness = 0.1f + squishinessFraction * 0.9f
        bottom = top + (actualHeight * constrainedSquishiness).toInt()
        scrollY = (actualHeight - height) / 2
    }

    override fun updateAccessibilityOrder(previousView: View?): View {
        accessibilityTraversalAfter = previousView?.id ?: ID_NULL
        return this
    }

    override fun getIcon(): QSIconView {
        return _icon
    }

    override fun getIconWithBackground(): View {
        return icon
    }

    override fun init(tile: QSTile) {
        init(
                { v: View? -> tile.click(this) },
                { view: View? ->
                    tile.longClick(this)
                    true
                }
        )
    }

    protected open fun init(
        click: OnClickListener?,
        longClick: OnLongClickListener?
    ) {
        setOnClickListener(click)
        onLongClickListener = longClick
    }

    override fun onStateChanged(state: QSTile.State) {
        post {
            handleStateChanged(state)
        }
    }

    override fun getDetailY(): Int {
        return top + height / 2
    }

    override fun hasOverlappingRendering(): Boolean {
        // Avoid layers for this layout - we don't need them.
        return false
    }

    override fun setClickable(clickable: Boolean) {
        super.setClickable(clickable)
        if (!isPrc) {
            background = if (clickable && showRippleEffect) {
                ripple.also {
                    // In case that the colorBackgroundDrawable was used as the background, make sure
                    // it has the correct callback instead of null
                    colorBackgroundDrawable.callback = it
                }
            } else {
                colorBackgroundDrawable
            }
        }
    }

    override fun getLabelContainer(): View {
        return labelContainer
    }

    override fun getSecondaryLabel(): View {
        return secondaryLabel
    }

    override fun getSecondaryIcon(): View {
        return sideView
    }

    override fun setShouldBlockVisibilityChanges(block: Boolean) {
        blockVisibilityChanges = block

        if (block) {
            lastVisibility = visibility
        } else {
            visibility = lastVisibility
        }
    }

    override fun setVisibility(visibility: Int) {
        if (blockVisibilityChanges) {
            lastVisibility = visibility
            return
        }

        super.setVisibility(visibility)
    }

    override fun setTransitionVisibility(visibility: Int) {
        if (blockVisibilityChanges) {
            // View.setTransitionVisibility just sets the visibility flag, so we don't have to save
            // the transition visibility separately from the normal visibility.
            lastVisibility = visibility
            return
        }

        super.setTransitionVisibility(visibility)
    }

    // Accessibility

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        if (!TextUtils.isEmpty(accessibilityClass)) {
            event.className = accessibilityClass
        }
        if (event.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION &&
                stateDescriptionDeltas != null) {
            event.text.add(stateDescriptionDeltas)
            stateDescriptionDeltas = null
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // Clear selected state so it is not announce by talkback.
        info.isSelected = false
        if (!TextUtils.isEmpty(accessibilityClass)) {
            info.className = accessibilityClass
            if (Switch::class.java.name == accessibilityClass) {
                val label = resources.getString(
                        if (tileState) R.string.switch_bar_on else R.string.switch_bar_off)
                // Set the text here for tests in
                // android.platform.test.scenario.sysui.quicksettings. Can be removed when
                // UiObject2 has a new getStateDescription() API and tests are updated.
                info.text = label
                info.isChecked = tileState
                info.isCheckable = true
                if (isLongClickable) {
                    info.addAction(
                            AccessibilityNodeInfo.AccessibilityAction(
                                    AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id,
                                    resources.getString(
                                            R.string.accessibility_long_click_tile)))
                }
            }
        }
    }

    override fun toString(): String {
        val sb = StringBuilder(javaClass.simpleName).append('[')
        sb.append("locInScreen=(${locInScreen[0]}, ${locInScreen[1]})")
        sb.append(", iconView=$_icon")
        sb.append(", tileState=$tileState")
        sb.append("]")
        return sb.toString()
    }

    // HANDLE STATE CHANGES RELATED METHODS

    protected open fun handleStateChanged(state: QSTile.State) {
        val allowAnimations = animationsEnabled()
        showRippleEffect = state.showRippleEffect
        isClickable = state.state != Tile.STATE_UNAVAILABLE
        isLongClickable = state.handlesLongClick
        icon.setIcon(state, allowAnimations)
        contentDescription = state.contentDescription

        // State handling and description
        val stateDescription = StringBuilder()
        val stateText = getStateText(state)
        if (!TextUtils.isEmpty(stateText)) {
            stateDescription.append(stateText)
            if (TextUtils.isEmpty(state.secondaryLabel)) {
                state.secondaryLabel = stateText
            }
        }
        if (!TextUtils.isEmpty(state.stateDescription)) {
            stateDescription.append(", ")
            stateDescription.append(state.stateDescription)
            if (lastState != INVALID && state.state == lastState &&
                    state.stateDescription != lastStateDescription) {
                stateDescriptionDeltas = state.stateDescription
            }
        }

        setStateDescription(stateDescription.toString())
        lastStateDescription = state.stateDescription

        accessibilityClass = if (state.state == Tile.STATE_UNAVAILABLE) {
            null
        } else {
            state.expandedAccessibilityClassName
        }

        if (state is BooleanState) {
            val newState = state.value
            if (tileState != newState) {
                tileState = newState
            }
        }
        //

        // Labels
        if (!Objects.equals(label.text, state.label)) {
            label.text = state.label
        }
        if (!Objects.equals(secondaryLabel.text, state.secondaryLabel)) {
            secondaryLabel.text = state.secondaryLabel
            secondaryLabel.visibility = if (TextUtils.isEmpty(state.secondaryLabel)) {
                GONE
            } else {
                VISIBLE
            }
        }

        // Colors
        if (state.state != lastState) {
            singleAnimator.cancel()
            if (allowAnimations) {
                singleAnimator.setValues(
                        colorValuesHolder(
                                BACKGROUND_NAME,
                                paintColor,
                                getBackgroundColorForState(state.state)
                        ),
                        colorValuesHolder(
                                LABEL_NAME,
                                label.currentTextColor,
                                getLabelColorForState(state.state)
                        ),
                        colorValuesHolder(
                                SECONDARY_LABEL_NAME,
                                secondaryLabel.currentTextColor,
                                getSecondaryLabelColorForState(state.state)
                        ),
                        colorValuesHolder(
                                CHEVRON_NAME,
                                chevronView.imageTintList?.defaultColor ?: 0,
                                getChevronColorForState(state.state)
                        )
                    )
                singleAnimator.start()
            } else {
                setAllColors(
                    getBackgroundColorForState(state.state),
                    getLabelColorForState(state.state),
                    getSecondaryLabelColorForState(state.state),
                    getChevronColorForState(state.state)
                )
            }
        }

        // Right side icon
        loadSideViewDrawableIfNecessary(state)

        label.isEnabled = !state.disabledByPolicy

        lastState = state.state
    }

    protected open fun setAllColors(
        backgroundColor: Int,
        labelColor: Int,
        secondaryLabelColor: Int,
        chevronColor: Int
    ) {
        setColor(backgroundColor)
        setLabelColor(labelColor)
        setSecondaryLabelColor(secondaryLabelColor)
        setChevronColor(chevronColor)
    }

    protected open fun setColor(color: Int) {
        colorBackgroundDrawable.mutate().setTint(color)
        paintColor = color
    }

    protected open fun setLabelColor(color: Int) {
        label.setTextColor(color)
    }

    protected open fun setSecondaryLabelColor(color: Int) {
        secondaryLabel.setTextColor(color)
    }

    protected open fun setChevronColor(color: Int) {
        chevronView.imageTintList = ColorStateList.valueOf(color)
    }

    protected open fun loadSideViewDrawableIfNecessary(state: QSTile.State) {
        if (state.sideViewCustomDrawable != null) {
            customDrawableView.setImageDrawable(state.sideViewCustomDrawable)
            customDrawableView.visibility = VISIBLE
            chevronView.visibility = GONE
        } else if (state !is BooleanState || state.forceExpandIcon) {
            customDrawableView.setImageDrawable(null)
            customDrawableView.visibility = GONE
            chevronView.visibility = VISIBLE
        } else {
            customDrawableView.setImageDrawable(null)
            customDrawableView.visibility = GONE
            chevronView.visibility = GONE
        }
    }

    protected open fun getStateText(state: QSTile.State): String {
        if (state.disabledByPolicy) {
            return context.getString(R.string.tile_disabled)
        }

        return if (state.state == Tile.STATE_UNAVAILABLE || state is BooleanState) {
            var arrayResId = SubtitleArrayMapping.getSubtitleId(state.spec)
            val array = resources.getStringArray(arrayResId)
            array[state.state]
        } else {
            ""
        }
    }

    /*
     * The view should not be animated if it's not on screen and no part of it is visible.
     */
    protected open fun animationsEnabled(): Boolean {
        if (!isShown) {
            return false
        }
        if (alpha != 1f) {
            return false
        }
        getLocationOnScreen(locInScreen)
        return locInScreen.get(1) >= -height
    }

    protected open fun getBackgroundColorForState(state: Int): Int {
        return when (state) {
            Tile.STATE_ACTIVE -> colorActive
            Tile.STATE_INACTIVE -> colorInactive
            Tile.STATE_UNAVAILABLE -> colorUnavailable
            else -> {
                Log.e(TAG, "Invalid state $state")
                0
            }
        }
    }

    protected open fun getLabelColorForState(state: Int): Int {
        return when (state) {
            Tile.STATE_ACTIVE -> colorLabelActive
            Tile.STATE_INACTIVE -> colorLabelInactive
            Tile.STATE_UNAVAILABLE -> colorLabelUnavailable
            else -> {
                Log.e(TAG, "Invalid state $state")
                0
            }
        }
    }

    protected open fun getSecondaryLabelColorForState(state: Int): Int {
        return when (state) {
            Tile.STATE_ACTIVE -> colorSecondaryLabelActive
            Tile.STATE_INACTIVE -> colorSecondaryLabelInactive
            Tile.STATE_UNAVAILABLE -> colorSecondaryLabelUnavailable
            else -> {
                Log.e(TAG, "Invalid state $state")
                0
            }
        }
    }

    protected open fun getChevronColorForState(state: Int): Int = getSecondaryLabelColorForState(state)
}

@VisibleForTesting
internal object SubtitleArrayMapping {
    val subtitleIdsMap = mapOf<String?, Int>(
        "internet" to R.array.tile_states_internet,
        "wifi" to R.array.tile_states_wifi,
        "cell" to R.array.tile_states_cell,
        "battery" to R.array.tile_states_battery,
        "dnd" to R.array.tile_states_dnd,
        "flashlight" to R.array.tile_states_flashlight,
        "rotation" to R.array.tile_states_rotation,
        "bt" to R.array.tile_states_bt,
        "airplane" to R.array.tile_states_airplane,
        "location" to R.array.tile_states_location,
        "hotspot" to R.array.tile_states_hotspot,
        "inversion" to R.array.tile_states_inversion,
        "saver" to R.array.tile_states_saver,
        "dark" to R.array.tile_states_dark,
        "work" to R.array.tile_states_work,
        "cast" to R.array.tile_states_cast,
        "night" to R.array.tile_states_night,
        "screenrecord" to R.array.tile_states_screenrecord,
        "reverse" to R.array.tile_states_reverse,
        "reduce_brightness" to R.array.tile_states_reduce_brightness,
        "cameratoggle" to R.array.tile_states_cameratoggle,
        "mictoggle" to R.array.tile_states_mictoggle,
        "controls" to R.array.tile_states_controls,
        "wallet" to R.array.tile_states_wallet,
        "alarm" to R.array.tile_states_alarm
    )

    fun getSubtitleId(spec: String?): Int {
        return subtitleIdsMap.getOrDefault(spec, R.array.tile_states_default)
    }
}

fun colorValuesHolder(name: String, vararg values: Int): PropertyValuesHolder {
    return PropertyValuesHolder.ofInt(name, *values).apply {
        setEvaluator(ArgbEvaluator.getInstance())
    }
}