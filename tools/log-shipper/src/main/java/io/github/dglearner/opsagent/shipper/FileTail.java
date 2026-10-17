package io.github.dglearner.opsagent.shipper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * The reading position in one log file. Only complete lines are read: a line still being written is left for the
 * next read, so a batch never ends in half a line.
 *
 * <p>A file is recognised by its identity (the inode on Linux and macOS; Windows has none, so its creation time),
 * so a rotated file, renamed away and replaced by a new one, is read again from the start. A file shorter than the
 * position read so far was truncated and is read from the start too. Each such restart counts a generation: a file
 * truncated and written again keeps its identity, and the generation tells its new lines from the old ones.
 */
final class FileTail {

    /** Ingest accepts lines of at most 16 KB; longer ones are cut there and marked. */
    static final int MAX_LINE_BYTES = 16 * 1024 - 32;
    static final String TRUNCATED = " [truncated]";

    /** A complete line and the position just after it. */
    record Line(String text, long end) {
    }

    final Path path;
    String identity;
    long generation;
    long offset;

    FileTail(Path path, String identity, long generation, long offset) {
        this.path = path;
        this.identity = identity;
        this.generation = generation;
        this.offset = offset;
    }

    static String identity(BasicFileAttributes attributes) {
        Object key = attributes.fileKey();
        return key != null ? key.toString() : "created:" + attributes.creationTime().toMillis();
    }

    /**
     * Notices rotation and truncation; true if the file was replaced or shortened since the last read, in which case
     * reading starts again from its beginning.
     */
    boolean checkReplaced(BasicFileAttributes attributes) {
        String current = identity(attributes);
        if (current.equals(identity) && attributes.size() >= offset) {
            return false;
        }
        identity = current;
        generation++;
        offset = 0;
        return true;
    }

    /** Up to {@code maxLines} complete lines from the current position, at most about {@code maxBytes} of them. */
    List<Line> read(int maxLines, long maxBytes) throws IOException {
        List<Line> lines = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(offset);
            InputStream in = new BufferedInputStream(Channels.newInputStream(channel), 64 * 1024);
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean truncated = false;
            long position = offset;
            long consumed = 0;
            int b;
            while (lines.size() < maxLines && consumed < maxBytes && (b = in.read()) != -1) {
                position++;
                consumed++;
                if (b == '\n') {
                    String text = line.toString(StandardCharsets.UTF_8);
                    if (text.endsWith("\r")) {
                        text = text.substring(0, text.length() - 1);
                    }
                    if (!text.isBlank()) {
                        lines.add(new Line(truncated ? text + TRUNCATED : text, position));
                    } else if (!lines.isEmpty()) {
                        lines.set(lines.size() - 1, new Line(lines.getLast().text(), position));
                    }
                    line.reset();
                    truncated = false;
                } else if (line.size() < MAX_LINE_BYTES) {
                    line.write(b);
                } else {
                    truncated = true;
                }
            }
        }
        return lines;
    }

    static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class);
    }
}
