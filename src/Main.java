import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.io.BufferedInputStream;

import redis.clients.jedis.Jedis;

public class Main {
    
    public static void main(String[] args) {
        
        int port = 8080;

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Proxy Server Running On Port " + port);

            try(Jedis jedis = new Jedis("localhost", 6379)) {
                jedis.flushAll();
                System.out.println("Connected To Redis");

                Cache cache = new Cache(jedis);

                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(new ProxyThread(clientSocket, cache)).start();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class ProxyThread implements Runnable {
        private Socket clientSocket;
        private Cache cache;

        public ProxyThread(Socket clientSocket, Cache cache) {
            this.clientSocket = clientSocket;
            this.cache = cache;
        }

        public void LoadWebPageFromCache(String host, Cache cache, PrintWriter clientWriter) {

            try{
                byte[] bytes = cache.GetHostBytes(host);

                ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
                InputStreamReader inputStreamReader = new InputStreamReader(byteArrayInputStream);

                BufferedReader serverReader = new BufferedReader(inputStreamReader);

                cache.ModifyHost(host);

                int contentLength = -1;
                boolean isChunked = false;
                String line;
                while ((line = serverReader.readLine()) != null) {

                    clientWriter.println(line);
                    //System.out.println("Received from server: " + line);    //Uncomment this to view all the headers

                    if (line.startsWith("Content-Length:")) {
                        contentLength = Integer.parseInt(line.split(" ")[1]);
                    } else if (line.startsWith("Transfer-Encoding: chunked")) {
                        isChunked = true;
                    }
                    if (line.isEmpty()) {
                        // End of headers
                        clientWriter.println(); // Blank line indicating end of headers
                        break;
                    }
                }
                clientWriter.flush();

                if (contentLength != -1) {

                    char[] buffer = new char[1024];
                    int bytesRead;
                    while (contentLength > 0 && (bytesRead = serverReader.read(buffer, 0, Math.min(buffer.length, contentLength))) != -1) {
                        clientWriter.write(buffer, 0, bytesRead);
                        contentLength -= bytesRead;
                    }
                } else if (isChunked) {

                    while (true) {
                        String chunkSizeHex = serverReader.readLine();

                        System.out.println(chunkSizeHex);

                        if(chunkSizeHex.isEmpty())
                            break;

                        try{
                            int chunkSize = Integer.parseInt(chunkSizeHex.trim(), 16);
                            if (chunkSize == 0) {
                                clientWriter.println();
                                break;
                            }

                            char[] buffer = new char[chunkSize];
                            int bytesRead = serverReader.read(buffer, 0, chunkSize);

                            clientWriter.write(buffer, 0, bytesRead);
                            serverReader.readLine();
                        }
                        catch (NumberFormatException e) {
                            e.printStackTrace();
                            break;
                        }
                    }
                } else {
                    // Read until end of stream (not recommended for production)
                    System.out.println("No \'Content-Length\' Or \'Transfer-Encoding Chunked\' Found");
                    char[] buffer = new char[1024];
                    int bytesRead;
                    while ((bytesRead = serverReader.read(buffer)) != -1) {
                        clientWriter.write(buffer, 0, bytesRead);
                    }
                }

                clientWriter.flush();
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        @Override
        public void run() {

            try {
                String currentThreadName = Thread.currentThread().getName();
                int clientPort = clientSocket.getPort();

                BufferedReader clientReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                PrintWriter clientWriter = new PrintWriter(clientSocket.getOutputStream());
    
                String requestLine = clientReader.readLine();

                if (requestLine.startsWith("CONNECT")) {
                    clientSocket.close(); // Close the client socket after sending the response
                    return;                
                }

                System.out.println(
                    "RECEIVED REQUEST: " + requestLine + " HANDLED BY: " + currentThreadName + " ON PORT: " + clientPort
                );
    
                // Extracting host and port from the request
                String[] requestParts = requestLine.split(" ");
                String urlString = requestParts[1];
                URL url = new URL(urlString);
                String host = url.getHost();
                int port = url.getPort() == -1 ? 80 : url.getPort();
    
                // Forwarding request to server
                Socket serverSocket = new Socket(host, port);
                PrintWriter serverWriter = new PrintWriter(serverSocket.getOutputStream());
                serverWriter.println(requestLine);
                serverWriter.println("Host: " + host);
                serverWriter.println();
                serverWriter.flush();

                InputStream inputStream;
                InputStreamReader inputStreamReader;
                BufferedReader serverReader;

                int contentLength = -1;
                boolean isChunked = false;

                if(cache.containsHost(urlString)){

                    System.out.println("HOST " + urlString + " IS ALREADY CACHED");

                    {
                        // byte[] bytes = cache.GetHostBytes(host);

                        // ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
                        // inputStreamReader = new InputStreamReader(byteArrayInputStream);

                        // serverReader = new BufferedReader(inputStreamReader);

                        // cache.ModifyHost(host);
                    }
                        
                    LoadWebPageFromCache(urlString, cache, clientWriter);
                }
                else{

                    System.out.println("CACHING HOST: " + urlString);

                    inputStream = new BufferedInputStream(serverSocket.getInputStream());
                    inputStreamReader = new InputStreamReader(inputStream);
                    serverReader = new BufferedReader(inputStreamReader);

                    StringBuilder stringBuilder = new StringBuilder();
                    char[] tempBuffer = new char[1024];

                    int tempBytesRead;
                    while ((tempBytesRead = inputStreamReader.read(tempBuffer)) != -1) {
                        stringBuilder.append(tempBuffer, 0, tempBytesRead);

                        String bufferString = new String(tempBuffer, 0, tempBytesRead);
                        //System.out.println(bufferString);

                        if (bufferString.contains("Content-Length:")) {
                            int index = bufferString.indexOf("Content-Length:");
                            int endIndex = bufferString.indexOf("\r\n", index);
                            if (endIndex != -1) {
                                String lengthString = bufferString.substring(index + "Content-Length:".length(), endIndex);
                                contentLength = Integer.parseInt(lengthString.trim());
                            }
                        } else if (bufferString.contains("Transfer-Encoding: chunked")) {
                            isChunked = true;
                        }

                        if (bufferString.contains("</html>") || bufferString.contains("</HTML>")) {
                            // int indexOfHtmlEndTag = bufferString.indexOf("</html>");
                            // if (indexOfHtmlEndTag == -1) {
                            //     indexOfHtmlEndTag = bufferString.indexOf("</HTML>");
                            // }
                            // // Check if the index of "</html>" or "</HTML>" is followed by an empty line
                            // int indexOfEmptyLine = bufferString.indexOf("\n\n", indexOfHtmlEndTag);
                            // if (indexOfEmptyLine != -1) {
                                //System.out.println("_________________REACHED THE END OF RESPONSE_________________");
                                break;
                            // }
                        }
                    }

                    String responseData = stringBuilder.toString();

                    cache.SetHostData(urlString, responseData);

                    int byteSize = responseData.getBytes().length;
                    System.out.println("Host: " + urlString + " BYTE SIZE: " + byteSize);

                    cache.AddNewHost(urlString, byteSize);

                    //System.out.println("________________WE ARE AT THE END OF ELSE BLOCK");
                    LoadWebPageFromCache(urlString, cache, clientWriter);
                }

                {
                    // String line;
                    // while ((line = serverReader.readLine()) != null) {

                    //     System.out.println("________________INSIDE OF WHILE LOOP USING THE SERVER-READER");

                    //     clientWriter.println(line);
                    //     System.out.println("Received from server: " + line);    //Uncomment this to view all the headers

                    //     if (line.startsWith("Content-Length:")) {
                    //         contentLength = Integer.parseInt(line.split(" ")[1]);
                    //     } else if (line.startsWith("Transfer-Encoding: chunked")) {
                    //         isChunked = true;
                    //     }
                    //     if (line.isEmpty()) {
                    //         // End of headers
                    //         clientWriter.println(); // Blank line indicating end of headers
                    //         break;
                    //     }
                    // }
                    // clientWriter.flush();

                    // System.out.println("_____________WE HAVE READ THE HEADERS SUCCESSFULLY");

                    // if (contentLength != -1) {

                    //     System.out.println("______________INSIDE THE CONTENT-LENGTH BLOCK");

                    //     char[] buffer = new char[1024];
                    //     int bytesRead;
                    //     while (contentLength > 0 && (bytesRead = serverReader.read(buffer, 0, Math.min(buffer.length, contentLength))) != -1) {
                    //         clientWriter.write(buffer, 0, bytesRead);
                    //         contentLength -= bytesRead;
                    //     }
                    // } else if (isChunked) {

                    //     System.out.println("______________INSIDE THE IS-CHUNKED BLOCK");

                    //     while (true) {
                    //         String chunkSizeHex = serverReader.readLine();

                    //         System.out.println(chunkSizeHex);

                    //         if(chunkSizeHex.isEmpty())
                    //             break;

                    //         try{
                    //             int chunkSize = Integer.parseInt(chunkSizeHex.trim(), 16);
                    //             if (chunkSize == 0) {
                    //                 clientWriter.println();
                    //                 break;
                    //             }

                    //             char[] buffer = new char[chunkSize];
                    //             int bytesRead = serverReader.read(buffer, 0, chunkSize);

                    //             clientWriter.write(buffer, 0, bytesRead);
                    //             serverReader.readLine();
                    //         }
                    //         catch (NumberFormatException e) {
                    //             e.printStackTrace();
                    //             break;
                    //         }
                    //     }
                    // } else {
                    //     // Read until end of stream (not recommended for production)
                    //     System.out.println("No \'Content-Length\' Or \'Transfer-Encoding Chunked\' Found");
                    //     char[] buffer = new char[1024];
                    //     int bytesRead;
                    //     while ((bytesRead = serverReader.read(buffer)) != -1) {
                    //         clientWriter.write(buffer, 0, bytesRead);
                    //     }
                    // }

                    // clientWriter.flush();
                }

                // Close sockets
                serverSocket.close();
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
