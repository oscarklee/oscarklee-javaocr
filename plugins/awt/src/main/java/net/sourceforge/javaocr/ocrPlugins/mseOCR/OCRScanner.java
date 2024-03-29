// OCRScanner.java
// Copyright (c) 2003-2010 Ronald B. Cemer
// Modified by William Whitney
// All rights reserved.
// This software is released under the BSD license.
// Please see the accompanying LICENSE.txt for details.
package net.sourceforge.javaocr.ocrPlugins.mseOCR;

import net.sourceforge.javaocr.ocrPlugins.imgShearer.ImageShearer;
import net.sourceforge.javaocr.ocrPlugins.levelsCorrector.LevelsCorrector;
import net.sourceforge.javaocr.ocrPlugins.receiptFinder.ReceiptFinder;
import net.sourceforge.javaocr.scanner.*;
import net.sourceforge.javaocr.scanner.accuracy.AccuracyListenerInterface;
import net.sourceforge.javaocr.scanner.accuracy.AccuracyProviderInterface;
import net.sourceforge.javaocr.scanner.accuracy.OCRComp;
import net.sourceforge.javaocr.scanner.accuracy.OCRIdentification;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.*;
import java.util.List;
import java.util.logging.Logger;

/**
 * OCR document scanner.
 *
 * @author Ronald B. Cemer
 */
public class OCRScanner extends DocumentScannerListenerAdaptor implements AccuracyProviderInterface {

    private static final int BEST_MATCH_STORE_COUNT = 8;
    //    private StringBuffer decodeBuffer = new StringBuffer();
    private CharacterRange[] acceptableChars;
    //    private boolean firstRow = false;
    private String newline = System.getProperty("line.separator");
    private HashMap<Character, ArrayList<TrainingImage>> trainingImages = new HashMap<Character, ArrayList<TrainingImage>>();
    private RecognizedChar[] bestChars = new RecognizedChar[BEST_MATCH_STORE_COUNT];
    private double[] bestMSEs = new double[BEST_MATCH_STORE_COUNT];
    private DocumentScanner documentScanner = new DocumentScanner();
    private AccuracyListenerInterface accListener;
    private FoundWord currentWord;
    private List<FoundWord> words;

    public void acceptAccuracyListener(AccuracyListenerInterface listener) {
        accListener = listener;
    }

    /**
     * @return The <code>DocumentScanner</code> instance that is used to scan the document(s).
     *         This is useful if the caller wants to adjust some of the scanner's parameters.
     */
    public DocumentScanner getDocumentScanner() {
        return documentScanner;
    }

    /**
     * Remove all training images from the training set.
     */
    public void clearTrainingImages() {
        trainingImages.clear();
    }

    /**
     * Add training images to the training set.
     *
     * @param images A <code>HashMap</code> using <code>Character</code>s for
     *               the keys.  Each value is an <code>ArrayList</code> of
     *               <code>TrainingImages</code> for the specified character.  The training
     *               images are added to any that may already have been loaded.
     */
    public void addTrainingImages(HashMap<Character, ArrayList<TrainingImage>> images) {
        for (Character key : images.keySet()) {
            ArrayList<TrainingImage> al = images.get(key);
            ArrayList<TrainingImage> oldAl = trainingImages.get(key);
            if (oldAl == null) {
                oldAl = new ArrayList<TrainingImage>();
                trainingImages.put(key, oldAl);
            }
            for (TrainingImage anAl : al) {
                oldAl.add(anAl);
            }
        }
    }

    /**
     * Scan an image and return the decoded text.
     *
     * @param image           The <code>Image</code> to be scanned.
     * @param x1              The leftmost pixel position of the area to be scanned, or
     *                        <code>0</code> to start scanning at the left boundary of the image.
     * @param y1              The topmost pixel position of the area to be scanned, or
     *                        <code>0</code> to start scanning at the top boundary of the image.
     * @param x2              The rightmost pixel position of the area to be scanned, or
     *                        <code>0</code> to stop scanning at the right boundary of the image.
     * @param y2              The bottommost pixel position of the area to be scanned, or
     *                        <code>0</code> to stop scanning at the bottom boundary of the image.
     * @param acceptableChars An array of <code>CharacterRange</code> objects
     *                        representing the ranges of characters which are allowed to be decoded,
     *                        or <code>null</code> to not limit which characters can be decoded.
     * @return The decoded text.
     */
    public java.util.List<FoundWord> scan(
            Image image,
            int x1,
            int y1,
            int x2,
            int y2,
            CharacterRange[] acceptableChars) {

        currentWord = new FoundWord();
        words = new LinkedList<FoundWord>();

        this.acceptableChars = acceptableChars;
        PixelImage pixelImage = new PixelImage(image);
        pixelImage.toGrayScale(true);
        new LevelsCorrector().adjustImageLevels(pixelImage);
        pixelImage.filter();
        new ReceiptFinder().findReceipt(documentScanner, pixelImage);
        pixelImage = new ImageShearer().shearImage(documentScanner, pixelImage);

        BufferedImage newImage = new BufferedImage(pixelImage.width, pixelImage.height, BufferedImage.TYPE_BYTE_INDEXED);
        WritableRaster raster = (WritableRaster) newImage.getData();
        raster.setPixels(0, 0, pixelImage.width, pixelImage.height, pixelImage.pixels);
        newImage.setData(raster);

        documentScanner.scan(pixelImage, this, x1, y1, x2, y2);
        return words;
    }

    @Override
    public void endRow(PixelImage pixelImage, int y1, int y2) {
        //Send accuracy of this identification to the listener
        if (accListener != null) {
            OCRIdentification identAccuracy = new OCRIdentification(OCRComp.MSE);
            identAccuracy.addChar('\n', 0.0);
            accListener.processCharOrSpace(identAccuracy);
        }
    }

    @Override
    public void beginRow(PixelImage pixelImage, int y1, int y2) {
        endWord();
    }

    private void endWord() {
        if (currentWord.getSize() > 0) {
            words.add(currentWord);
        }
        currentWord = new FoundWord();
    }

    @Override
    public void processChar(
            PixelImage pixelImage,
            int x1,
            int y1,
            int x2,
            int y2,
            int rowY1,
            int rowY2) {

        int[] pixels = pixelImage.pixels;
        int w = pixelImage.width;
        int h = pixelImage.height;
        int areaW = x2 - x1, areaH = y2 - y1;
        float aspectRatio = ((float) areaW) / ((float) areaH);
        int rowHeight = rowY2 - rowY1;
        float topWhiteSpaceFraction = (float) (y1 - rowY1) / (float) rowHeight;
        float bottomWhiteSpaceFraction = (float) (rowY2 - y2) / (float) rowHeight;
        Iterator<Character> it;
        if (acceptableChars != null) {
            ArrayList<Character> al = new ArrayList<Character>();
            for (CharacterRange cr : acceptableChars) {
                for (int c = cr.min; c <= cr.max; c++) {
                    Character ch = (char) c;
                    if (al.indexOf(ch) < 0) {
                        al.add(ch);
                    }
                }
            }
            it = al.iterator();
        } else {
            it = trainingImages.keySet().iterator();
        }
        int bestCount = 0;
        while (it.hasNext()) {
            Character ch = it.next();
            ArrayList<TrainingImage> al = trainingImages.get(ch);
            int nimg = al.size();
            if (nimg > 0) {
                double mse = 0.0;
                TrainingImage mseImg = null;
                boolean gotAny = false;
                for (TrainingImage ti : al) {
                    if (isTrainingImageACandidate(
                            aspectRatio,
                            areaW,
                            areaH,
                            topWhiteSpaceFraction,
                            bottomWhiteSpaceFraction,
                            ti)) {
                        double thisMSE = ti.calcMSE(pixels, w, h, x1, y1, x2, y2);
                        if ((!gotAny) || (thisMSE < mse)) {
                            gotAny = true;
                            mse = thisMSE;
                            mseImg = ti;
                        }
                    }
                }
/// Maybe mse should be required to be below a certain threshold before we store it.
/// That would help us to handle things like welded characters, and characters that get improperly
/// split into two or more characters.
                if (gotAny) {
                    boolean inserted = false;
                    for (int i = 0; i < bestCount; i++) {
                        if (mse < bestMSEs[i]) {
                            for (int j = Math.min(bestCount, BEST_MATCH_STORE_COUNT - 1); j > i; j--) {
                                int k = j - 1;
                                bestChars[j] = bestChars[k];
                                bestMSEs[j] = bestMSEs[k];
                            }
                            final FoundChar foundChar = new FoundChar(pixelImage, x1, y1, x2, y2, rowY1, rowY2);
                            bestChars[i] = new RecognizedChar(ch, mseImg, foundChar);
                            bestMSEs[i] = mse;
                            if (bestCount < BEST_MATCH_STORE_COUNT) {
                                bestCount++;
                            }
                            inserted = true;
                            break;
                        }
                    }
                    if ((!inserted) && (bestCount < BEST_MATCH_STORE_COUNT)) {
                        final FoundChar foundChar = new FoundChar(pixelImage, x1, y1, x2, y2, rowY1, rowY2);
                        bestChars[bestCount] = new RecognizedChar(ch, mseImg, foundChar);
                        bestMSEs[bestCount] = mse;
                        bestCount++;
                    }
                }
            }
        }
/// We could also put some aspect ratio range checking into the page scanning logic (but only when
/// decoding; not when loading training images) so that the aspect ratio of a non-empty character
/// block is limited to within the min and max of the aspect ratios in the training set.
        if (bestCount > 0) {
            currentWord.addRecognizedChar(bestChars[0]);

            //Send accuracy of this identification to the listener
            if (accListener != null) {
                OCRIdentification identAccuracy = new OCRIdentification(OCRComp.MSE);
                for (int i = 0; i < bestCount; i++) {
                    identAccuracy.addChar(bestChars[i].getRecognizedChar(), bestMSEs[i]);
                }
                accListener.processCharOrSpace(identAccuracy);
            }

        } else {
            if (accListener != null) {
                OCRIdentification identAccuracy = new OCRIdentification(OCRComp.MSE);
                accListener.processCharOrSpace(identAccuracy);
            }
        }
    }

    private boolean isTrainingImageACandidate(
            float aspectRatio,
            int w,
            int h,
            float topWhiteSpaceFraction,
            float bottomWhiteSpaceFraction,
            TrainingImage ti) {
        // The aspect ratios must be within tolerance.
        if (((aspectRatio / ti.aspectRatio) - 1.0f) > TrainingImage.ASPECT_RATIO_TOLERANCE) {
            return false;
        }
        if (((ti.aspectRatio / aspectRatio) - 1.0f) > TrainingImage.ASPECT_RATIO_TOLERANCE) {
            return false;
        }
        // The top whitespace fractions must be within tolerance.
        if (Math.abs(topWhiteSpaceFraction - ti.topWhiteSpaceFraction)
                > TrainingImage.TOP_WHITE_SPACE_FRACTION_TOLERANCE) {
            return false;
        }
        // The bottom whitespace fractions must be within tolerance.
        if (Math.abs(bottomWhiteSpaceFraction - ti.bottomWhiteSpaceFraction)
                > TrainingImage.BOTTOM_WHITE_SPACE_FRACTION_TOLERANCE) {
            return false;
        }
        // If the area being scanned is really small and we
        // are about to crunch down a training image by a huge
        // factor in order to compare to it, then don't do that.
        if ((w <= 4) && (ti.width >= (w * 10))) {
            return false;
        }
        if ((h <= 4) && (ti.height >= (h * 10))) {
            return false;
        }
        // If the area being scanned is really large and we
        // are about to expand a training image by a huge
        // factor in order to compare to it, then don't do that.
        if ((ti.width <= 4) && (w >= (ti.width * 10))) {
            return false;
        }
        if ((ti.height <= 4) && (h >= (ti.height * 10))) {
            return false;
        }
        return true;
    }

    @Override
    public void processSpace(PixelImage pixelImage, int x1, int y1, int x2, int y2) {
        endWord();
        //Send accuracy of this identification to the listener
        if (accListener != null) {
            OCRIdentification identAccuracy = new OCRIdentification(OCRComp.MSE);
            identAccuracy.addChar(' ', 0.0);
            accListener.processCharOrSpace(identAccuracy);
        }
    }

    private static final Logger LOG = Logger.getLogger(OCRScanner.class.getName());
}
