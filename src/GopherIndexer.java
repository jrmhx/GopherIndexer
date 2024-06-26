
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

/**
 * The GopherIndexer class is designed to recursively fetch and index files from Gopher servers.
 * It keeps track of visited directories, downloads text and binary files, and logs various statistics
 * such as the smallest and largest files and server connectivity status. The class uses a combination
 * of lists to manage files and server states, and employs a custom connection handler to manage network operations.
 */

public class GopherIndexer {
    private final int MAX_DEPTH;
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
    private final Map<String, Integer> errorCount;

    private static final int MAX_FILENAME_LENGTH = 63;
    private static final String DOWNLOAD_DIRECTORY = "downloaded_files/";

    private final ConnectionHandler connectionHandler = new ConnectionHandler();

    /**
     * Constructor initializes the GopherIndexer with a specified hostname and port.
     * It sets up lists and sets to track various states and files during operation.
     * @param hostname The hostname of the Gopher server to connect to.
     * @param port The port of the Gopher server.
     */

    public GopherIndexer(String hostname,int port, int maxDepth){
        this.MAX_DEPTH = maxDepth;
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
        this.errorCount = new HashMap<>();
    }


    /**
     * Logs the error message and the number of times it occurred.
     * @param errorMsg The error message to log.
     */
    public void logErrorCount(String errorMsg){
        if (errorCount.containsKey(errorMsg)) {
            errorCount.put(errorMsg, errorCount.get(errorMsg) + 1);
        } else {
            errorCount.put(errorMsg, 1);
        }
    }

    /**
     * Converts an array of bytes into a hexadecimal String.
     * @param bytes The byte array to convert.
     * @return A String representing the hexadecimal value of the byte array.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Generates a filesystem-safe path from a given full path, ensuring it adheres to length restrictions and avoids character collisions.
     * @param fullPath The full path to be sanitized and truncated.
     * @return A String representing the safe file path.
     * @throws RuntimeException If no suitable hashing algorithm is found.
     */
    private String generateSafeFilePath(String fullPath) {
        try {
            // Sanitize the string to remove unwanted characters
            String safePath = fullPath.replaceAll("[^a-zA-Z0-9.-]", "_");

            // Attempt to find the extension, if any
            String extension = "";
            int dotIndex = safePath.lastIndexOf('.');
            if (dotIndex > 0) {
                extension = safePath.substring(dotIndex);  // Capture the extension
                safePath = safePath.substring(0, dotIndex); // Remove extension from the main path
            }

            // Hash the sanitized path without the extension to generate a unique suffix
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(fullPath.getBytes());
            String hash = bytesToHex(hashBytes).substring(0, Math.min(8, bytesToHex(hashBytes).length()));  // Ensure the substring index is valid

            // Construct the final path with hash and extension
            safePath += hash + extension;
            safePath = safePath.length() > MAX_FILENAME_LENGTH ? safePath.substring(0, MAX_FILENAME_LENGTH) : safePath;

            // Create the full file path and check for uniqueness
            Path path = Paths.get(DOWNLOAD_DIRECTORY, safePath);
            int counter = 1;
            while (Files.exists(path)) {
                safePath = safePath + "_" + counter++;
                path = Paths.get(DOWNLOAD_DIRECTORY, safePath);
            }

            return path.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash path due to missing algorithm.");
        }
    }



    /**
     * Downloads data to a file at the specified file path, ensuring directories exist and handling file creation.
     * @param data The string data to be written to the file.
     * @param filePath The path where the file will be written.
     * @return The size of the file written or 0 if an error occurs.
     */
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
            // add to error count
            logErrorCount(e.getMessage());
            return 0; // In case of error, return 0
        }
    }

/**
     * Downloads binary data to a file at the specified file path, ensuring directories exist and handling file creation.
     * @param data The byte array data to be written to the file.
     * @param filePath The path where the file will be written.
     * @return The size of the file written or 0 if an error occurs.
     */
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
            logErrorCount(e.getMessage());
            return 0; // In case of error, return 0
        }
    }

    /**
     * Fetches data from a Gopher server at the specified hostname and port using the provided selector.
     * @param hostname The hostname of the Gopher server.
     * @param port The port of the Gopher server.
     * @param selector The selector to fetch data from the server.
     * @return The data fetched from the server or null if an error occurs.
     */
    public String fetchFromGopher(String hostname, int port, String selector) {
        try {
            connectionHandler.connect(hostname, port);
            return connectionHandler.sendRequest(selector);
        } catch(SocketTimeoutException e){
            Logger.severe("Timeout occurred while connecting to or reading from " + hostname + ":" + port + " [" + e.getMessage() + "]");
            logErrorCount(e.getMessage());
            return null;
        }catch (IOException e) {
            Logger.severe("Failed to connect to " + hostname + ":" + port + " [" + e.getMessage() + "]");
            logErrorCount(e.getMessage());
            return null;
        } finally {
            try {
                connectionHandler.disconnect();
            } catch (IOException e) {
                Logger.severe("Failed to disconnect from " + hostname + ":" + port + " [" + e.getMessage() + "]");
                logErrorCount(e.getMessage());
            }
        }
    }

    /**
     * Fetches binary data from a Gopher server at the specified hostname and port using the provided selector.
     * @param hostname The hostname of the Gopher server.
     * @param port The port of the Gopher server.
     * @param selector The selector to fetch binary data from the server.
     * @return The binary data fetched from the server or null if an error occurs.
     */
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
            logErrorCount(e.getMessage());
            return null;
        } catch (IOException e) {
            Logger.severe("Failed to connect to " + hostname + ":" + port + " [" + e.getMessage() + "]");
            logErrorCount(e.getMessage());
            return null;
        } finally {
            try {
                connectionHandler.disconnect();
            } catch (IOException e) {
                Logger.severe("Failed to disconnect from " + hostname + ":" + port + " [" + e.getMessage() + "]");
                logErrorCount(e.getMessage());
            }
        }
    }


    /**
     * Checks if a server is up by attempting to connect to it.
     * @param hostname The hostname of the server to check.
     * @param port The port of the server to check.
     * @return True if the server is up, false otherwise.
     */
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
                logErrorCount(e.getMessage());
            }
        }
    }

    /**
     * Recursively fetches data from a Gopher server at the specified hostname and port using the provided selector.
     * It processes the data to download text and binary files, and logs various statistics.
     * @param hostname The hostname of the Gopher server.
     * @param port The port of the Gopher server.
     * @param selector The selector to fetch data from the server.
     * @param path The path of the current resource being fetched.
     * @throws IOException If an error occurs during the fetch operation.
     */
    public void recursiveFetch(String hostname, int port, String selector, String path, int currentDepth) throws IOException {

        if (currentDepth >= MAX_DEPTH) {
            Logger.warning("Maximum depth reached, stopping recursion at depth: " + currentDepth);
            return; // Stop recursion if the maximum depth is reached
        }

        String resourceKey = hostname + ":" + port + selector;
        if (visited.contains(resourceKey)) {
            return;
        }
        visited.add(resourceKey);

        System.out.println("Fetching: " + new Date() + " - " + hostname + ":" + port + selector);

        String data = fetchFromGopher(hostname, port, selector);
        if (data == null || data.isEmpty()) {
            Logger.warning("Empty or null response received for selector: " + selector);
            logErrorCount("Empty or null response received for selector");
            return; // Skip processing this resource
        }

        String[] lines = data.split("\n");
        for (String line : lines) {
            if (!line.contains("\t")) continue;
            String[] parts = line.split("\t");
            if (parts.length < 4) {  // Ensure there are enough parts to process without error
                Logger.warning("Incorrectly formatted data line: " + line);
                logErrorCount("Incorrectly formatted data line");
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
                logErrorCount(e.getMessage());
                continue; // Skip this entry
            }

            String fullPath = path + newSelector; // Sanitize newSelector to be safe as a file path component

            switch (type) {
                case "1" -> { // directories
                    if (newHostname.equals(this.hostname) && newPort == this.port) {
                        recursiveFetch(newHostname, newPort, newSelector, fullPath, currentDepth + 1);
                    } else {
                        String resourceKeyExternal = newHostname + ":" + newPort + newSelector;
                        if (! visited.contains(resourceKeyExternal)) {
                            visited.add(resourceKeyExternal); // Mark external server as visited to prevent re-fetching
                            if (isServerUp(newHostname, newPort)) {
                                externalServersUp.add(newHostname + ":" + newPort);
                            } else {
                                externalServersDown.add(newHostname + ":" + newPort);
                            }
                        }
                    }
                }
                case "0" -> { // .txt file
                    System.out.println("Fetching: " + new Date() + " - " + newHostname + ":" + newPort + newSelector);
                    String downloadData = fetchFromGopher(newHostname, newPort, newSelector);
                    if (downloadData == null || downloadData.isEmpty()) {
                        Logger.warning("Empty or null response received for selector: " + selector);
                        logErrorCount("Empty or null response received for selector");
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
                        logErrorCount("Empty or null response received for selector");
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

    /**
     * Prints statistics about the indexing operation, including the number of directories visited,
     * the number of text and binary files fetched successfully, the number of bad text and binary files,
     * the smallest and largest text and binary file sizes, the smallest text file content, the list of external servers
     * that are up and down, the total number of unique invalid references, and the list of unique invalid references.
     */
    public void printStatistics() {
        System.out.println("Total directories on the server: " + visited.size());
        System.out.println("\u001B[33mNOTE\u001B[0m: This includes data fetched from the server that has a data type of '1' (directories) with a valid data format");
        System.out.println("\u001B[33mNOTE\u001B[0m: It doesn't include dictionaries that can be fetched from the external server");
        System.out.println("\u001B[33mNOTE\u001B[0m: The directories included are not guranteed to be reachable (maybe due to the server being down)");
        System.out.println("\u001B[33mNOTE\u001B[0m: It includes the server's root directory as well");
        if (!visited.isEmpty()) {
            System.out.println("List of all directories on the server:");
            visited.forEach(System.out::println);
        }
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
        System.out.println("---------------------------------------------------");
        if (!errorCount.isEmpty()) {
            System.out.println("List of error handle/unsuccessful fetching while run the indexer:");
            errorCount.forEach((key, value) -> System.out.println(key + " : " + value));
        }
    }

    /**
     * Main method to run the GopherIndexer and fetch data from a Gopher server.
     * @param args The command line arguments.
     */
    public static void main(String[] args) {
        String hostname = "comp3310.ddns.net"; // Default hostname
        int port = 70; // Default port
        int maxDepth = 1024; // Default maximum depth

        if (args.length >= 2) {
            hostname = args[0];
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                Logger.severe("Invalid port number: " + args[1]);
                System.exit(1);
            }
        }

        if (args.length >= 3) {
            try {
                maxDepth = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                Logger.severe("Invalid depth limit: " + args[2]);
                System.exit(1);
            }
        }

        System.out.printf("\u001B[32mIndexing Gopher server: %s:%d with a depth limit of %d%n\u001B[0m", hostname, port, maxDepth);

        GopherIndexer indexer = new GopherIndexer(hostname, port, maxDepth);

        try {
            indexer.recursiveFetch(hostname, port, "", "", 0);
            System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
            System.out.println();
            System.out.println("\u001B[32m Finish Indexing! \u001B[0m");
            System.out.println();
            indexer.printStatistics();
            System.out.println();
            System.out.println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
        } catch (IOException e) {
            Logger.severe("Exception occurred" + " [" + e.getMessage() + "]");
        }
    }
}