import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class GopherClient {
    public static void main(String[] args) {
        String hostname = "comp3310.ddns.net";
        int port = 70;

        try (Socket socket = new Socket(hostname, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // Send a Gopher request for the root directory
            out.print("\r\n");
            out.flush(); // Explicitly flush to ensure no extra data

            // Read the response from the server
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                System.out.println(inputLine);
            }
        } catch (IOException e) {
            System.err.println("An error occurred while trying to connect to the Gopher server:");
            e.printStackTrace();
        }
    }
}
