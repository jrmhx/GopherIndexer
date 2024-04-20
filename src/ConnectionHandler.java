import java.io.*;
import java.net.*;

public class ConnectionHandler {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    // Constants for timeout and retries
    private static final int CONNECTION_TIMEOUT = 2000; // 2 seconds
    private static final int READ_TIMEOUT = 5000; // 5 seconds
    private static final int MAX_RETRIES = 3; // max retries for connecting

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
                // Set up the output stream
                out = new PrintWriter(socket.getOutputStream(), true);
                // Set up the input stream
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
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

    public void disconnect() throws IOException {
        in.close();
        out.close();
        socket.close();
    }

    public String sendRequest(String request) throws IOException {
        out.print(request+"\r\n");
        out.flush(); // Explicitly flush to ensure no extra data
        StringBuilder response = new StringBuilder();
        String line;

        final int MAX_LINES = 50; // Maximum lines
        int lineCount = 0;

        while ((line = in.readLine()) != null && lineCount < MAX_LINES) {
            response.append(line).append("\n");
            lineCount++;
        }
        if (lineCount >= MAX_LINES) {
            throw new IOException("Too many lines in response");
        }
        return response.toString();
    }
}