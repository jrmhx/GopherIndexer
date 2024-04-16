import java.io.*;
import java.net.*;
import java.util.*;

public class GopherIndexer {
    private String server;
    private int port;
    private Set<String> visited;
    private List<String> textFiles;
    private List<String> binaryFiles;
    private long largestTextFileSize = 0;
    private String smallestTextFileContents = null;
    private long smallestTextFileSize = Long.MAX_VALUE;
    private long smallestBinaryFileSize = Long.MAX_VALUE;
    private long largestBinaryFileSize = 0;

    public GopherIndexer(String server, int port) {
        this.server = server;
        this.port = port;
        this.visited = new HashSet<>();
        this.textFiles = new ArrayList<>();
        this.binaryFiles = new ArrayList<>();
    }

    private String fetchFromGopher(String selector) throws IOException {
        Socket socket = new Socket(server, port);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        // Send the selector followed by a carriage return and newline
        out.println(selector + "\r\n");

        // Read the response
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            response.append(line).append("\n");
        }

        // Close connections
        in.close();
        out.close();
        socket.close();

        return response.toString();
    }

    public void recursiveFetch(String selector, String path) throws IOException {
        String resourceKey = server + ":" + port + selector;
        if (visited.contains(resourceKey)) {
            return;
        }
        visited.add(resourceKey);

        String data = fetchFromGopher(selector);
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

                if (!hostname.equals(this.server) || newPort != this.port) {
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
        GopherIndexer indexer = new GopherIndexer("comp3310.ddns.net", 70);
        try {
            indexer.recursiveFetch("", "/");
            indexer.printStatistics();
        } catch (IOException e) {
            System.err.println("Error during Gopher indexing: " + e.getMessage());
        }
    }
}
