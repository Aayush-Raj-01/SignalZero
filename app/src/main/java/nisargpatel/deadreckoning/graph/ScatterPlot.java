package nisargpatel.deadreckoning.graph;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;

/** Lightweight scatter plot used by the sensor calibration/debug screen. */
public class ScatterPlot {

    private final String seriesName;
    private final ArrayList<Double> xList = new ArrayList<>();
    private final ArrayList<Double> yList = new ArrayList<>();

    public ScatterPlot(String seriesName) {
        this.seriesName = seriesName;
    }

    public View getGraphView(Context context) {
        return new PlotView(context);
    }

    public void addPoint(double x, double y) {
        xList.add(x);
        yList.add(y);
    }

    public float getLastXPoint() {
        return xList.get(xList.size() - 1).floatValue();
    }

    public float getLastYPoint() {
        return yList.get(yList.size() - 1).floatValue();
    }

    public void clearSet() {
        xList.clear();
        yList.clear();
    }

    private double getMaxBound() {
        double max = 0;
        for (double value : xList) max = Math.max(max, Math.abs(value));
        for (double value : yList) max = Math.max(max, Math.abs(value));
        return Math.ceil(max / 100.0) * 100.0 + 100.0;
    }

    private final class PlotView extends View {
        private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PlotView(Context context) {
            super(context);
            axisPaint.setColor(Color.LTGRAY);
            axisPaint.setStrokeWidth(2f);
            pointPaint.setColor(Color.parseColor("#ff0099ff"));
            pointPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(Color.DKGRAY);
            textPaint.setTextSize(36f);
            setBackgroundColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = 56f;
            float top = 64f;
            float right = getWidth() - 40f;
            float bottom = getHeight() - 48f;
            if (right <= left || bottom <= top) return;

            float centerX = (left + right) / 2f;
            float centerY = (top + bottom) / 2f;
            canvas.drawText(seriesName, left, 40f, textPaint);
            canvas.drawLine(left, centerY, right, centerY, axisPaint);
            canvas.drawLine(centerX, top, centerX, bottom, axisPaint);

            double bound = getMaxBound();
            double scale = Math.min((right - left) / (2.0 * bound),
                    (bottom - top) / (2.0 * bound));
            for (int i = 0; i < xList.size(); i++) {
                float x = (float) (centerX + xList.get(i) * scale);
                float y = (float) (centerY - yList.get(i) * scale);
                canvas.drawCircle(x, y, 5f, pointPaint);
            }
        }
    }
}
