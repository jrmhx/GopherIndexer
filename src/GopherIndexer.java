
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class GopherIndexer {
    private final String hostname;
    private final Set<String> visited;
    private final List<String> textFiles;
    private final List<String> binaryFiles;
    private long largestTextFileSize = 0;
    private String smallestTextFileContents = null;
    private long smallestTextFileSize = Long.MAX_VALUE;
    private long smallestBinaryFileSize = Long.MAX_VALUE;
    private long largestBinaryFileSize = 0;
    private long numbExternaliseUp = 0;
    private long numExternalServerDown = 0;
    private long numBadFiles = 0;

    private static final int MAX_FILENAME_LENGTH = 63;
    private static final String DOWNLOAD_DIRECTORY = "downloaded_files/";

    private final ConnectionHandler connectionHandler = new ConnectionHandler();
    private static final Logger logger = Logger.getLogger(GopherIndexer.class.getName());
    static {
        LoggingSetup.configureLogger(logger);
    }

    public GopherIndexer(String hostname) {
        this.hostname = hostname;
        this.visited = new HashSet<>();
        this.textFiles = new ArrayList<>();
        this.binaryFiles = new ArrayList<>();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String generateSafeFilePath(String fullPath) {
        try {
            // Sanitize the string to remove unwanted characters
            String safePath = fullPath.replaceAll("[^a-zA-Z0-9.-]", "_");

            // Truncate if necessary to prevent excessively long filenames
            if (safePath.length() > MAX_FILENAME_LENGTH) {
                String extension = ""; // Assuming an extension could be in the path, like .txt or .bin
                int dotIndex = safePath.lastIndexOf('.');
                if (dotIndex > 0) {
                    extension = safePath.substring(dotIndex);
                    safePath = safePath.substring(0, dotIndex);
                }

                // Hash the original path to append as a unique identifier to prevent collisions
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hashBytes = digest.digest(fullPath.getBytes());
                String hash = bytesToHex(hashBytes).substring(0, 8); // Short hash to keep overall length under control

                // Append hash and ensure total length including extension doesn't exceed max
                safePath = safePath.substring(0, Math.min(safePath.length(), MAX_FILENAME_LENGTH - hash.length() - extension.length())) + hash + extension;
            }

            return Paths.get(DOWNLOAD_DIRECTORY, safePath).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash path due to missing algorithm.", e);
        }
    }

    private long downloadFile(String data, String filePath) {
        try {
            // Ensure directory path exists before writing the file
            String safeFilePath = generateSafeFilePath(filePath);
            Path path = Paths.get(safeFilePath);
            Files.createDirectories(path.getParent());

            // Write data to file
            Files.write(path, data.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return Files.size(path); // Return the size of the file
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to write file: " + filePath, e);
            return 0; // In case of error, return 0
        }
    }

    private long downloadFile(byte[] data, String filePath) {
        try {
            // Ensure directory path exists before writing the file
            String safeFilePath = generateSafeFilePath(filePath);
            Path path = Paths.get(safeFilePath);
            Files.createDirectories(path.getParent());

            // Write data to file
            Files.write(path, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return Files.size(path); // Return the size of the file
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to write file: " + filePath, e);
            return 0; // In case of error, return 0
        }
    }


    public String fetchFromGopher(String hostname, int port, String selector) {
        try {
            connectionHandler.connect(hostname, port);
            return connectionHandler.sendRequest(selector);
        } catch(SocketTimeoutException e){
            logger.log(Level.WARNING, "Timeout occurred while connecting to or reading from " + hostname + ":" + port, e);
            this.numBadFiles++;
            return null;
        }catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to connect to " + hostname + ":" + port, e);
            return null;
        } finally {
            try {
                connectionHandler.disconnect();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to disconnect from " + hostname + ":" + port, e);
            }
        }
    }

    public byte[] fetchBinaryFromGopher(String hostname, int port, String selector) {
        try {
            connectionHandler.connect(hostname, port);
            connectionHandler.getOutputStream().print(selector + "\r\n");
            connectionHandler.getOutputStream().flush();

            // Prepare to read the binary data from the stream
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[1024];
            int nRead;
            while ((nRead = connectionHandler.getRawInputStream().read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            return buffer.toByteArray();
        } catch(SocketTimeoutException e){
            logger.log(Level.WARNING, "Timeout occurred while connecting to or reading from " + hostname + ":" + port, e);
            this.numBadFiles++;
            return null;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to connect to " + hostname + ":" + port, e);
            return null;
        } finally {
            try {
                connectionHandler.disconnect();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to disconnect from " + hostname + ":" + port, e);
            }
        }
    }


    public boolean isServerUp(String hostname, int port) {
        try {
            connectionHandler.connect(hostname, port);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                connectionHandler.disconnect();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to disconnect from " + hostname + ":" + port, e);
            }
        }
    }

    public void recursiveFetch(String hostname, int port, String selector, String path) throws IOException {
        String resourceKey = hostname + ":" + port + selector;
        if (visited.contains(resourceKey)) {
            return;
        }
        visited.add(resourceKey);

        String data = fetchFromGopher(hostname, port, selector);
        if (data == null || data.isEmpty()) {
            logger.log(Level.WARNING, "Empty or null response received for selector: " + selector);
            return; // Skip processing this resource
        }
        System.out.println("Fetching: " + new Date() + " - " + selector);

        String[] lines = data.split("\n");
        for (String line : lines) {
            if (!line.contains("\t")) continue;
            String[] parts = line.split("\t");
            if (parts.length < 4) {  // Ensure there are enough parts to process without error
                logger.log(Level.WARNING, "Incorrectly formatted line: " + line);
                continue;
            }
            String type = parts[0].substring(0, 1);
            String newSelector = parts[1];
            String newHostname = parts[2];
            int newPort;
            try {
                newPort = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                logger.log(Level.SEVERE, "Failed to parse port number: " + parts[3], e);
                continue; // Skip this entry
            }
            // Change here to use newSelector instead of displayString for fullPath
            String fullPath = path + newSelector; // Sanitize newSelector to be safe as a file path component

            switch (type) {
                case "i" -> {
                    continue; // Skip informational lines
                }
                case "1" -> {
                    if (newHostname.equals(this.hostname)) {
                        recursiveFetch(newHostname, newPort, newSelector, fullPath);
                    } else {
                        if (isServerUp(newHostname, newPort)) {
                            numbExternaliseUp++;
                        } else {
                            numExternalServerDown++;
                        }
                    }
                }
                case "0" -> {
                    textFiles.add(fullPath);
                    String downloadData = fetchFromGopher(newHostname, newPort, newSelector);
                    if (downloadData == null || downloadData.isEmpty()) {
                        logger.log(Level.WARNING, "Empty or null response received for selector: " + selector);
                    }

                    if( downloadData != null){
                        // Remove the trailing period if it exists
                        if (downloadData.endsWith(".\n")) {
                            downloadData = downloadData.substring(0, downloadData.length() - 2);
                        } else if (downloadData.endsWith(".")) {
                            downloadData = downloadData.substring(0, downloadData.length() - 1);
                        }
                        long size = downloadFile(downloadData, fullPath);
                        if (size > 0){
                            if (size < smallestTextFileSize) {
                                smallestTextFileSize = size;
                                smallestTextFileContents = downloadData;
                            }
                            if (size > largestTextFileSize) {
                                largestTextFileSize = size;
                            }
                            logger.log(Level.INFO, "File downloaded and saved: " + fullPath + " (Size: " + size + " bytes)");
                        }
                    }
                }
                case "9" -> { // binary files
                    binaryFiles.add(fullPath);
                    byte[] downloadData = fetchBinaryFromGopher(newHostname, newPort, newSelector);
                    if (downloadData == null ) {
                        logger.log(Level.WARNING, "Empty or null response received for selector: " + selector);
                    } else {
                        long size = downloadFile(downloadData, fullPath);
                        if (size > 0){
                            if (size < smallestBinaryFileSize) {
                                smallestBinaryFileSize = size;
                            }
                            if (size > largestBinaryFileSize) {
                                largestBinaryFileSize = size;
                            }
                            logger.log(Level.INFO, "File downloaded and saved: " + fullPath + " (Size: " + size + " bytes)");
                        }
                    }
                }
            }
        }
    }

    public void printStatistics() {
        System.out.println("Total directories visited: " + visited.size());
        System.out.println("Total text files: " + textFiles.size());
        System.out.println("List of all text files:");
        textFiles.forEach(System.out::println);
        System.out.println("Total binary files: " + binaryFiles.size());
        System.out.println("List of all binary files:");
        binaryFiles.forEach(System.out::println);
        System.out.println("Smallest text file content: " + smallestTextFileContents);
        System.out.println("Largest text file size: " + largestTextFileSize);
        System.out.println("Smallest binary file size: " + smallestBinaryFileSize);
        System.out.println("Largest binary file size: " + largestBinaryFileSize);
        System.out.println("Number of external servers up: " + numbExternaliseUp);
        System.out.println("Number of external servers down: " + numExternalServerDown);
        System.out.println("Number of bad files: " + numBadFiles);
    }

    public static void main(String[] args) {
        setupLogger();
        String hostname = "comp3310.ddns.net";
        int port = 70;

        GopherIndexer indexer = new GopherIndexer(hostname);

        try {
            indexer.recursiveFetch(hostname, port, "", "");
            indexer.printStatistics();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Exception occurred", e);
        }
    }

    private static void setupLogger() {
        try {
            FileHandler fh = new FileHandler("GopherIndexer.log", true);
            logger.addHandler(fh);
            fh.setFormatter(new SimpleFormatter());
        } catch (java.io.IOException e) {
            logger.log(Level.SEVERE, "File logger not working.", e);
        }
    }
}