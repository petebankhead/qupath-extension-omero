package qupath.ext.omero.core.pixelapis.mspixelbuffer;

import loci.formats.gui.AWTImageTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.omero.core.apis.ApisHandler;
import qupath.ext.omero.core.pixelapis.PixelApiReader;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;

import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferDouble;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferShort;
import java.awt.image.DataBufferUShort;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;

/**
 * Read pixel values using the <a href="https://github.com/glencoesoftware/omero-ms-pixel-buffer">OMERO pixel buffer microservice</a>.
 */
class MsPixelBufferReader implements PixelApiReader {

    private static final Logger logger = LoggerFactory.getLogger(MsPixelBufferReader.class);
    private static final String TILE_URI = "%s/tile/%d/%d/%d/%d?x=%d&y=%d&w=%d&h=%d&format=tif&resolution=%d";
    private final String host;
    private final ApisHandler apisHandler;
    private final long imageID;
    private final PixelType pixelType;
    private final int numberOfChannels;
    private final ColorModel colorModel;
    private final int numberOfLevels;

    /**
     * Create a new pixel buffer microservice reader.
     *
     * @param host the URI from which this microservice is available
     * @param apisHandler the apis handler to use when sending requests
     * @param imageID the ID of the image to open
     * @param pixelType the pixel type of the image to open
     * @param channels the channels of the image to open
     * @param numberOfLevels the number of resolution levels of the image to open
     */
    public MsPixelBufferReader(
            String host,
            ApisHandler apisHandler,
            long imageID,
            PixelType pixelType,
            List<ImageChannel> channels,
            int numberOfLevels
    ) {
        this.host = host;
        this.apisHandler = apisHandler;
        this.imageID = imageID;
        this.pixelType = pixelType;
        this.numberOfChannels = channels.size();
        this.colorModel = ColorModelFactory.createColorModel(pixelType, channels);
        this.numberOfLevels = numberOfLevels;

        logger.debug("Created pixel buffer microservice reader for {}", host);
    }

    @Override
    public BufferedImage readTile(TileRequest tileRequest) throws IOException {
        logger.debug("Reading tile {} from pixel buffer microservice API", tileRequest);

        // OMERO expects resolutions to be specified in reverse order
        int level = numberOfLevels - tileRequest.getLevel() - 1;

        List<BufferedImage> images;
        try {
            images = IntStream.range(0, numberOfChannels)
                    .mapToObj(i -> readTile(
                            imageID,
                            i,
                            level,
                            tileRequest
                    ))
                    .map(CompletableFuture::join)
                    .toList();
        } catch (CompletionException e) {
            throw new IOException(e);
        }
        logger.debug("Got images {} for {}. Combining them", images, tileRequest);

        if (numberOfChannels == 1 && pixelType.equals(PixelType.UINT8)) {
            return images.getFirst();
        } else {
            DataBuffer dataBuffer = getDataBuffer(images.stream()
                    .map(AWTImageTools::getPixels)
                    .toList()
            );

            return new BufferedImage(
                    colorModel,
                    WritableRaster.createWritableRaster(
                            new BandedSampleModel(
                                    dataBuffer.getDataType(),
                                    tileRequest.getTileWidth(),
                                    tileRequest.getTileHeight(),
                                    numberOfChannels
                            ),
                            dataBuffer,
                            null
                    ),
                    false,
                    null
            );
        }
    }

    @Override
    public void close() {}

    @Override
    public String toString() {
        return String.format(
                "Pixel buffer microservice reader for image %d of %s",
                imageID,
                host
        );
    }

    private CompletableFuture<BufferedImage> readTile(long imageID, int channel, int level, TileRequest tileRequest) {
        try {
            return apisHandler.getImage(new URI(String.format(TILE_URI,
                    host,
                    imageID,
                    tileRequest.getZ(),
                    channel,
                    tileRequest.getT(),
                    tileRequest.getTileX(),
                    tileRequest.getTileY(),
                    tileRequest.getTileWidth(),
                    tileRequest.getTileHeight(),
                    level
            )));
        } catch (URISyntaxException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private DataBuffer getDataBuffer(List<Object> pixels) {
        return switch (pixelType) {
            case UINT8 -> {
                byte[][] bytes = new byte[pixels.size()][];
                for (int i = 0; i < pixels.size(); i++) {
                    bytes[i] = ((byte[][]) pixels.get(i))[0];
                }
                yield new DataBufferByte(bytes, bytes[0].length);
            }
            case UINT16, INT16 -> {
                short[][] shortArray = new short[pixels.size()][];
                for (int i = 0; i < pixels.size(); i++) {
                    shortArray[i] = ((short[][]) pixels.get(i))[0];
                }
                yield pixelType.equals(PixelType.UINT16) ?
                        new DataBufferUShort(shortArray, shortArray[0].length) :
                        new DataBufferShort(shortArray, shortArray[0].length);
            }
            case INT32 -> {
                int[][] intArray = new int[pixels.size()][];
                for (int i = 0; i < pixels.size(); i++) {
                    intArray[i] = ((int[][]) pixels.get(i))[0];
                }
                yield new DataBufferInt(intArray, intArray[0].length);
            }
            case FLOAT32 -> {
                float[][] floatArray = new float[pixels.size()][];
                for (int c = 0; c < pixels.size(); c++) {
                    floatArray[c] = ((float[][]) pixels.get(c))[0];
                }
                yield  new DataBufferFloat(floatArray, floatArray[0].length);
            }
            case FLOAT64 -> {
                double[][] doubleArray = new double[pixels.size()][];
                for (int c = 0; c < pixels.size(); c++) {
                    doubleArray[c] = ((double[][]) pixels.get(c))[0];
                }
                yield  new DataBufferDouble(doubleArray, doubleArray[0].length);
            }
            default -> throw new UnsupportedOperationException(String.format("Unsupported pixel type: %s ", pixelType));
        };
    }
}
