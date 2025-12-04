package brut.androlib.res.decoder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import brut.androlib.exceptions.AndrolibException;
import brut.androlib.exceptions.NinePatchNotFoundException;
import brut.androlib.res.decoder.data.LayoutBounds;
import brut.androlib.res.decoder.data.NinePatchData;
import brut.util.BinaryDataInputStream;

import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.ByteOrder;

public class ResNinePatchAndroidStreamDecoder implements ResStreamDecoder {
    private static final int NP_CHUNK_TYPE = 0x6e705463; // npTc
    private static final int OI_CHUNK_TYPE = 0x6e704c62; // npLb
    private static final int NP_COLOR = 0xff000000;
    private static final int OI_COLOR = 0xffff0000;

    public void decode(InputStream in, OutputStream out) throws AndrolibException {
        try {
            byte[] data = IOUtils.toByteArray(in);

            if (data.length == 0) {
                return;
            }
            Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
            int width = bm.getWidth(), height = bm.getHeight();

            Bitmap outImg = Bitmap.createBitmap(width + 2, height + 2, bm.getConfig());

            for (int w = 0; w < width; w++)
                for (int h = 0; h < height; h++) outImg.setPixel(w + 1, h + 1, bm.getPixel(w, h));

            NinePatchData np = findNinePatchData(data);
            drawHLineA(outImg, height + 1, np.paddingLeft + 1, width - np.paddingRight);
            drawVLineA(outImg, width + 1, np.paddingTop + 1, height - np.paddingBottom);

            int[] xDivs = np.xDivs;
            if (xDivs.length == 0) {
                drawHLineA(outImg, 0, 1, width);
            } else {
                for (int i = 0; i < xDivs.length; i += 2) {
                    drawHLineA(outImg, 0, xDivs[i] + 1, xDivs[i + 1]);
                }
            }

            int[] yDivs = np.yDivs;
            if (yDivs.length == 0) {
                drawVLineA(outImg, 0, 1, height);
            } else {
                for (int i = 0; i < yDivs.length; i += 2) {
                    drawVLineA(outImg, 0, yDivs[i] + 1, yDivs[i + 1]);
                }
            }

            // Some images optionally use optical inset/layout bounds
            // https://developer.android.com/about/versions/android-4.3.html#OpticalBounds
            try {
                LayoutBounds lb = findLayoutBounds(data);

                for (int i = 0; i < lb.left; i++) {
                    int x = 1 + i;
                    outImg.setPixel(x, height + 1, LayoutBounds.COLOR_TICK);
                }

                for (int i = 0; i < lb.right; i++) {
                    int x = width - i;
                    outImg.setPixel(x, height + 1, LayoutBounds.COLOR_TICK);
                }

                for (int i = 0; i < lb.top; i++) {
                    int y = 1 + i;
                    outImg.setPixel(width + 1, y, LayoutBounds.COLOR_TICK);
                }

                for (int i = 0; i < lb.bottom; i++) {
                    int y = height - i;
                    outImg.setPixel(width + 1, y, LayoutBounds.COLOR_TICK);
                }
            } catch (NinePatchNotFoundException ignored) {
                // This chunk might not exist.
            }

            outImg.compress(Bitmap.CompressFormat.PNG, 100, out);
            bm.recycle();
            outImg.recycle();
        } catch (IOException ex) {
            throw new AndrolibException(ex);
        }
    }

    private NinePatchData findNinePatchData(byte[] data) throws NinePatchNotFoundException, IOException {
        BinaryDataInputStream in = new BinaryDataInputStream(data, ByteOrder.BIG_ENDIAN);
        findChunk(in, NinePatchData.MAGIC);
        return NinePatchData.read(in);
    }

    private LayoutBounds findLayoutBounds(byte[] data) throws NinePatchNotFoundException, IOException {
        BinaryDataInputStream in = new BinaryDataInputStream(data, ByteOrder.BIG_ENDIAN);
        findChunk(in, LayoutBounds.MAGIC);
        return LayoutBounds.read(in);
    }

    private void findChunk(BinaryDataInputStream in, int magic) throws NinePatchNotFoundException, IOException {
        in.skipBytes(8);
        for (;;) {
            int size;
            try {
                size = in.readInt();
            } catch (EOFException ignored) {
                throw new NinePatchNotFoundException();
            }
            if (in.readInt() == magic) {
                return;
            }
            in.skipBytes(size + 4);
        }
    }

    private void drawHLineA(Bitmap bm, int y, int x1, int x2) {
        for (int x = x1; x <= x2; x++) {
            bm.setPixel(x, y, NinePatchData.COLOR_TICK);
        }
    }

    private void drawVLineA(Bitmap bm, int x, int y1, int y2) {
        for (int y = y1; y <= y2; y++) {
            bm.setPixel(x, y, NinePatchData.COLOR_TICK);
        }
    }
}
