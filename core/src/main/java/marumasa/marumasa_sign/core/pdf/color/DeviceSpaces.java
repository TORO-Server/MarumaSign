package marumasa.marumasa_sign.core.pdf.color;

/**
 * DeviceGray、DeviceRGB、DeviceCMYK。
 */
public final class DeviceSpaces {

    public static final PdfColorSpace GRAY = new PdfColorSpace() {
        @Override
        public int components() {
            return 1;
        }

        @Override
        public int toRgb(float[] value) {
            return gray(value[0]);
        }
    };

    public static final PdfColorSpace RGB = new PdfColorSpace() {
        @Override
        public int components() {
            return 3;
        }

        @Override
        public int toRgb(float[] value) {
            return rgb(value[0], value[1], value[2]);
        }
    };

    public static final PdfColorSpace CMYK = new PdfColorSpace() {
        @Override
        public int components() {
            return 4;
        }

        @Override
        public int toRgb(float[] value) {
            return CmykConverter.toRgb(value[0], value[1], value[2], value[3]);
        }

        @Override
        public float[] initialColor() {
            return new float[]{0, 0, 0, 1};
        }
    };

    private DeviceSpaces() {
    }
}
