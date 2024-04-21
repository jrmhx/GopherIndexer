import java.io.*;
import java.net.*;

/**
 * The ConnectionHandler class is responsible for establishing and managing a network connection
 * to a specified host and port. It handles both textual and binary data transmission,
 * and provides functionality to manage timeouts and retry connections if initial attempts fail.
 */
public class ConnectionHandler {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private InputStream rawIn; // for binary

    // Constants for timeout and retries
    private static final int CONNECTION_TIMEOUT = 2000; // 2 seconds
    private static final int READ_TIMEOUT = 5000; // 5 seconds
    private static final int MAX_RETRIES = 2; // max retries for connecting

    /**
     * Attempts to connect to a server with a specified host and port,
     * retrying up to a maximum number of times defined by MAX_RETRIES.
     * @param host The hostname or IP address of the server to connect to.
     * @param port The port number of the server.
     * @throws IOException If all attempts to connect fail or if interrupted during the retry backoff.
     */
    public void connect(String host, int port) throws IOException {
        IOException lastException = null;
        for (int attempts = 0; attempts < MAX_RETRIES; attempts++) {
            try {
                // Attempt to create and connect the socket
                socket = new Socket();
                // Set the connection timeout for how long to wait for the server to respond
                socket.connect(new InetSocketAddress(host, port), CONNECTION_TIMEOUT);
                // Set the read timeout to define how long to wait for data once connected
                socket.setSoTimeout(READ_TIMEOUT);

                // Initialize the output and input streams
                out = new PrintWriter(socket.getOutputStream(), true);
                rawIn = socket.getInputStream();  // Store the raw input stream
                in = new BufferedReader(new InputStreamReader(rawIn)); // Continue to initialize the buffered reader

                return; // Successful connection, exit the method
            } catch (IOException e) {
                lastException = e;
                try {
                    // Close any potentially half-open socket before retrying
                    if (socket != null) {
                        socket.close();
                    }
                } catch (IOException ce) {
                    // Ignore errors on close during connection attempts
                }
                // Implementing exponential backoff
                try {
                    Thread.sleep((long) (Math.pow(2, attempts) * 100));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while trying to connect", ie);
                }
            }
        }
        // After all attempts, if still not connected, throw the last exception encountered
        throw lastException;
    }

    /**
     * Closes the connection and associated streams.
     * @throws IOException If an error occurs while closing the socket or streams.
     */
    public void disconnect() throws IOException {
        in.close();
        out.close();
        socket.close();
    }

    /**
     * Sends a specified request to the connected server and retrieves the response.
     * The response is limited to a maximum size to prevent excessive memory usage.
     * @param request The request string to be sent to the server.
     * @return A string containing the server's response.
     * @throws IOException If an error occurs during communication or if the response exceeds the maximum allowable size.
     */
    public String sendRequest(String request) throws IOException {
        out.print(request + "\r\n");
        out.flush();  // Ensure that all data is sent to the server

        StringBuilder response = new StringBuilder();
        String line;
        final int MAX_RESPONSE_SIZE = 1024 * 1024; // 1MB limit
        int responseSize = 0;

        while ((line = in.readLine()) != null) {
            if (responseSize + line.getBytes().length > MAX_RESPONSE_SIZE) {
                throw new IOException("Response size exceeds the maximum limit of " + MAX_RESPONSE_SIZE + " bytes");
            }
            response.append(line).append("\n");
            responseSize += line.getBytes().length; // Update the response size
        }

        return response.toString();
    }

    /**
     * Provides access to the raw input stream for reading binary data directly from the socket.
     * @return The InputStream connected to the socket for binary data.
     */
    public InputStream getRawInputStream() {
        return rawIn;  // Return the InputStream for binary data
    }

    /**
     * Provides access to the output stream for sending data to the connected server.
     * @return The PrintWriter connected to the socket's output stream.
     */
    public PrintWriter getOutputStream() {
        return out;
    }
}