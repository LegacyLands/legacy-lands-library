package net.legacy.library.configuration.util;

import lombok.Cleanup;
import lombok.experimental.UtilityClass;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * IOUtil is a utility class that provides common file and directory operations.
 *
 * @author qwq-dev
 * @since 2025-1-25 13:15
 */
@UtilityClass
public class IOUtil {

    /**
     * Retrieves all files (not directories) within the specified directory (including subdirectories).
     *
     * @param directoryPath the path to the directory you want to scan
     * @return a list of Files found under the specified directory
     * @throws IOException if an I/O error is thrown when accessing the file system
     */
    public static List<File> getAllFiles(String directoryPath) throws IOException {
        List<File> fileList = new ArrayList<>();
        Path path = Paths.get(directoryPath);

        if (!Files.exists(path) || !Files.isDirectory(path)) {
            return fileList;
        }

        @Cleanup
        Stream<Path> walk = Files.walk(path);
        walk.filter(Files::isRegularFile).forEach(path1 -> fileList.add(path1.toFile()));

        return fileList;
    }

    /**
     * Reads the entire content of a file and returns it as a String.
     *
     * @param filePath the path to the file to be read
     * @return the content of the file as a String
     * @throws IOException if an I/O error occurs opening or reading the file
     */
    public static String readFileAsString(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Reads all lines from a file into a List of Strings, where each element represents a single line.
     *
     * @param filePath the path to the file to be read
     * @return a List of lines from the file
     * @throws IOException if an I/O error occurs opening or reading the file
     */
    public static List<String> readAllLines(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    /**
     * Writes the given text content to the specified file, overwriting any existing content.
     *
     * @param filePath the path to the file
     * @param content  the text content to be written
     * @throws IOException if an I/O error occurs writing the file
     */
    public static void writeStringToFile(String filePath, String content) throws IOException {
        Path path = Paths.get(filePath);
        // Ensure parent directory exists
        createDirectories(path.getParent().toString());

        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(content);
        }
    }

    /**
     * Appends the given text content to the specified file, creating the file if it does not exist.
     *
     * @param filePath the path to the file
     * @param content  the text content to be appended
     * @throws IOException if an I/O error occurs writing the file
     */
    public static void appendStringToFile(String filePath, String content) throws IOException {
        Path path = Paths.get(filePath);
        // Ensure parent directory exists
        createDirectories(path.getParent().toString());

        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(content);
        }
    }

    /**
     * Copies a file from the source path to the destination path.
     *
     * @param sourcePath the existing file to copy
     * @param destPath   the target file
     * @throws IOException if an I/O error occurs when copying
     */
    public static void copyFile(String sourcePath, String destPath) throws IOException {
        Path source = Paths.get(sourcePath);
        Path destination = Paths.get(destPath);
        // Ensure parent directory exists
        createDirectories(destination.getParent().toString());

        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Moves (or renames) a file from the source path to the destination path.
     *
     * @param sourcePath the existing file to move
     * @param destPath   the target file
     * @throws IOException if an I/O error occurs when moving
     */
    public static void moveFile(String sourcePath, String destPath) throws IOException {
        Path source = Paths.get(sourcePath);
        Path destination = Paths.get(destPath);
        // Ensure parent directory exists
        createDirectories(destination.getParent().toString());

        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Deletes a file or an empty directory at the specified path.
     *
     * @param filePath the path to the file or directory
     * @throws IOException if an I/O error occurs deleting the file
     */
    public static void deleteFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        Files.deleteIfExists(path);
    }

    /**
     * Creates all directories along the specified path if they do not exist already.
     *
     * @param directoryPath the path of directories to create
     * @throws IOException if an I/O error occurs creating directories
     */
    public static void createDirectories(String directoryPath) throws IOException {
        if (directoryPath == null) {
            return;
        }
        Path path = Paths.get(directoryPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    /**
     * Determines if the specified path is a directory.
     *
     * @param path the path to check
     * @return true if the path exists and is a directory, false otherwise
     */
    public static boolean isDirectory(String path) {
        return Files.isDirectory(Paths.get(path));
    }

    /**
     * Returns the size of a file in bytes.
     *
     * @param filePath the path to the file
     * @return the size of the file in bytes, or -1 if the file does not exist
     */
    public static long getFileSize(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            return -1;
        }
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return -1;
        }
    }

    /**
     * Retrieves the file name from a given file path.
     *
     * @param filePath the full path of the file
     * @return the file name (with extension) or an empty string if invalid
     */
    public static String getFileName(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }
        Path path = Paths.get(filePath);
        Path fileName = path.getFileName();
        return (fileName != null) ? fileName.toString() : "";
    }

    /**
     * Retrieves the file extension from a given file path (e.g., "txt" for "file.txt").
     *
     * @param filePath the full path of the file
     * @return the file extension without the dot, or an empty string if none found
     */
    public static String getFileExtension(String filePath) {
        String fileName = getFileName(filePath);
        int lastDotIndex = fileName.lastIndexOf('.');

        return (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) ?
                fileName.substring(lastDotIndex + 1) : "";
    }
}