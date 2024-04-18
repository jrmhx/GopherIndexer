import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;

public class GopherIndexer {
    private final String hostname;
    private final int port;
    private final Set<String> visited;
    private final List<String> textFiles;
    private final List<String> binaryFiles;
    private long largestTextFileSize = 0;
    private String smallestTextFileContents = null;
    private long smallestTextFileSize = Long.MAX_VALUE;
    private long smallestBinaryFileSize = Long.MAX_VALUE;
    private long largestBinaryFileSize = 0;
    private static final Logger logger = Logger.getLogger(GopherIndexer.class.getName());
    private final ConnectionHandler connectionHandler = new ConnectionHandler();

    public GopherIndexer(String hostname, int port) {
        this.hostname = hostname;
        this.port = port;
        this.visited = new HashSet<>();
        this.textFiles = new ArrayList<>();
        this.binaryFiles = new ArrayList<>();
    }

    public String fetchFromGopher(String hostname, int port, String selector) {
        try {
            connectionHandler.connect(hostname, port);
            return connectionHandler.sendRequest(selector);
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

    public void recursiveFetch(String selector, String path) throws IOException {
        String resourceKey = hostname + ":" + port + selector;
        if (visited.contains(resourceKey)) {
            return;
        }
        visited.add(resourceKey);

        String data = fetchFromGopher(hostname, port, selector);
        System.out.println("Fetching: " + new Date() + " - " + selector);

        String[] lines = data.split("\n");
        for (String line : lines) {
            if (line.isEmpty() || !line.contains("\t")) continue;
            String[] parts = line.split("\t");
            if (parts.length > 3) {
                String type = parts[0].substring(0, 1);
                String displayString = parts[0].substring(1).trim();
                String newSelector = parts[1];
                String hostname = parts[2];
                int newPort = Integer.parseInt(parts[3]);
                String fullPath = path + "/" + displayString;

                if (!hostname.equals(this.hostname) || newPort != this.port) {
                    continue; // Skip external servers
                } else if ("1".equals(type)) {
                    recursiveFetch(newSelector, fullPath);
                } else if ("0".equals(type)) {
                    textFiles.add(fullPath);
                    long size = data.length();
                    if (size < smallestTextFileSize) {
                        smallestTextFileSize = size;
                        smallestTextFileContents = data;
                    }
                    if (size > largestTextFileSize) {
                        largestTextFileSize = size;
                    }
                } else if ("9".equals(type)) { // Assuming '9' represents binary files
                    binaryFiles.add(fullPath);
                    long size = data.length();
                    if (size < smallestBinaryFileSize) {
                        smallestBinaryFileSize = size;
                    }
                    if (size > largestBinaryFileSize) {
                        largestBinaryFileSize = size;
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
    }

    public static void main(String[] args) {
        setupLogger();
        String hostname = "comp3310.ddns.net";
        int port = 70;

        GopherIndexer indexer = new GopherIndexer(hostname, port);

        try {
            indexer.recursiveFetch("", "/");
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