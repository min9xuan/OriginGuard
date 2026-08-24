package com.originguard.media.application;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.originguard.shared.application.BusinessConflictException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import org.springframework.stereotype.Component;

@Component
public class MediaContentAnalyzer {
    private static final long MAX_PIXELS = 40_000_000L;
    private static final Set<String> EXIF_TAGS = Set.of(
            "Make", "Model", "Software", "Orientation", "Date/Time",
            "Date/Time Original", "Exposure Time", "F-Number", "ISO Speed Ratings",
            "Focal Length", "GPS Latitude", "GPS Longitude");

    public Analysis analyze(byte[] content, String declaredContentType) {
        if (content.length == 0) {
            throw invalid("上传文件为空");
        }
        String detected = detectContentType(content);
        if (declaredContentType != null
                && !declaredContentType.isBlank()
                && !declaredContentType.equalsIgnoreCase(detected)) {
            throw invalid("文件声明类型与实际内容不一致，实际格式为 " + detected);
        }
        Dimensions dimensions = readDimensions(content);
        long pixels = (long) dimensions.width() * dimensions.height();
        if (pixels > MAX_PIXELS) {
            throw invalid("图片解码后的像素总量超过 4000 万安全限制");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(content));
        } catch (Exception exception) {
            throw invalid("图片解码器无法读取该文件");
        }
        if (image == null) {
            throw invalid("上传内容不是可解码的 JPEG、PNG 或 WebP 图片");
        }
        return new Analysis(
                detected,
                image.getWidth(),
                image.getHeight(),
                sha256(content),
                differenceHash(image),
                extractMetadata(content));
    }

    private Dimensions readDimensions(byte[] content) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) throw invalid("无法读取图片文件头");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("当前服务没有可用于该图片格式的安全解码器");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) throw invalid("图片尺寸无效");
                return new Dimensions(width, height);
            } finally {
                reader.dispose();
            }
        } catch (BusinessConflictException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("无法安全解码图片文件头");
        }
    }

    private String detectContentType(byte[] content) {
        if (content.length >= 8
                && (content[0] & 0xff) == 0x89
                && content[1] == 0x50
                && content[2] == 0x4e
                && content[3] == 0x47
                && content[4] == 0x0d
                && content[5] == 0x0a
                && content[6] == 0x1a
                && content[7] == 0x0a) {
            return "image/png";
        }
        if (content.length >= 3
                && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8
                && (content[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (content.length >= 12
                && content[0] == 'R'
                && content[1] == 'I'
                && content[2] == 'F'
                && content[3] == 'F'
                && content[8] == 'W'
                && content[9] == 'E'
                && content[10] == 'B'
                && content[11] == 'P') {
            return "image/webp";
        }
        throw invalid("当前仅支持 JPEG、PNG 和 WebP 图片，请确认文件真实格式");
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String differenceHash(BufferedImage source) {
        BufferedImage resized = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, 9, 8, null);
        } finally {
            graphics.dispose();
        }
        long hash = 0;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = resized.getRaster().getSample(x, y, 0);
                int right = resized.getRaster().getSample(x + 1, y, 0);
                if (left > right) hash |= 1L << bit;
                bit++;
            }
        }
        return "%016x".formatted(hash);
    }

    private Map<String, Object> extractMetadata(byte[] content) {
        Map<String, Object> extracted = new LinkedHashMap<>();
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(content));
            metadata.getDirectories().forEach(directory -> directory.getTags().forEach(tag -> {
                if (EXIF_TAGS.contains(tag.getTagName()) && extracted.size() < 20) {
                    extracted.putIfAbsent(directory.getName() + "." + tag.getTagName(), tag.getDescription());
                }
            }));
            if (metadata.hasErrors()) extracted.put("metadataWarnings", true);
        } catch (Exception exception) {
            extracted.put("metadataReadError", exception.getClass().getSimpleName());
        }
        return Map.copyOf(extracted);
    }

    private BusinessConflictException invalid(String message) {
        return new BusinessConflictException("MEDIA_CONTENT_INVALID", message);
    }

    public record Analysis(
            String detectedContentType,
            int width,
            int height,
            String sha256,
            String perceptualHash,
            Map<String, Object> extractedMetadata) {}

    private record Dimensions(int width, int height) {}
}
