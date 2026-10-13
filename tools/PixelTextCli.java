import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import ro.pixeltext.core.TextImageCodec;

/** Cross-platform reference encoder/decoder for desktop PNG round trips. */
public final class PixelTextCli {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("encode INPUT.txt OUTPUT.png [auto|predictive|deflate]\ndecode INPUT.png OUTPUT.txt");
            System.exit(2);
        }
        if (args[0].equals("encode")) {
            TextImageCodec.Mode mode = args.length > 3 ? TextImageCodec.Mode.valueOf(args[3].toUpperCase(java.util.Locale.ROOT)) : TextImageCodec.Mode.AUTO;
            TextImageCodec.Encoded result = TextImageCodec.encode(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8), mode);
            BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 512, 512, TextImageCodec.toPixels(result), 0, 512);
            if (!ImageIO.write(image, "png", new File(args[2]))) throw new IllegalStateException("PNG writer unavailable.");
            System.out.printf("%s: %,d → %,d bytes, 512×512 PNG%n", result.algorithm, result.originalBytes, result.payloadBytes);
        } else if (args[0].equals("decode")) {
            BufferedImage image = ImageIO.read(new File(args[1]));
            if (image == null || image.getWidth() != 512 || image.getHeight() != 512) throw new IllegalArgumentException("Expected a 512×512 PixelText PNG.");
            TextImageCodec.Decoded result = TextImageCodec.fromPixels(image.getRGB(0, 0, 512, 512, null, 0, 512), 512, 512);
            Files.writeString(Path.of(args[2]), result.text, StandardCharsets.UTF_8);
            System.out.printf("Recovered %,d UTF-8 bytes; integrity verified.%n", result.originalBytes);
        } else throw new IllegalArgumentException("Unknown command.");
    }
}
