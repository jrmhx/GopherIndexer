
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Paths;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class GopherIndexer {
    private final String hostname;
    private final int port;
    private final Set<String> visited;
    private final List<String> textFiles;
    private final List<String> binaryFiles;
    private final List<String> badTextFiles;
    private final List<String> badBinaryFiles;
    private long largestTextFileSize = 0;
    private String smallestTextFileContents = null;
    private long smallestTextFileSize = Long.MAX_VALUE;
    private long smallestBinaryFileSize = Long.MAX_VALUE;
    private long largestBinaryFileSize = 0;
    private final List<String> externalServersUp;
    private final List<String> externalServersDown;
    private final List<String> uniqueInvalidReferences;

    private static final int MAX_FILENAME_LENGTH = 63;
    private static final String DOWNLOAD_DIRECTORY = "downloaded_files/";

    private final ConnectionHandler connectionHandler = new ConnectionHandler();

    public GopherIndexer(String hostname,int port) {
        this.hostname = hostname;
        this.port = port;
        this.visited = new HashSet<>();
        this.textFiles = new ArrayList<>();
        this.binaryFiles = new ArrayList<>();
        this.badBinaryFiles = new ArrayList<>();
        this.badTextFiles = new ArrayList<>();
        this.externalServersUp = new ArrayList<>();
        this.externalServersDown = new ArrayList<>();
        this.uniqueInvalidReferences = new ArrayList<>();
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
            throw new RuntimeException("Failed to hash path due to missing algorithm.");
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
            Logger.severe("Failed to write file: " + filePath);
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
            Logger.severe("Failed to write file: " + filePath);
            return 0; // In case of error, return 0
        }
    }


    public String fetchFromGopher(String hostname, int port, String selector) {
        try {
            connectionHandler.connect(hostname, port);
            return connectionHandler.sendRequest(selector);
        } catch(SocketTimeoutException e){
            Logger.severe("Timeout occurred while connecting to or reading from " + hostname + ":" + port + " [" + e.getMessage() + "]");
            return null;
        }catch (IOException e) {
            Logger.severe("Failed to connect to " + hostname + ":" + port + " [" + e.getMessage() + "]");
            return null;
        } finally {
            try {
                connectionHandler.disconnect();
            } catch (IOException e) {
                Logger.severe("Failed to disconnect from " + hostname + ":" + port + " [" + e.getMessage() + "]");
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
            Logger.severe("Timeout occurred while connecting to or reading from " + hostname + ":" + port + " [" + e.getMessage() + "]");
            return null;
        } catch (IOException e) {
            Logger.severe("Failed to connect to " + hostname + ":" + port + " [" + e.getMessage() + "]");
            return null;
        } finally {
            try {
                connectionHandler.disconnect();
            } catch (IOException e) {
                Logger.severe("Failed to disconnect from " + hostname + ":" + port + " [" + e.getMessage() + "]");
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
                Logger.severe("Failed to disconnect from " + hostname + ":" + port + " [" + e.getMessage() + "]");
            }
        }
    }

    public void recursiveFetch(String hostname, int port, String selector, String path) throws IOException {
        String resourceKey = hostname + ":" + port + selector;
        if (visited.contains(resourceKey)) {
            return;
        }
        visited.add(resourceKey);

        System.out.println("Fetching: " + new Date() + " - " + hostname + ":" + port + selector);

        String data = fetchFromGopher(hostname, port, selector);
        if (data == null || data.isEmpty()) {
            Logger.warning("Empty or null response received for selector: " + selector);
            return; // Skip processing this resource
        }

        String[] lines = data.split("\n");
        for (String line : lines) {
            if (!line.contains("\t")) continue;
            String[] parts = line.split("\t");
            if (parts.length < 4) {  // Ensure there are enough parts to process without error
                Logger.warning("Incorrectly formatted data line: " + line);
                continue;
            }
            String type = parts[0].substring(0, 1);
            String newSelector = parts[1];
            String newHostname = parts[2];
            int newPort;
            try {
                newPort = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                Logger.severe("Failed to parse port number: " + parts[3]);
                continue; // Skip this entry
            }

            String fullPath = path + newSelector; // Sanitize newSelector to be safe as a file path component

            switch (type) {
                case "i" -> {
                    // do nothing
                }
                case "1" -> { // folder
                    if (newHostname.equals(this.hostname) && newPort == this.port) {
                        recursiveFetch(newHostname, newPort, newSelector, fullPath);
                    } else {
                        if (isServerUp(newHostname, newPort)) {
                            externalServersUp.add(newHostname + ":" + newPort);
                        } else {
                            externalServersDown.add(newHostname + ":" + newPort);
                        }
                    }
                }
                case "0" -> { // .txt file
                    System.out.println("Fetching: " + new Date() + " - " + newHostname + ":" + newPort + newSelector);
                    String downloadData = fetchFromGopher(newHostname, newPort, newSelector);
                    if (downloadData == null || downloadData.isEmpty()) {
                        Logger.warning("Empty or null response received for selector: " + selector);
                        this.badTextFiles.add(fullPath);
                    }

                    if( downloadData != null){
                        // Remove the trailing period if it exists
                        if (downloadData.endsWith(".\n")) {
                            downloadData = downloadData.substring(0, downloadData.length() - 2);
                        } else if (downloadData.endsWith(".")) {
                            downloadData = downloadData.substring(0, downloadData.length() - 1);
                        }
                        long size = downloadFile(downloadData, fullPath);
                        if (size < smallestTextFileSize) {
                            smallestTextFileSize = size;
                            smallestTextFileContents = downloadData;
                        }
                        if (size > largestTextFileSize) {
                            largestTextFileSize = size;
                        }
                        this.textFiles.add(fullPath);
                        Logger.info("File downloaded and saved: " + fullPath + " (Size: " + size + " bytes)");
                    }
                }
                case "3" -> { // unique invalid references
                    this.uniqueInvalidReferences.add(fullPath);
                }
                case "9" -> { // binary file
                    System.out.println("Fetching: " + new Date() + " - " + newHostname + ":" + newPort + newSelector);
                    byte[] downloadData = fetchBinaryFromGopher(newHostname, newPort, newSelector);
                    if (downloadData == null ) {
                        Logger.warning("Empty or null response received for selector: " + selector);
                        this.badBinaryFiles.add(fullPath);
                    } else {
                        long size = downloadFile(downloadData, fullPath);
                        if (size < smallestBinaryFileSize) {
                            smallestBinaryFileSize = size;
                        }
                        if (size > largestBinaryFileSize) {
                            largestBinaryFileSize = size;
                        }
                        this.binaryFiles.add(fullPath);
                        Logger.info("File downloaded successfully: " + fullPath + " (Size: " + size + " bytes)");
                    }
                }
            }
        }
    }

    public void printStatistics() {
        System.out.println("Total directories visited: " + visited.size());
        System.out.println();
        System.out.println("---------------------------------------------------");
        System.out.println("Total text files (fetched successfully): " + textFiles.size());
        System.out.println();
        if (!textFiles.isEmpty()) {
            System.out.println("List of all text files:");
            textFiles.forEach(System.out::println);
        }
        System.out.println("---------------------------------------------------");
        System.out.println("Total bad text files (empty or null response): " + badTextFiles.size());
        System.out.println();
        if (!badTextFiles.isEmpty()) {
            System.out.println("List of all bad text files:");
            badTextFiles.forEach(System.out::println);
        }
        System.out.println("---------------------------------------------------");
        System.out.println("Total bad binary files (empty or null response): " + badBinaryFiles.size());
        System.out.println();
        if (!badBinaryFiles.isEmpty()) {
            System.out.println("List of all bad binary files:");
            badBinaryFiles.forEach(System.out::println);
        }
        System.out.println("---------------------------------------------------");
        System.out.println("Total binary files (fetched successfully): " + binaryFiles.size());
        System.out.println();
        if (!binaryFiles.isEmpty()) {
            System.out.println("List of all binary files:");
            binaryFiles.forEach(System.out::println);
        }
        System.out.println("---------------------------------------------------");
        System.out.println();
        System.out.println("Smallest text file content: " + smallestTextFileContents);
        System.out.println("Largest text file size: " + largestTextFileSize + " bytes");
        System.out.println("Smallest binary file size: " + smallestBinaryFileSize + " bytes");
        System.out.println("Largest binary file size: " + largestBinaryFileSize + " bytes");
        System.out.println();
        System.out.println("---------------------------------------------------");
        System.out.println("Total external servers: " + (externalServersUp.size() + externalServersDown.size()));
        System.out.println();
        if(!externalServersUp.isEmpty()){
            System.out.println("List of all external servers that are up:");
            externalServersUp.forEach(System.out::println);
        }
        System.out.println();
        if(!externalServersDown.isEmpty()){
            System.out.println("List of all external servers that are down:");
            externalServersDown.forEach(System.out::println);
        }
        System.out.println("---------------------------------------------------");
        System.out.println("Total unique invalid references: " + uniqueInvalidReferences.size());
        System.out.println();
        if (!uniqueInvalidReferences.isEmpty()) {
            System.out.println("List of all unique invalid references:");
            uniqueInvalidReferences.forEach(System.out::println);
        }
    }

    public static void main(String[] args) {
        String hostname = "comp3310.ddns.net";
        int port = 70;

        GopherIndexer indexer = new GopherIndexer(hostname, port);

        try {
            indexer.recursiveFetch(hostname, port, "", "");
            System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
            System.out.println();
            System.out.println("\u001B[32m Finish Indexing! \u001B[0m");
            indexer.printStatistics();
            System.out.println();
            System.out.println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
        } catch (IOException e) {
            Logger.severe("Exception occurred" + " [" + e.getMessage() + "]");
        }
    }
}