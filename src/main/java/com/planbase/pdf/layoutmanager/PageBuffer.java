package com.planbase.pdf.layoutmanager;

import org.apache.pdfbox.pdmodel.edit.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.xobject.PDJpeg;
import org.apache.pdfbox.pdmodel.graphics.xobject.PDPixelMap;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Please don't access this class directly if you don't have to.  It's a little bit like a model for stuff that
 * needs to be drawn on a page, but much more like a heap of random functionality that sort of landed in an
 * inner class.  This will probably be refactored away in future releases.
 */
class PageBuffer {

    public final int pageNum;
    private long lastOrd = 0;
    private final Set<PdfItem> items = new TreeSet<PdfItem>();

    PageBuffer(int pn) {
        pageNum = pn;
    }

    void fillRect(final float xVal, final float yVal, final float w, final float h,
                  final Color c, final float z) {
        items.add(FillRect.of(xVal, yVal, w, h, c, lastOrd++, z));
    }

    void drawJpeg(final float xVal, final float yVal, final ScaledJpeg sj,
                  final PdfLayoutMgr mgr) {
        items.add(DrawJpeg.of(xVal, yVal, sj, mgr, lastOrd++, PdfItem.DEFAULT_Z_INDEX));
    }

    void drawPng(final float xVal, final float yVal, final ScaledPng sj,
                 final PdfLayoutMgr mgr) {
        items.add(DrawPng.of(xVal, yVal, sj, mgr, lastOrd++, PdfItem.DEFAULT_Z_INDEX));
    }

    private void drawLine(final float xa, final float ya, final float xb,
                          final float yb, final LineStyle ls, final float z) {
        items.add(DrawLine.of(xa, ya, xb, yb, ls, lastOrd++, z));
    }

    void drawLine(final float xa, final float ya, final float xb, final float yb,
                  final LineStyle ls) {
        drawLine(xa, ya, xb, yb, ls, PdfItem.DEFAULT_Z_INDEX);
    }

    private void drawStyledText(final float xCoord, final float yCoord, final String text,
                                TextStyle s, final float z) {
        items.add(Text.of(xCoord, yCoord, text, s, lastOrd++, z));
    }

    void drawStyledText(final float xCoord, final float yCoord, final String text,
                        TextStyle s) {
        drawStyledText(xCoord, yCoord, text, s, PdfItem.DEFAULT_Z_INDEX);
    }

    void commit(PDPageContentStream stream) throws IOException {
        // Since items are z-ordered, then sub-ordered by entry-order, we will draw
        // everything in the correct order.
        for (PdfItem item : items) {
            item.commit(stream);
        }
    }

    private static class DrawLine extends PdfItem {
        private final float x1, y1, x2, y2;
        private final LineStyle style;

        private DrawLine(final float xa, final float ya, final float xb, final float yb,
                         LineStyle s,
                         final long ord, final float z) {
            super(ord, z);
            x1 = xa;
            y1 = ya;
            x2 = xb;
            y2 = yb;
            style = s;
        }

        public static DrawLine of(final float xa, final float ya, final float xb,
                                  final float yb, LineStyle s,
                                  final long ord, final float z) {
            return new DrawLine(xa, ya, xb, yb, s, ord, z);
        }

        @Override
        public void commit(PDPageContentStream stream) throws IOException {
            stream.setStrokingColor(style.color());
            stream.setLineWidth(style.width());
            stream.drawLine(x1, y1, x2, y2);
        }
    }

    private static class FillRect extends PdfItem {
        private final float x, y, width, height;
        private final Color color;

        private FillRect(final float xVal, final float yVal, final float w, final float h,
                         final Color c, final long ord, final float z) {
            super(ord, z);
            x = xVal;
            y = yVal;
            width = w;
            height = h;
            color = c;
        }

        public static FillRect of(final float xVal, final float yVal, final float w,
                                  final float h, final Color c, final long ord, final float z) {
            return new FillRect(xVal, yVal, w, h, c, ord, z);
        }

        @Override
        public void commit(PDPageContentStream stream) throws IOException {
            stream.setNonStrokingColor(color);
            stream.fillRect(x, y, width, height);
        }
    }

    static class Text extends PdfItem {
        public final float x, y;
        public final String t;
        public final TextStyle style;

        private Text(final float xCoord, final float yCoord, final String text,
                     TextStyle s, final long ord, final float z) {
            super(ord, z);
            x = xCoord;
            y = yCoord;
            t = text;
            style = s;
        }

        public static Text of(final float xCoord, final float yCoord, final String text,
                              TextStyle s, final long ord, final float z) {
            return new Text(xCoord, yCoord, text, s, ord, z);
        }

        @Override
        public void commit(PDPageContentStream stream) throws IOException {
            stream.beginText();
            stream.setNonStrokingColor(style.textColor());
            stream.setFont(style.font(), style.fontSize());
            stream.moveTextPositionByAmount(x, y);
            stream.drawString(t);
            stream.endText();
        }
    }

    private static class DrawPng extends PdfItem {
        private final float x, y;
        private final PDPixelMap png;
        private final ScaledPng scaledPng;

        // private Log logger = LogFactory.getLog(DrawPng.class);

        private DrawPng(final float xVal, final float yVal, final ScaledPng sj,
                        final PdfLayoutMgr mgr,
                        final long ord, final float z) {
            super(ord, z);
            x = xVal;
            y = yVal;
            png = mgr.ensureCached(sj);
            scaledPng = sj;
        }

        public static DrawPng of(final float xVal, final float yVal, final ScaledPng sj,
                                 final PdfLayoutMgr mgr,
                                 final long ord, final float z) {
            return new DrawPng(xVal, yVal, sj, mgr, ord, z);
        }

        @Override
        public void commit(PDPageContentStream stream) throws IOException {
            // stream.drawImage(png, x, y);
            XyDim dim = scaledPng.dimensions();
            stream.drawXObject(png, x, y, dim.x(), dim.y());
        }
    }

    private static class DrawJpeg extends PdfItem {
        private final float x, y;
        private final PDJpeg jpeg;
        private final ScaledJpeg scaledJpeg;

        // private Log logger = LogFactory.getLog(DrawJpeg.class);

        private DrawJpeg(final float xVal, final float yVal, final ScaledJpeg sj,
                         final PdfLayoutMgr mgr,
                         final long ord, final float z) {
            super(ord, z);
            x = xVal;
            y = yVal;
            jpeg = mgr.ensureCached(sj);
            scaledJpeg = sj;
        }

        public static DrawJpeg of(final float xVal, final float yVal, final ScaledJpeg sj,
                                  final PdfLayoutMgr mgr,
                                  final long ord, final float z) {
            return new DrawJpeg(xVal, yVal, sj, mgr, ord, z);
        }

        @Override
        public void commit(PDPageContentStream stream) throws IOException {
            // stream.drawImage(jpeg, x, y);
            XyDim dim = scaledJpeg.dimensions();
            stream.drawXObject(jpeg, x, y, dim.x(), dim.y());
        }
    }
}
