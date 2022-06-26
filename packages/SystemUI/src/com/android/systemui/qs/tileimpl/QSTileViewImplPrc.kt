/*
 * Copyright (C) 2021 The Android Open Source Project
 * Copyright (C) 2022 droid-ng
 *
 * Part of Moto-PRC QS recreation
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

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.android.systemui.FontSizeUtils
import com.android.systemui.R
import com.android.systemui.plugins.qs.QSIconView
import com.android.systemui.plugins.qs.QSTile
import kotlin.random.Random

@SuppressLint("ViewConstructor")
open class QSTileViewImplPrc @JvmOverloads constructor(
        context: Context,
        iicon: QSIconView,
        ccollapsed: Boolean = false
) : QSTileViewImpl(context, iicon, ccollapsed, true) {
    private var l: LinearLayout
    private var useprc2 = false

    init {
        gravity = Gravity.CENTER
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        l = LinearLayout(context)
        this.addView(l)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER
        l.clipChildren = false
        l.clipToPadding = false
        l.isFocusable = true
        l.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        l.background = this.createTileBackground()
        this.setColor(this.getBackgroundColorForState(lastState))

        val iconSize = resources.getDimensionPixelSize(R.dimen.qs_icon_size)
        l.addView(_icon, LayoutParams(iconSize, iconSize))
        this.createAndAddLabels()
        this.createAndAddSideView()

        //bad hack to avoid triggering return
        useprc2 = !useprc2
        setIsPrc2(!useprc2)
    }

    fun setIsPrc2(prc2: Boolean) {
        if (useprc2 == prc2)
            return
        useprc2 = prc2
        val prcsize = resources.getDimensionPixelSize(R.dimen.prc_qs_tile_width)
        val prc2size = resources.getDimensionPixelSize(R.dimen.prc_qs2_tile_width)
        val prclayoutParams = LayoutParams(prcsize, prcsize)
        val prc2layoutParams = LayoutParams(prc2size, prc2size)
        l.layoutParams = if (useprc2) prc2layoutParams else prclayoutParams

        removeView(labelContainer)
        l.removeView(labelContainer)
        removeView(sideView)
        removeView(l)

        addView(l)
        createAndAddLabels()
        createAndAddSideView()

        if (useprc2 && collapsed) {
            val labelMargin = resources.getDimensionPixelSize(R.dimen.qs_label_container_margin)
            (labelContainer.layoutParams as MarginLayoutParams).apply {
                topMargin = labelMargin
            }
        }
    }

    override fun getIconWithBackground(): View {
        //sorry - i have to return nonsense to make qsanimator work my way
        return l
    }

    override fun getLabelContainer(): View {
        //even more sorry
        if (useprc2) return super.getLabelContainer()
        return customDrawableView
    }

    override fun getSecondaryLabel(): View {
        //most sorry
        if (useprc2) return super.getSecondaryLabel()
        return customDrawableView
    }

    override fun updateResources() {
        FontSizeUtils.updateFontSize(label, R.dimen.qs_tile_text_size)
        FontSizeUtils.updateFontSize(secondaryLabel, R.dimen.qs_tile_text_size)

        val iconSize = context.resources.getDimensionPixelSize(R.dimen.qs_icon_size)
        _icon.layoutParams.apply {
            height = iconSize
            width = iconSize
        }

        if (useprc2 && collapsed) {
            val labelMargin = resources.getDimensionPixelSize(R.dimen.qs_label_container_margin)
            (labelContainer.layoutParams as MarginLayoutParams).apply {
                topMargin = labelMargin
            }
        }

    }

    override fun createAndAddLabels() {
        labelContainer = LayoutInflater.from(context)
                .inflate(if (useprc2) R.layout.qs_tile_label_prc2 else R.layout.qs_tile_label_prc, this, false) as IgnorableChildLinearLayout
        label = labelContainer.requireViewById(R.id.tile_label)
        secondaryLabel = labelContainer.requireViewById(R.id.app_label)
        if (collapsed) {
            //labelContainer.ignoreLastView = true
            // Ideally, it'd be great if the parent could set this up when measuring just this child
            // instead of the View class having to support this. However, due to the mysteries of
            // LinearLayout's double measure pass, we cannot overwrite `measureChild` or any of its
            // sibling methods to have special behavior for labelContainer.
            labelContainer.forceUnspecifiedMeasure = true
            if (useprc2) secondaryLabel.alpha = 0f
        }
        setLabelColor(getLabelColorForState(lastState))
        setSecondaryLabelColor(getSecondaryLabelColorForState(lastState))
        if (useprc2) {
            addView(labelContainer)
        } else {
            l.addView(labelContainer)
        }
    }

    override fun setClickable(clickable: Boolean) {
        super.setClickable(clickable)
        l.background = if (clickable && showRippleEffect) {
            ripple.also {
                // In case that the colorBackgroundDrawable was used as the background, make sure
                // it has the correct callback instead of null
                colorBackgroundDrawable.callback = it
            }
        } else {
            colorBackgroundDrawable
        }
    }
    override fun loadSideViewDrawableIfNecessary(state: QSTile.State) {
        /*if (state.sideViewCustomDrawable != null) {
            customDrawableView.setImageDrawable(state.sideViewCustomDrawable)
            customDrawableView.visibility = VISIBLE
            chevronView.visibility = GONE
        } else if (state !is BooleanState || state.forceExpandIcon) {
            customDrawableView.setImageDrawable(null)
            customDrawableView.visibility = GONE
            chevronView.visibility = VISIBLE
        } else {*/
        customDrawableView.setImageDrawable(null)
        customDrawableView.visibility = GONE
        chevronView.visibility = GONE
        //}
    }

    override fun getLabelColorForState(state: Int): Int {
        if (useprc2) return colorLabelInactive
        return super.getLabelColorForState(state)
    }

    override fun getSecondaryLabelColorForState(state: Int): Int {
        if (useprc2) return colorSecondaryLabelInactive
        return super.getSecondaryLabelColorForState(state)
    }

    //todo: check if neccessary
    override fun getDetailY(): Int {
        if (!useprc2) return super.getDetailY()
        return top + labelContainer.top + labelContainer.height / 2
    }
}