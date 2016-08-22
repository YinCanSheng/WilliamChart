/*
 * Copyright 2015 Diogo Bernardino
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.db.chart.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Typeface;
import android.support.annotation.ColorInt;
import android.support.annotation.FloatRange;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.widget.RelativeLayout;

import com.db.chart.animation.Animation;
import com.db.chart.animation.style.BaseStyleAnimation;
import com.db.chart.listener.OnEntryClickListener;
import com.db.chart.model.ChartEntry;
import com.db.chart.model.ChartSet;
import com.db.chart.renderer.XRenderer;
import com.db.chart.renderer.YRenderer;
import com.db.chart.tooltip.Tooltip;
import com.db.williamchart.R;

import java.text.DecimalFormat;
import java.util.ArrayList;


/**
 * Abstract class to be extend to define any chart that implies axis.
 */
public abstract class ChartView extends RelativeLayout {


	private static final String TAG = "chart.view.ChartView";

	private static final int DEFAULT_GRID_ROWS = 5;

	private static final int DEFAULT_GRID_COLUMNS = 5;

	/** Horizontal and Vertical position controllers */
	final XRenderer xRndr;

	final YRenderer yRndr;

	/** Style applied to chart */
	final Style style;

	/** Context */
	Context ctx;

	/** Chart data to be displayed */
	ArrayList<ChartSet> data;

	/** Chart orientation */
	private Orientation mOrientation;

	/** Chart borders including padding */
	private int mChartLeft;

	private int mChartTop;

	private int mChartRight;

	private int mChartBottom;

	/** Threshold limit line value */
	private boolean mHasThresholdValue;

	private float mThresholdStartValue;

	private float mThresholdEndValue;

	private boolean mHasThresholdLabel;

	private int mThresholdStartLabel;

	private int mThresholdEndLabel;

	/** Chart data to be displayed */
	private ArrayList<ArrayList<Region>> mRegions;

	/** Index of last point clicked */
	private int mIndexClicked;

	private int mSetClicked;

	/** Listeners to for touch events */
	private OnEntryClickListener mEntryListener;

	private OnClickListener mChartListener;

	/** Drawing flag */
	private boolean mReadyToDraw;

	/** Drawing flag */
	private boolean mIsDrawing;

	/** Chart animation */
	private Animation mAnim;

	/**
	 * Executed only before the chart is drawn for the first time.
	 * . borders are defined
	 * . digestData(data), to process the data to be drawn
	 * . defineRegions(), if listener has been registered
	 * this will define the chart regions to handle by onTouchEvent
	 */
	final private OnPreDrawListener drawListener = new OnPreDrawListener() {
		@SuppressLint("NewApi")
		@Override
		public boolean onPreDraw() {

			ChartView.this.getViewTreeObserver().removeOnPreDrawListener(this);

			// Generate Paint object with style attributes
			style.init();

			// Initiate axis labels with data and style
			yRndr.init(data, style);
			xRndr.init(data, style);

			// Set the positioning of the whole chart's frame
			mChartLeft = getPaddingLeft();
			mChartTop = getPaddingTop() + style.fontMaxHeight / 2;
			mChartRight = getMeasuredWidth() - getPaddingRight();
			mChartBottom = getMeasuredHeight() - getPaddingBottom();

			// Measure space and set the positioning of the inner border.
			// Inner borders will be chart's frame excluding the space needed by axis.
			// They define the actual area where chart's content will be drawn.
			yRndr.measure(mChartLeft, mChartTop, mChartRight, mChartBottom);
			xRndr.measure(mChartLeft, mChartTop, mChartRight, mChartBottom);

			// Negotiate chart inner boundaries.
			// Both renderers may require different space to draw axis stuff.
			final float[] bounds = negotiateInnerChartBounds(yRndr.getInnerChartBounds(),
					  xRndr.getInnerChartBounds());
			yRndr.setInnerChartBounds(bounds[0], bounds[1], bounds[2], bounds[3]);
			xRndr.setInnerChartBounds(bounds[0], bounds[1], bounds[2], bounds[3]);

			// Dispose the various axis elements in their positions
			yRndr.dispose();
			xRndr.dispose();

			// Parse threshold screen coordinates
			if (mHasThresholdValue) {
				mThresholdStartValue = yRndr.parsePos(0, mThresholdStartValue);
				mThresholdEndValue = yRndr.parsePos(0, mThresholdEndValue);
			}

			// Process data to define screen coordinates
			digestData();

			// In case Views extending ChartView need to pre process data before the onDraw
			onPreDrawChart(data);

			// Define entries regions
			mRegions = defineRegions(data);

			// Prepare the animation retrieving the first dump of coordinates to be used
			if (mAnim != null) data = mAnim.prepareEnterAnimation(ChartView.this);

			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB)
				ChartView.this.setLayerType(LAYER_TYPE_SOFTWARE, null);

			return mReadyToDraw = true;
		}
	};

	/** Grid attributes */
	private GridType mGridType;

	private int mGridNRows;

	private int mGridNColumns;

	/** Tooltip */
	private Tooltip mTooltip;


	public ChartView(Context context, AttributeSet attrs) {

		super(context, attrs);

		init();
		ctx = context;
		xRndr = new XRenderer();
		yRndr = new YRenderer();
		style = new Style(
				  context.getTheme().obtainStyledAttributes(attrs, R.styleable.ChartAttrs, 0, 0));
	}


	public ChartView(Context context) {

		super(context);

		init();
		ctx = context;
		xRndr = new XRenderer();
		yRndr = new YRenderer();
		style = new Style();
	}


	private void init() {

		mReadyToDraw = false;
		mSetClicked = -1;
		mIndexClicked = -1;
		mHasThresholdValue = false;
		mHasThresholdLabel = false;
		mIsDrawing = false;
		data = new ArrayList<>();
		mRegions = new ArrayList<>();
		mGridType = GridType.NONE;
		mGridNRows = DEFAULT_GRID_ROWS;
		mGridNColumns = DEFAULT_GRID_COLUMNS;
	}


	@Override
	public void onAttachedToWindow() {

		super.onAttachedToWindow();

		this.setWillNotDraw(false);
		style.init();
	}


	@Override
	public void onDetachedFromWindow() {

		super.onDetachedFromWindow();

		style.clean();
	}


	@Override
	public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

		super.onMeasure(widthMeasureSpec, heightMeasureSpec);

		int widthMode = MeasureSpec.getMode(widthMeasureSpec);
		int heightMode = MeasureSpec.getMode(heightMeasureSpec);

		int tmpWidth = widthMeasureSpec;
		int tmpHeight = heightMeasureSpec;

		if (widthMode == MeasureSpec.AT_MOST) tmpWidth = 200;

		if (heightMode == MeasureSpec.AT_MOST) tmpHeight = 100;

		setMeasuredDimension(tmpWidth, tmpHeight);
	}


	/**
	 * Convert {@link ChartEntry} values into screen points.
	 */
	private void digestData() {

		int nEntries = data.get(0).size();
		for (ChartSet set : data) {
			for (int i = 0; i < nEntries; i++) {
				set.getEntry(i)
						  .setCoordinates(xRndr.parsePos(i, set.getValue(i)),
									 yRndr.parsePos(i, set.getValue(i)));
			}
		}
	}


	/**
	 * (Optional) To be overridden in case the view needs to execute some code before
	 * starting the drawing.
	 *
	 * @param data Array of {@link ChartSet} to do the necessary preparation just before onDraw
	 */
	void onPreDrawChart(ArrayList<ChartSet> data) {}


	/**
	 * (Optional) To be overridden in order for each chart to define its own clickable regions.
	 * This way, classes extending ChartView will only define their clickable regions.
	 * <p/>
	 * Important: the returned vector must match the order of the data passed
	 * by the user. This ensures that onTouchEvent will return the correct index.
	 *
	 * @param data {@link java.util.ArrayList} of {@link com.db.chart.model.ChartSet}
	 * to use while defining each region of a {@link com.db.chart.view.BarChartView}
	 *
	 * @return {@link java.util.ArrayList} of {@link android.graphics.Region} with regions
	 * where click will be detected
	 */
	ArrayList<ArrayList<Region>> defineRegions(ArrayList<ChartSet> data) {

		return mRegions;
	}


	/**
	 * Method responsible to draw bars with the parsed screen points.
	 *
	 * @param canvas The canvas to draw on
	 * @param data {@link java.util.ArrayList} of {@link com.db.chart.model.ChartSet}
	 * to use while drawing the Chart
	 */
	abstract protected void onDrawChart(Canvas canvas, ArrayList<ChartSet> data);


	/**
	 * Set new data to the chart and invalidates the view to be then drawn.
	 *
	 * @param set {@link ChartSet} object.
	 */
	public void addData(ChartSet set) {

		if (!data.isEmpty() && set.size() != data.get(0).size())
			Log.e(TAG, "The number of entries between sets doesn't match.",
					  new IllegalArgumentException());
		if (set == null) Log.e(TAG, "Chart data set can't be null", new IllegalArgumentException());

		data.add(set);
	}


	/**
	 * Add full chart data.
	 *
	 * @param data An array of {@link ChartSet}
	 */
	public void addData(ArrayList<ChartSet> data) {

		this.data = data;
	}


	/**
	 * Base method when a show chart occurs
	 */
	private void display() {

		this.getViewTreeObserver().addOnPreDrawListener(drawListener);
		postInvalidate();
	}


	/**
	 * Show chart data
	 */
	public void show() {

		for (ChartSet set : data)
			set.setVisible(true);
		display();
	}


	/**
	 * Show only a specific chart dataset.
	 *
	 * @param setIndex Dataset index to be displayed
	 */
	public void show(int setIndex) {

		data.get(setIndex).setVisible(true);
		display();
	}


	/**
	 * Starts the animation given as parameter.
	 *
	 * @param anim Animation used while showing and updating sets
	 */
	public void show(Animation anim) {

		mAnim = anim;
		show();
	}


	/**
	 * Dismiss chart data.
	 */
	public void dismiss() {

		dismiss(mAnim);
	}


	/**
	 * Dismiss a specific chart dataset.
	 *
	 * @param setIndex Dataset index to be dismissed
	 */
	public void dismiss(int setIndex) {

		data.get(setIndex).setVisible(false);
		invalidate();
	}


	/**
	 * Dismiss chart data with animation.
	 *
	 * @param anim Animation used to exit
	 */
	public void dismiss(Animation anim) {

		if (anim != null) {
			mAnim = anim;

			final Runnable endAction = mAnim.getEndAction();
			mAnim.setEndAction(new Runnable() {
				@Override
				public void run() {

					if (endAction != null) endAction.run();
					data.clear();
					invalidate();
				}
			});

			data = mAnim.prepareExitAnimation(this);
		} else {
			data.clear();
		}
		invalidate();
	}


	/**
	 * Method not expected to be used often. More for testing.
	 * Resets chart state to insert new configuration.
	 */
	public void reset() {

		if (mAnim != null && mAnim.isPlaying()) mAnim.cancel();

		init();
		if (xRndr.hasMandatoryBorderSpacing()) xRndr.reset();
		if (yRndr.hasMandatoryBorderSpacing()) yRndr.reset();

		mHasThresholdLabel = false;
		mHasThresholdValue = false;
		style.thresholdPaint = null;

		style.gridPaint = null;
	}


	/**
	 * Update set values. Animation support in case previously added.
	 *
	 * @param setIndex Index of set to be updated
	 * @param values Array of new values. Array length must match current data
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView updateValues(int setIndex, float[] values) {

		if (values.length != data.get(setIndex).size())
			Log.e(TAG, "New values size doesn't match current dataset size.",
					  new IllegalArgumentException());

		data.get(setIndex).updateValues(values);
		return this;
	}


	/**
	 * Notify {@link ChartView} about updated values. {@link ChartView} will be validated.
	 */
	public void notifyDataUpdate() {

		// Ignore update if chart is not even ready to draw or if it is still animating
		if (mAnim != null && !mAnim.isPlaying() && mReadyToDraw || mAnim == null && mReadyToDraw) {

			ArrayList<float[][]> oldCoords = new ArrayList<>(data.size());
			ArrayList<float[][]> newCoords = new ArrayList<>(data.size());

			for (ChartSet set : data)
				oldCoords.add(set.getScreenPoints());

			digestData();
			for (ChartSet set : data)
				newCoords.add(set.getScreenPoints());

			mRegions = defineRegions(data);
			if (mAnim != null) data = mAnim.prepareUpdateAnimation(this, oldCoords, newCoords);

			invalidate();

		} else {
			Log.w(TAG, "Unexpected data update notification. " +
					  "Chart is still not displayed or still displaying.");
		}

	}


	/**
	 * Toggles {@link Tooltip} between show and dismiss.
	 *
	 * @param rect {@link Rect} containing the bounds of last clicked entry
	 * @param value Value of the last entry clicked
	 */
	private void toggleTooltip(Rect rect, float value) {

		if (!mTooltip.on()) {
			mTooltip.prepare(rect, value);
			showTooltip(mTooltip, true);
		} else {
			dismissTooltip(mTooltip, rect, value);
		}
	}


	/**
	 * Adds a tooltip to {@link ChartView}.
	 * If is not the case already, the whole tooltip is forced to be inside {@link ChartView}
	 * bounds. The area used to apply the correction exclude any padding applied, the whole view
	 * size in the layout is take into account.
	 *
	 * @param tooltip {@link Tooltip} view to be added
	 * @param correctPos False if tooltip should not be forced to be inside ChartView.
	 * You may want to take care of it.
	 */
	public void showTooltip(Tooltip tooltip, boolean correctPos) {

		if (correctPos) tooltip.correctPosition(mChartLeft, mChartTop, mChartRight, mChartBottom);

		if (tooltip.hasEnterAnimation()) tooltip.animateEnter();

		this.addTooltip(tooltip);

	}


	/**
	 * Add {@link Tooltip}/{@link View}. to chart/parent view.
	 *
	 * @param tip tooltip to be added to chart
	 */
	private void addTooltip(Tooltip tip) {

		this.addView(tip);
		tip.setOn(true);
	}


	/**
	 * Remove {@link Tooltip}/{@link View} to chart/parent view.
	 *
	 * @param tip tooltip to be removed to chart
	 */
	private void removeTooltip(Tooltip tip) {

		this.removeView(tip);
		tip.setOn(false);
	}


	/**
	 * Dismiss tooltip from {@link ChartView}.
	 *
	 * @param tooltip View to be dismissed
	 */
	private void dismissTooltip(Tooltip tooltip) {

		dismissTooltip(tooltip, null, 0);
	}


	/**
	 * Dismiss tooltip from {@link ChartView}.
	 *
	 * @param tooltip View to be dismissed
	 */
	private void dismissTooltip(final Tooltip tooltip, final Rect rect, final float value) {

		if (tooltip.hasExitAnimation()) {
			tooltip.animateExit(new Runnable() {
				@Override
				public void run() {

					removeTooltip(tooltip);
					if (rect != null) toggleTooltip(rect, value);
				}
			});
		} else {
			this.removeTooltip(tooltip);
			if (rect != null) this.toggleTooltip(rect, value);
		}
	}


	/**
	 * Removes all tooltips currently presented in the chart.
	 */
	public void dismissAllTooltips() {

		this.removeAllViews();
		if (mTooltip != null) mTooltip.setOn(false);
	}


	/**
	 * Animate {@link ChartSet}.
	 *
	 * @param index Position of {@link ChartSet}
	 * @param anim Animation extending {@link BaseStyleAnimation}
	 */
	public void animateSet(int index, BaseStyleAnimation anim) {

		anim.play(this, this.data.get(index));
	}


	/**
	 * Asks the view if it is able to draw now.
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public boolean canIPleaseAskYouToDraw() {

		return !mIsDrawing;
	}


	/**
	 * Negotiates the inner bounds required by renderers.
	 *
	 * @param innersA Inner bounds require by element A
	 * @param innersB Inned bound required by element B
	 *
	 * @return float vector with size equal to 4 containing agreed
	 * inner bounds (left, top, right, bottom).
	 */
	float[] negotiateInnerChartBounds(float[] innersA, float[] innersB) {

		return new float[] {(innersA[0] > innersB[0]) ? innersA[0] : innersB[0],
				  (innersA[1] > innersB[1]) ? innersA[1] : innersB[1],
				  (innersA[2] < innersB[2]) ? innersA[2] : innersB[2],
				  (innersA[3] < innersB[3]) ? innersA[3] : innersB[3]};
	}


	/**
	 * Draw a threshold line or band on the labels or values axis. If same values or same label
	 * index have been given then a line will be drawn rather than a band.
	 *
	 * @param canvas Canvas to draw line/band on.
	 * @param left The left side of the line/band to be drawn
	 * @param top The top side of the line/band to be drawn
	 * @param right The right side of the line/band to be drawn
	 * @param bottom The bottom side of the line/band to be drawn
	 */
	private void drawThreshold(Canvas canvas, float left, float top, float right, float bottom) {

		if (left == right || top == bottom)
			canvas.drawLine(left, top, right, bottom, style.thresholdPaint);
		else canvas.drawRect(left, top, right, bottom, style.thresholdPaint);
	}


	/**
	 * Draw vertical lines of Grid.
	 *
	 * @param canvas Canvas to draw on.
	 */
	private void drawVerticalGrid(Canvas canvas) {

		final float offset = (getInnerChartRight() - getInnerChartLeft()) / mGridNColumns;
		float marker = getInnerChartLeft();

		if (style.hasYAxis) marker += offset;

		while (marker < getInnerChartRight()) {
			canvas.drawLine(marker, getInnerChartTop(), marker, getInnerChartBottom(),
					  style.gridPaint);
			marker += offset;
		}

		canvas.drawLine(getInnerChartRight(), getInnerChartTop(), getInnerChartRight(),
				  getInnerChartBottom(), style.gridPaint);
	}


	/**
	 * Draw horizontal lines of Grid.
	 *
	 * @param canvas Canvas to draw on.
	 */
	private void drawHorizontalGrid(Canvas canvas) {

		final float offset = (getInnerChartBottom() - getInnerChartTop()) / mGridNRows;
		float marker = getInnerChartTop();
		while (marker < getInnerChartBottom()) {
			canvas.drawLine(getInnerChartLeft(), marker, getInnerChartRight(), marker,
					  style.gridPaint);
			marker += offset;
		}

		if (!style.hasXAxis)
			canvas.drawLine(getInnerChartLeft(), getInnerChartBottom(), getInnerChartRight(),
					  getInnerChartBottom(), style.gridPaint);
	}


	/**
	 * Get orientation of chart.
	 *
	 * @return Object of type {@link com.db.chart.view.ChartView.Orientation}
	 * defining an horizontal or vertical orientation.
	 * Orientation.HORIZONTAL | Orientation.VERTICAL
	 */
	public Orientation getOrientation() {

		return mOrientation;
	}


	/**
	 * Inner Chart refers only to the area where chart data will be draw,
	 * excluding labels, axis, etc.
	 *
	 * @return Position of the inner bottom side of the chart
	 */
	public float getInnerChartBottom() {

		return yRndr.getInnerChartBottom();
	}


	/**
	 * Inner Chart refers only to the area where chart data will be draw,
	 * excluding labels, axis, etc.
	 *
	 * @return Position of the inner left side of the chart
	 */
	public float getInnerChartLeft() {

		return xRndr.getInnerChartLeft();
	}


	/**
	 * Inner Chart refers only to the area where chart data will be draw,
	 * excluding labels, axis, etc.
	 *
	 * @return Position of the inner right side of the chart
	 */
	public float getInnerChartRight() {

		return xRndr.getInnerChartRight();
	}


	/**
	 * Inner Chart refers only to the area where chart data will be draw,
	 * excluding labels, axis, etc.
	 *
	 * @return Position of the inner top side of the chart
	 */
	public float getInnerChartTop() {

		return yRndr.getInnerChartTop();
	}


	/**
	 * Returns the position of 0 value on chart.
	 *
	 * @return Position of 0 value on chart
	 */
	public float getZeroPosition() {

		if (mOrientation == Orientation.VERTICAL) return yRndr.parsePos(0, 0);
		else return xRndr.parsePos(0, 0);
	}


	/**
	 * Get the step used between Y values.
	 *
	 * @return step
	 */
	int getStep() {

		if (mOrientation == Orientation.VERTICAL) return yRndr.getStep();
		else return xRndr.getStep();
	}


	/**
	 * Get chart's border spacing.
	 *
	 * @return spacing
	 */
	float getBorderSpacing() {

		if (mOrientation == Orientation.VERTICAL) return xRndr.getBorderSpacing();
		else return yRndr.getBorderSpacing();
	}


	/**
	 * Get the whole data owned by the chart.
	 *
	 * @return List of {@link com.db.chart.model.ChartSet} owned by the chart
	 */
	public ArrayList<ChartSet> getData() {

		return data;
	}


	/**
	 * Get the list of {@link android.graphics.Rect} associated to each entry of a ChartSet.
	 *
	 * @param index {@link com.db.chart.model.ChartSet} index
	 *
	 * @return The list of {@link android.graphics.Rect} for the specified dataset
	 */
	public ArrayList<Rect> getEntriesArea(int index) {

		ArrayList<Rect> result = new ArrayList<>(mRegions.get(index).size());
		for (Region r : mRegions.get(index))
			result.add(getEntryRect(r));

		return result;
	}


	/**
	 * Get the area, {@link android.graphics.Rect}, of an entry from the entry's {@link
	 * android.graphics.Region}
	 *
	 * @param region Region covering {@link ChartEntry} area
	 *
	 * @return {@link android.graphics.Rect} specifying the area of an {@link ChartEntry}
	 */
	private Rect getEntryRect(Region region) {
		// Subtract the view left/top padding to correct position
		return new Rect(region.getBounds().left - getPaddingLeft(),
				  region.getBounds().top - getPaddingTop(), region.getBounds().right - getPaddingLeft(),
				  region.getBounds().bottom - getPaddingTop());
	}


	/**
	 * Get the current {@link com.db.chart.animation.Animation}
	 * held by {@link com.db.chart.view.ChartView}.
	 * Useful, for instance, to define another endAction.
	 *
	 * @return Current {@link com.db.chart.animation.Animation}
	 */
	public Animation getChartAnimation() {

		return mAnim;
	}


	/**
	 * Sets the chart's orientation.
	 *
	 * @param orien Orientation.HORIZONTAL | Orientation.VERTICAL
	 */
	void setOrientation(Orientation orien) {

		mOrientation = orien;
		if (mOrientation == Orientation.VERTICAL) {
			yRndr.setHandleValues(true);
		} else {
			xRndr.setHandleValues(true);
		}
	}


	/**
	 * Show/Hide Y labels and respective axis.
	 *
	 * @param position NONE - No labels
	 * OUTSIDE - Labels will be positioned outside the chart
	 * INSIDE - Labels will be positioned inside the chart
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setYLabels(YRenderer.LabelPosition position) {

		yRndr.setLabelsPositioning(position);
		return this;
	}


	/**
	 * Show/Hide X labels and respective axis.
	 *
	 * @param position NONE - No labels
	 * OUTSIDE - Labels will be positioned outside the chart
	 * INSIDE - Labels will be positioned inside the chart
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setXLabels(XRenderer.LabelPosition position) {

		xRndr.setLabelsPositioning(position);
		return this;
	}


	/**
	 * Set the format to be added to Y labels.
	 *
	 * @param format Format to be applied
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setLabelsFormat(DecimalFormat format) {

		if (mOrientation == Orientation.VERTICAL) yRndr.setLabelsFormat(format);
		else xRndr.setLabelsFormat(format);

		return this;
	}



	/*
	 * --------
	 * Setters
	 * --------
	 */


	/**
	 * Set color to be used in labels.
	 *
	 * @param color Color to be applied to labels
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setLabelsColor(@ColorInt int color) {

		style.labelsColor = color;
		return this;
	}


	/**
	 * Set size of font to be used in labels.
	 *
	 * @param size Font size to be applied to labels
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setFontSize(@IntRange(from = 0) int size) {

		style.fontSize = size;
		return this;
	}


	/**
	 * Set typeface to be used in labels.
	 *
	 * @param typeface To be applied to labels
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setTypeface(Typeface typeface) {

		style.typeface = typeface;
		return this;
	}


	/**
	 * Show/Hide X axis.
	 *
	 * @param bool If true axis won't be visible
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setXAxis(boolean bool) {

		style.hasXAxis = bool;
		return this;
	}


	/**
	 * Show/Hide Y axis.
	 *
	 * @param bool If true axis won't be visible
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setYAxis(boolean bool) {

		style.hasYAxis = bool;
		return this;
	}


	/**
	 * A step is seen as the step to be defined between 2 labels. As an
	 * example a step of 2 with a maxAxisValue of 6 will end up with
	 * {0, 2, 4, 6} as labels.
	 *
	 * @param minValue The minimum value that Y axis will have as a label
	 * @param maxValue The maximum value that Y axis will have as a label
	 * @param step (real) value distance from every label
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setAxisBorderValues(int minValue, int maxValue, int step) {

		if (mOrientation == Orientation.VERTICAL) yRndr.setBorderValues(minValue, maxValue, step);
		else xRndr.setBorderValues(minValue, maxValue, step);

		return this;
	}


	/**
	 * @param minValue The minimum value that Y axis will have as a label
	 * @param maxValue The maximum value that Y axis will have as a label
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setAxisBorderValues(int minValue, int maxValue) {

		if (mOrientation == Orientation.VERTICAL) yRndr.setBorderValues(minValue, maxValue);
		else xRndr.setBorderValues(minValue, maxValue);

		return this;
	}


	/**
	 * Define the thickness of the axis.
	 *
	 * @param thickness size of the thickness
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setAxisThickness(@FloatRange(from = 0.f) float thickness) {

		style.axisThickness = thickness;
		return this;
	}


	/**
	 * Define the color of the axis.
	 *
	 * @param color color of the axis
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setAxisColor(@ColorInt int color) {

		style.axisColor = color;
		return this;
	}


	/**
	 * A step is seen as the step to be defined between 2 labels.
	 * As an example a step of 2 with a max label value of 6 will end
	 * up with {0, 2, 4, 6} as labels.
	 *
	 * @param step (real) value distance from every label
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setStep(int step) {

		if (step <= 0) throw new IllegalArgumentException("Step can't be lower or equal to 0");

		if (mOrientation == Orientation.VERTICAL) yRndr.setStep(step);
		else xRndr.setStep(step);

		return this;
	}


	/**
	 * Register a listener to be called when an {@link ChartEntry} is clicked.
	 *
	 * @param listener Listener to be used for callback.
	 */
	public void setOnEntryClickListener(OnEntryClickListener listener) {

		this.mEntryListener = listener;
	}


	/**
	 * Register a listener to be called when the {@link ChartView} is clicked.
	 *
	 * @param listener Listener to be used for callback.
	 */
	@Override
	public void setOnClickListener(OnClickListener listener) {

		this.mChartListener = listener;
	}


	/**
	 * The method listens chart clicks and checks whether it intercepts
	 * a known Region. It will then use the registered Listener.onClick
	 * to return the region's index.
	 */
	@Override
	public boolean onTouchEvent(@NonNull MotionEvent event) {

		if (mAnim == null || !mAnim.isPlaying())

			if (event.getAction() == MotionEvent.ACTION_DOWN &&
					  (mTooltip != null || mEntryListener != null) &&
					  mRegions != null) {

				//Check if ACTION_DOWN over any ScreenPoint region.
				int nSets = mRegions.size();
				int nEntries = mRegions.get(0).size();
				for (int i = 0; i < nSets; i++) {
					for (int j = 0; j < nEntries; j++) {

						if (mRegions.get(i).get(j).contains((int) event.getX(), (int) event.getY())) {
							mSetClicked = i;
							mIndexClicked = j;
						}
					}
				}

			} else if (event.getAction() == MotionEvent.ACTION_UP) {

				if (mSetClicked != -1 && mIndexClicked != -1) {
					if (mRegions.get(mSetClicked)
							  .get(mIndexClicked)
							  .contains((int) event.getX(), (int) event.getY())) {

						if (mEntryListener != null) {
							mEntryListener.onClick(mSetClicked, mIndexClicked,
									  new Rect(getEntryRect(mRegions.get(mSetClicked).get(mIndexClicked))));
						}

						if (mTooltip != null) {
							toggleTooltip(getEntryRect(mRegions.get(mSetClicked).get(mIndexClicked)),
									  data.get(mSetClicked).getValue(mIndexClicked));
						}

					}
					mSetClicked = -1;
					mIndexClicked = -1;

				} else {

					if (mChartListener != null) mChartListener.onClick(this);

					if (mTooltip != null && mTooltip.on()) dismissTooltip(mTooltip);
				}
			}

		return true;
	}


	@Override
	protected void onDraw(Canvas canvas) {

		mIsDrawing = true;
		super.onDraw(canvas);

		if (mReadyToDraw) {
			//long time = System.currentTimeMillis();

			// Draw grid
			if (mGridType == GridType.FULL || mGridType == GridType.VERTICAL) drawVerticalGrid(canvas);
			if (mGridType == GridType.FULL || mGridType == GridType.HORIZONTAL)
				drawHorizontalGrid(canvas);

			// Draw Axis Y
			yRndr.draw(canvas);

			// Draw threshold
			if (mHasThresholdValue)
				drawThreshold(canvas, getInnerChartLeft(), mThresholdStartValue, getInnerChartRight(),
						  mThresholdEndValue);
			if (mHasThresholdLabel)
				drawThreshold(canvas, data.get(0).getEntry(mThresholdStartLabel).getX(),
						  getInnerChartTop(), data.get(0).getEntry(mThresholdEndLabel).getX(),
						  getInnerChartBottom());

			// Draw data
			if (!data.isEmpty()) onDrawChart(canvas, data);

			// Draw axis X
			xRndr.draw(canvas);

			//System.out.println("Time drawing "+(System.currentTimeMillis() - time));
		}

		mIsDrawing = false;
	}


	/**
	 * @param spacing Spacing between left/right of the chart and the first/last label
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setBorderSpacing(float spacing) {

		if (mOrientation == Orientation.VERTICAL) xRndr.setBorderSpacing(spacing);
		else yRndr.setBorderSpacing(spacing);

		return this;
	}


	/**
	 * @param spacing Spacing between top of the chart and the first label
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setTopSpacing(float spacing) {

		if (mOrientation == Orientation.VERTICAL) yRndr.setTopSpacing(spacing);
		else xRndr.setBorderSpacing(spacing);

		return this;
	}


	/**
	 * Apply grid to chart.
	 *
	 * @param type {@link GridType} for grid
	 * @param paint The Paint instance that will be used to draw the grid
	 * If null the grid won't be drawn
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setGrid(GridType type, Paint paint) {

		mGridType = type;
		style.gridPaint = paint;
		return this;
	}


	/**
	 * Apply grid to chart.
	 *
	 * @param type {@link GridType} for grid
	 * @param rows Grid's number of rows
	 * @param columns Grid's number of columns
	 * @param paint The Paint instance that will be used to draw the grid
	 * If null the grid won't be drawn
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setGrid(GridType type, @IntRange(from = 1) int rows,
			  @IntRange(from = 1) int columns, Paint paint) {

		if (rows < 1 || columns < 1)
			throw new IllegalArgumentException("Number of rows/columns can't be lesser than 1.");

		mGridType = type;
		mGridNRows = rows;
		mGridNColumns = columns;
		style.gridPaint = paint;
		return this;
	}


	/**
	 * Display a value threshold either in a form of line or band.
	 * In order to produce a line, the start and end value will be equal.
	 *
	 * @param startValue Threshold value.
	 * @param endValue Threshold value.
	 * @param paint The Paint instance that will be used to draw the grid
	 * If null the grid won't be drawn
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setValueThreshold(float startValue, float endValue, Paint paint) {

		mHasThresholdValue = true;
		mThresholdStartValue = startValue;
		mThresholdEndValue = endValue;
		style.thresholdPaint = paint;
		return this;
	}


	/**
	 * Display a label threshold either in a form of line or band.
	 * In order to produce a line, the start and end label will be equal.
	 *
	 * @param startLabel Threshold start label index.
	 * @param endLabel Threshold end label index.
	 * @param paint The Paint instance that will be used to draw the grid
	 * If null the grid won't be drawn
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setLabelThreshold(int startLabel, int endLabel, Paint paint) {

		mHasThresholdLabel = true;
		mThresholdStartLabel = startLabel;
		mThresholdEndLabel = endLabel;
		style.thresholdPaint = paint;
		return this;
	}


	/**
	 * Set spacing between Labels and Axis. Will be applied to both X and Y.
	 *
	 * @param spacing Spacing between labels and axis
	 *
	 * @return {@link com.db.chart.view.ChartView} self-reference.
	 */
	public ChartView setAxisLabelsSpacing(float spacing) {

		xRndr.setAxisLabelsSpacing(spacing);
		yRndr.setAxisLabelsSpacing(spacing);
		return this;
	}


	/**
	 * Mandatory horizontal border when necessary (ex: BarCharts)
	 * Sets the attribute depending on the chart's orientation.
	 * e.g. If orientation is VERTICAL it means that this attribute must be handled
	 * by horizontal axis and not the vertical axis.
	 */
	void setMandatoryBorderSpacing() {

		if (mOrientation == Orientation.VERTICAL) xRndr.setMandatoryBorderSpacing(true);
		else yRndr.setMandatoryBorderSpacing(true);
	}


	/**
	 * Set the {@link Tooltip} object which will be used to create chart tooltips.
	 *
	 * @param tip {@link Tooltip} object in order to produce chart tooltips
	 */
	public void setTooltips(Tooltip tip) {

		mTooltip = tip;
	}


	/**
	 * Applies an alpha to the paint object.
	 *
	 * @param paint {@link android.graphics.Paint} object to apply alpha.
	 * @param alpha Alpha value (opacity).
	 * @param entry Entry containing shadow customization.
	 */
	protected void applyShadow(Paint paint, float alpha, ChartEntry entry) {

		paint.setShadowLayer(entry.getShadowRadius(), entry.getShadowDx(), entry.getShadowDy(),
				  Color.argb(((int) (alpha * 255) < entry.getShadowColor()[0]) ? (int) (alpha * 255) :
										entry.getShadowColor()[0], entry.getShadowColor()[1],
							 entry.getShadowColor()[2], entry.getShadowColor()[3]));
	}


	public enum GridType {
		FULL, VERTICAL, HORIZONTAL, NONE
	}


	public enum Orientation {
		HORIZONTAL, VERTICAL
	}


	
	/*
	 * ----------
	 *    Style
	 * ----------
	 */


	/**
	 * Class responsible to style the Graph!
	 * Can be instantiated with or without attributes.
	 */
	public class Style {

		private final static int DEFAULT_COLOR = Color.BLACK;


		/** Chart */
		private Paint chartPaint;

		/** Axis */
		private float axisThickness;

		private int axisColor;

		private boolean hasXAxis;

		private boolean hasYAxis;

		/** Grid */
		private Paint gridPaint;

		/** Threshold **/
		private Paint thresholdPaint;

		/** Font */
		private Paint labelsPaint;

		private int labelsColor;

		private float fontSize;

		private Typeface typeface;

		/**
		 * Height of the text based on the font style defined.
		 * Includes uppercase height and bottom padding of special
		 * lowercase letter such as g, p, etc.
		 */
		private int fontMaxHeight;


		Style() {

			axisColor = DEFAULT_COLOR;
			axisThickness = ctx.getResources().getDimension(R.dimen.grid_thickness);
			hasXAxis = true;
			hasYAxis = true;

			labelsColor = DEFAULT_COLOR;
			fontSize = ctx.getResources().getDimension(R.dimen.font_size);
		}


		Style(TypedArray attrs) {

			axisColor = attrs.getColor(R.styleable.ChartAttrs_chart_axisColor, DEFAULT_COLOR);
			axisThickness = attrs.getDimension(R.styleable.ChartAttrs_chart_axisThickness,
					  getResources().getDimension(R.dimen.axis_thickness));

			labelsColor = attrs.getColor(R.styleable.ChartAttrs_chart_labelColor, DEFAULT_COLOR);
			fontSize = attrs.getDimension(R.styleable.ChartAttrs_chart_fontSize,
					  getResources().getDimension(R.dimen.font_size));

			String typefaceName = attrs.getString(R.styleable.ChartAttrs_chart_typeface);
			if (typefaceName != null) typeface = Typeface.createFromAsset(getResources().
					  getAssets(), typefaceName);
		}


		private void init() {

			chartPaint = new Paint();
			chartPaint.setColor(axisColor);
			chartPaint.setStyle(Paint.Style.STROKE);
			chartPaint.setStrokeWidth(axisThickness);
			chartPaint.setAntiAlias(true);

			labelsPaint = new Paint();
			labelsPaint.setColor(labelsColor);
			labelsPaint.setStyle(Paint.Style.FILL_AND_STROKE);
			labelsPaint.setAntiAlias(true);
			labelsPaint.setTextSize(fontSize);
			labelsPaint.setTypeface(typeface);

			fontMaxHeight = (int) (style.labelsPaint.descent() - style.labelsPaint.ascent());
		}


		public void clean() {

			chartPaint = null;
			labelsPaint = null;
			gridPaint = null;
			thresholdPaint = null;
		}


		/**
		 * Get label's height.
		 *
		 * @param text Label to measure
		 *
		 * @return Height of label
		 */
		public int getLabelHeight(String text) {

			final Rect rect = new Rect();
			style.labelsPaint.getTextBounds(text, 0, text.length(), rect);
			return rect.height();
		}


		public Paint getChartPaint() {

			return chartPaint;
		}


		public float getAxisThickness() {

			return axisThickness;
		}


		public boolean hasXAxis() {

			return hasXAxis;
		}


		public boolean hasYAxis() {

			return hasYAxis;
		}


		public Paint getLabelsPaint() {

			return labelsPaint;
		}


		public int getFontMaxHeight() {

			return fontMaxHeight;
		}
	}


}
