// Copyright 2012-01-10 PlanBase Inc. & Glen Peterson
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.planbase.pdf.layoutmanager;

import org.apache.pdfbox.exceptions.COSVisitorException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.edit.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.xobject.PDJpeg;
import org.apache.pdfbox.pdmodel.graphics.xobject.PDPixelMap;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

import static org.apache.pdfbox.pdmodel.PDPage.PAGE_SIZE_LETTER;

/**
 * <p>The main class in this package; it handles page and line breaks.</p>
 * <p>
 * <h3>Usage (the unit test is a much better example):</h3>
 * <pre><code>// Create a new manager
 * PdfLayoutMgr pageMgr = PdfLayoutMgr.newRgbPageMgr();
 *
 * LogicalPage lp = pageMgr.logicalPageStart();
 * // defaults to Landscape orientation
 * // call various lp.tableBuilder() or lp.put...() methods here.
 * // They will page-break and create extra physical pages as needed.
 * // ...
 * lp.commit();
 *
 * lp = pageMgr.logicalPageStart(LogicalPage.Orientation.PORTRAIT);
 * // These pages will be in Portrait orientation
 * // call various lp methods to put things on the next page grouping
 * // ...
 * lp.commit();
 *
 * // The file to write to
 * OutputStream os = new FileOutputStream("test.pdf");
 *
 * // Commit all pages to output stream.
 * pageMgr.save(os);</code></pre>
 * <br>
 * <h3>Note:</h3>
 * <p>Because this class buffers and writes to an underlying stream, it is mutable, has side effects,
 * and is NOT thread-safe!</p>
 */
public class PdfLayoutMgr {

    /**
     * If you use no scaling when printing the output PDF, PDFBox shows approximately 72
     * Document-Units Per Inch.  This makes one pixel on an average desktop monitor correspond to
     * roughly one document unit.  This is a useful constant for page layout math.
     */
    public static final float DOC_UNITS_PER_INCH = 72f;

    private final List<PageBuffer> pages = new ArrayList<PageBuffer>();
    private final PDDocument doc;
    private final boolean overwriteExistingPages;

    // pages.size() counts the first page as 1, so 0 is the appropriate sentinel value
    private int unCommittedPageIdx = 0;

    private final PDColorSpace colorSpace;

    List<PageBuffer> pages() {
        return Collections.unmodifiableList(pages);
    }

    private PdfLayoutMgr(PDColorSpace cs) throws IOException {
        this(new PDDocument(), cs, false);
    }

    private PdfLayoutMgr(PDDocument doc, PDColorSpace cs) throws IOException {
        this(doc, cs, false);
    }

    private PdfLayoutMgr(PDDocument doc, PDColorSpace cs, boolean overwriteExistingPages) {
        this.doc = doc;
        this.colorSpace = cs;
        this.overwriteExistingPages = overwriteExistingPages;
    }

    /**
     * Returns a new PdfLayoutMgr with the given color space.
     *
     * @param cs the color-space.
     * @return a new PdfLayoutMgr
     * @throws IOException
     */
    public static PdfLayoutMgr of(PDColorSpace cs) throws IOException {
        return new PdfLayoutMgr(cs);
    }

    /**
     * Creates a new PdfLayoutMgr with the PDDeviceRGB color space.
     *
     * @return a new Page Manager with an RGB color space
     * @throws IOException
     */
    @SuppressWarnings("UnusedDeclaration") // Part of end-user public interface
    public static PdfLayoutMgr newRgbPageMgr() throws IOException {
        return new PdfLayoutMgr(PDDeviceRGB.INSTANCE);
    }

    /**
     * Creates a new PdfLayoutMgr on an existing document with the PDDeviceRGB color space.
     *
     * @param doc The document to write to
     * @return a new Page Manager with an RGB color space
     * @throws IOException
     */
    @SuppressWarnings("UnusedDeclaration") // Part of the end-user public interface
    public static PdfLayoutMgr newRgbPageMgr(PDDocument doc) throws IOException {
        return new PdfLayoutMgr(doc, PDDeviceRGB.INSTANCE);
    }

    public static PdfLayoutMgr newRgbPageMgr(PDDocument doc, boolean overwriteExistingPages) throws IOException {
        return new PdfLayoutMgr(doc, PDDeviceRGB.INSTANCE, overwriteExistingPages);
    }

    /**
     * Returns the correct page for the given value of y.  This lets the user use any Y value and
     * we continue extending their canvas downward (negative) by adding extra pages.
     *
     * @param y the un-adjusted y value.
     * @return the proper page and adjusted y value for that page.
     */
    LogicalPage.PageBufferAndY appropriatePage(LogicalPage lp, float y) {
        if (pages.size() < 1) {
            throw new IllegalStateException("Cannot work with the any pages until one has been created by calling newPage().");
        }
        int idx = unCommittedPageIdx;
        // Get the first possible page

        while (y < lp.yPageBottom()) {
            // logger.info("Adjusting y.  Was: " + y + " about to add " + printAreaHeight);
            y += lp.printAreaHeight(); // y could even be negative.  Just keep moving to the top of the next
            // page until it's in the printable area.
            idx++;
            if (pages.size() <= idx) {
                pages.add(new PageBuffer(pages.size() + 1));
            }
        }
        PageBuffer ps = pages.get(idx);
        return new LogicalPage.PageBufferAndY(ps, y);
    }

    /**
     * Call this to commit the PDF information to the underlying stream after it is completely built.
     */
    public void save(OutputStream os) throws IOException, COSVisitorException {
        doc.save(os);
        doc.close();
    }

    /**
     * Tells this PdfLayoutMgr that you want to start a new logical page (which may be broken across
     * two or more physical pages) in the requested page orientation and the page size.
     */
    @SuppressWarnings("UnusedDeclaration") // Part of end-user public interface
    public LogicalPage logicalPageStart(LogicalPage.Orientation o, PDRectangle pageSize) {
        PageBuffer pb = new PageBuffer(pages.size() + 1);
        pages.add(pb);
        return LogicalPage.of(this, o, pageSize);
    }

    /**
     * Tells this PdfLayoutMgr that you want to start a new logical page (which may be broken across
     * two or more physical pages) in the requested page orientation.
     */
    @SuppressWarnings("UnusedDeclaration") // Part of end-user public interface
    public LogicalPage logicalPageStart(LogicalPage.Orientation o) {
        return logicalPageStart(o, PAGE_SIZE_LETTER);
    }

    /**
     * Get a new logical page (which may be broken across two or more physical pages) in Landscape orientation.
     */
    public LogicalPage logicalPageStart() {
        return logicalPageStart(LogicalPage.Orientation.LANDSCAPE);
    }

    /**
     * Call this when you are through with your current set of pages to commit all pending text and
     * drawing operations.  This is the only method that throws an IOException because the purpose of
     * PdfLayoutMgr is to buffer all operations until a page is complete so that it can safely be
     * written to the underlying stream.  This method turns the potential pages into real output.
     * Call when you need a page break, or your document is done and you need to write it out.
     *
     * @throws IOException - if there is a failure writing to the underlying stream.
     */
    @SuppressWarnings("UnusedDeclaration")
    // Part of end-user public interface
    void logicalPageEnd(LogicalPage lp) throws IOException {
        List existingPages = doc.getDocumentCatalog().getAllPages();

        // Write out all uncommitted pages.
        while (unCommittedPageIdx < pages.size()) {
            PDPage pdPage = null;

            if (overwriteExistingPages && existingPages.size() > unCommittedPageIdx) {
                pdPage = (PDPage) existingPages.get(unCommittedPageIdx);
            } else {
                pdPage = new PDPage();
                pdPage.setMediaBox(lp.getPageSize());
                if (lp.orientation() == LogicalPage.Orientation.LANDSCAPE) {
                    pdPage.setRotation(90);
                }
                doc.addPage(pdPage);
            }
            PDPageContentStream stream = null;
            try {
                stream = new PDPageContentStream(doc, pdPage, overwriteExistingPages, true);

                if (lp.orientation() == LogicalPage.Orientation.LANDSCAPE) {
                    stream.concatenate2CTM(0, 1, -1, 0, lp.pageWidth(), 0);
                }

                stream.setStrokingColorSpace(colorSpace);
                stream.setNonStrokingColorSpace(colorSpace);

                PageBuffer pb = pages.get(unCommittedPageIdx);
                pb.commit(stream);
                lp.commitBorderItems(stream);

                stream.close();
                // Set to null to show that no exception was thrown and no need to close again.
                stream = null;
            } finally {
                // Let it throw an exception if the closing doesn't work.
                if (stream != null) {
                    stream.close();
                }
            }
            unCommittedPageIdx++;
        }
    }

    @Override
    public boolean equals(Object other) {
        // First, the obvious...
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        if (!(other instanceof PdfLayoutMgr)) {
            return false;
        }
        // Details...
        final PdfLayoutMgr that = (PdfLayoutMgr) other;
        return this.doc.equals(that.doc) && (this.pages.equals(that.pages));
    }

    @Override
    public int hashCode() {
        return doc.hashCode() + pages.hashCode();
    }

    // You can have many DrawJpegs backed by only a few images - it is a flyweight, and this
    // hash map keeps track of the few underlying images, even as intances of DrawJpeg
    // represent all the places where these images are used.
    // CRITICAL: This means that the the set of jpgs must be thrown out and created anew for each
    // document!  Thus, a private final field on the PdfLayoutMgr instead of DrawJpeg, and DrawJpeg
    // must be an inner class (or this would have to be package scoped).
    private final Map<BufferedImage, PDJpeg> jpegMap = new HashMap<BufferedImage, PDJpeg>();

    PDJpeg ensureCached(final ScaledJpeg sj) {
        BufferedImage bufferedImage = sj.bufferedImage();
        PDJpeg temp = jpegMap.get(bufferedImage);
        if (temp == null) {
            try {
                temp = new PDJpeg(doc, bufferedImage);
            } catch (IOException ioe) {
                // can there ever be an exception here?  Doesn't it get written later?
                throw new IllegalStateException("Caught exception creating a PDJpeg from a bufferedImage", ioe);
            }
            jpegMap.put(bufferedImage, temp);
        }
        return temp;
    }

    // You can have many DrawPngs backed by only a few images - it is a flyweight, and this
    // hash map keeps track of the few underlying images, even as intances of DrawPng
    // represent all the places where these images are used.
    // CRITICAL: This means that the the set of jpgs must be thrown out and created anew for each
    // document!  Thus, a private final field on the PdfLayoutMgr instead of DrawPng, and DrawPng
    // must be an inner class (or this would have to be package scoped).
    private final Map<BufferedImage, PDPixelMap> pngMap = new HashMap<BufferedImage, PDPixelMap>();

    PDPixelMap ensureCached(final ScaledPng sj) {
        BufferedImage bufferedImage = sj.bufferedImage();
        PDPixelMap temp = pngMap.get(bufferedImage);
        if (temp == null) {
            try {
                temp = new PDPixelMap(doc, bufferedImage);
            } catch (IOException ioe) {
                // can there ever be an exception here?  Doesn't it get written later?
                throw new IllegalStateException("Caught exception creating a PDPixelMap from a bufferedImage", ioe);
            }
            pngMap.put(bufferedImage, temp);
        }
        return temp;
    }

}
