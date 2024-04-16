import java.io.*;
import java.net.*;

public class ConnectionHandler {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    public void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        out = new PrintWriter(socket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    }

    public void disconnect() throws IOException {
        in.close();
        out.close();
        socket.close();
    }

    public String sendRequest(String request) throws IOException {
        out.println(request);
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            response.append(line + "\n");
        }
        return response.toString();
    }



}
