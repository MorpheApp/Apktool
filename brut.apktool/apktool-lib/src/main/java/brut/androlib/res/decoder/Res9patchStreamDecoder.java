/*
 *  Copyright (C) 2010 Ryszard Wiśniewski <brut.alll@gmail.com>
 *  Copyright (C) 2010 Connor Tumbleson <connor.tumbleson@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package brut.androlib.res.decoder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import brut.androlib.exceptions.AndrolibException;
import brut.androlib.exceptions.CantFind9PatchChunkException;
import brut.androlib.res.data.ninepatch.NinePatchData;
import brut.androlib.res.data.ninepatch.OpticalInset;
import brut.util.ExtDataInput;
import brut.util.ExtDataInputStream;
import brut.util.OSDetection;
import org.apache.commons.io.IOUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.*;

public class Res9patchStreamDecoder implements ResStreamDecoder {
    private static final int NP_CHUNK_TYPE = 0x6e705463; // npTc
    private static final int OI_CHUNK_TYPE = 0x6e704c62; // npLb
    private static final int NP_COLOR = 0xff000000;
    private static final int OI_COLOR = 0xffff0000;

    private static OSDecoder decoder;

    {
        decoder = OSDetection.isAndroid()
            ? new AndroidImpl()
            : new OtherImpl();
    }

    @Override
    public void decode(InputStream in, OutputStream out) throws AndrolibException {
        try {
            byte[] data = IOUtils.toByteArray(in);
            if (data.length == 0) return;
            decoder.decode(data, out);
        } catch (IOException | NullPointerException ex) {
            // In my case this was triggered because a .png file was
            // containing a html document instead of an image.
            // This could be more verbose and try to MIME ?
            throw new AndrolibException(ex);
        }
    }

    private NinePatchData getNinePatch(byte[] data) throws AndrolibException, IOException {
        ExtDataInput in = ExtDataInputStream.bigEndian(new ByteArrayInputStream(data));
        find9patchChunk(in, NP_CHUNK_TYPE);
        return NinePatchData.decode(in);
    }

    private OpticalInset getOpticalInset(byte[] data) throws AndrolibException, IOException {
        ExtDataInput in = ExtDataInputStream.bigEndian(new ByteArrayInputStream(data));
        find9patchChunk(in, OI_CHUNK_TYPE);
        return OpticalInset.decode(in);
    }

    private void find9patchChunk(DataInput in, int magic) throws AndrolibException, IOException {
        in.skipBytes(8);
        while (true) {
            int size;
            try {
                size = in.readInt();
            } catch (IOException ex) {
                throw new CantFind9PatchChunkException("Could not find nine patch chunk", ex);
            }
            if (in.readInt() == magic) {
                return;
            }
            in.skipBytes(size + 4);
        }
    }

    private void drawHLine(Bitmap bm, int y, int x1, int x2) {
        for (int x = x1; x <= x2; x++) {
            bm.setPixel(x, y, NP_COLOR);
        }
    }

    private void drawHLine(BufferedImage im, int y, int x1, int x2) {
        for (int x = x1; x <= x2; x++) {
            im.setRGB(x, y, NP_COLOR);
        }
    }

    private void drawVLine(Bitmap bm, int x, int y1, int y2) {
        for (int y = y1; y <= y2; y++) {
            bm.setPixel(x, y, NP_COLOR);
        }
    }

    private void drawVLine(BufferedImage im, int x, int y1, int y2) {
        for (int y = y1; y <= y2; y++) {
            im.setRGB(x, y, NP_COLOR);
        }
    }

    // OS implementations
    private interface OSDecoder {
        void decode(byte[] data, OutputStream out) throws IOException, AndrolibException;
    }

    private class AndroidImpl implements OSDecoder {
        public void decode(byte[] data, OutputStream out) throws IOException, AndrolibException {
            Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);

            int width = bm.getWidth(), height = bm.getHeight();

            Bitmap.Config config = bm.getConfig();
            if (config == null) config = Bitmap.Config.ARGB_8888;

            Bitmap outImg = Bitmap.createBitmap(width + 2, height + 2, config);

            for (int w = 0; w < width; w++)
                for (int h = 0; h < height; h++) outImg.setPixel(w + 1, h + 1, bm.getPixel(w, h));

            NinePatchData np = getNinePatch(data);
            drawHLine(outImg, height + 1, np.padLeft + 1, width - np.padRight);
            drawVLine(outImg, width + 1, np.padTop + 1, height - np.padBottom);

            int[] xDivs = np.xDivs;
            if (xDivs.length == 0) {
                drawHLine(outImg, 0, 1, width);
            } else {
                for (int i = 0; i < xDivs.length; i += 2) {
                    drawHLine(outImg, 0, xDivs[i] + 1, xDivs[i + 1]);
                }
            }

            int[] yDivs = np.yDivs;
            if (yDivs.length == 0) {
                drawVLine(outImg, 0, 1, height);
            } else {
                for (int i = 0; i < yDivs.length; i += 2) {
                    drawVLine(outImg, 0, yDivs[i] + 1, yDivs[i + 1]);
                }
            }

            // Some images additionally use Optical Bounds
            // https://developer.android.com/about/versions/android-4.3.html#OpticalBounds
            try {
                OpticalInset oi = getOpticalInset(data);

                for (int i = 0; i < oi.layoutBoundsLeft; i++) {
                    int x = 1 + i;
                    outImg.setPixel(x, height + 1, OI_COLOR);
                }

                for (int i = 0; i < oi.layoutBoundsRight; i++) {
                    int x = width - i;
                    outImg.setPixel(x, height + 1, OI_COLOR);
                }

                for (int i = 0; i < oi.layoutBoundsTop; i++) {
                    int y = 1 + i;
                    outImg.setPixel(width + 1, y, OI_COLOR);
                }

                for (int i = 0; i < oi.layoutBoundsBottom; i++) {
                    int y = height - i;
                    outImg.setPixel(width + 1, y, OI_COLOR);
                }
            } catch (CantFind9PatchChunkException t) {
                // This chunk might not exist
            }

            outImg.compress(Bitmap.CompressFormat.PNG, 100, out);
            bm.recycle();
            outImg.recycle();
        }
    }

    private class OtherImpl implements OSDecoder {
        public void decode(byte[] data, OutputStream out) throws IOException, AndrolibException {
            BufferedImage im = ImageIO.read(new ByteArrayInputStream(data));
            int w = im.getWidth(), h = im.getHeight();

            BufferedImage im2 = new BufferedImage(w + 2, h + 2, BufferedImage.TYPE_INT_ARGB);
            if (im.getType() == BufferedImage.TYPE_CUSTOM) {
                //TODO: Ensure this is gray + alpha case?
                Raster srcRaster = im.getRaster();
                WritableRaster dstRaster = im2.getRaster();
                int[] gray = null, alpha = null;
                for (int y = 0; y < im.getHeight(); y++) {
                    gray = srcRaster.getSamples(0, y, w, 1, 0, gray);
                    alpha = srcRaster.getSamples(0, y, w, 1, 1, alpha);

                    dstRaster.setSamples(1, y + 1, w, 1, 0, gray);
                    dstRaster.setSamples(1, y + 1, w, 1, 1, gray);
                    dstRaster.setSamples(1, y + 1, w, 1, 2, gray);
                    dstRaster.setSamples(1, y + 1, w, 1, 3, alpha);
                }
            } else {
                im2.createGraphics().drawImage(im, 1, 1, w, h, null);
            }

            NinePatchData np = getNinePatch(data);
            drawHLine(im2, h + 1, np.padLeft + 1, w - np.padRight);
            drawVLine(im2, w + 1, np.padTop + 1, h - np.padBottom);

            int[] xDivs = np.xDivs;
            if (xDivs.length == 0) {
                drawHLine(im2, 0, 1, w);
            } else {
                for (int i = 0; i < xDivs.length; i += 2) {
                    drawHLine(im2, 0, xDivs[i] + 1, xDivs[i + 1]);
                }
            }

            int[] yDivs = np.yDivs;
            if (yDivs.length == 0) {
                drawVLine(im2, 0, 1, h);
            } else {
                for (int i = 0; i < yDivs.length; i += 2) {
                    drawVLine(im2, 0, yDivs[i] + 1, yDivs[i + 1]);
                }
            }

            // Some images additionally use Optical Bounds
            // https://developer.android.com/about/versions/android-4.3.html#OpticalBounds
            try {
                OpticalInset oi = getOpticalInset(data);

                for (int i = 0; i < oi.layoutBoundsLeft; i++) {
                    int x = 1 + i;
                    im2.setRGB(x, h + 1, OI_COLOR);
                }

                for (int i = 0; i < oi.layoutBoundsRight; i++) {
                    int x = w - i;
                    im2.setRGB(x, h + 1, OI_COLOR);
                }

                for (int i = 0; i < oi.layoutBoundsTop; i++) {
                    int y = 1 + i;
                    im2.setRGB(w + 1, y, OI_COLOR);
                }

                for (int i = 0; i < oi.layoutBoundsBottom; i++) {
                    int y = h - i;
                    im2.setRGB(w + 1, y, OI_COLOR);
                }
            } catch (CantFind9PatchChunkException t) {
                // This chunk might not exist
            }

            ImageIO.write(im2, "png", out);
        }
    }
}
