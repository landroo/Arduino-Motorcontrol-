package org.landroo.arduino;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Hashtable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by rkovacs on 2016.11.15..
 */

public class WebServer {
    private static final String TAG = "WebServer";

    private ThreadPooledServer mServer;
    private int serverPort = 8040;

    private Context context;

    private byte[] mData = null;
    private boolean inProgress = false;

    private int imageFormat = ImageFormat.NV21; //ImageFormat.JPEG; //only support ImageFormat.NV21 and ImageFormat.YUY2 for now
    private int mWidth = 0;
    private int mHeight = 0;

    private String indexHtml;

    private Handler handler;

    public WebServer(int port, Context context, int width, int height, Handler handler) {
        this.serverPort = port;
        this.context = context;
        this.mWidth = width;
        this.mHeight = height;
        this.handler = handler;

        indexHtml = loadAsset("index.html");

        mServer = new ThreadPooledServer(serverPort);
        new Thread(mServer).start();
    }

    /**
     * server thread
     */
    public class ThreadPooledServer implements Runnable
    {
        protected int serverPort = 8040;
        protected ServerSocket serverSocket = null;
        protected boolean isStopped = false;
        protected Thread runningThread = null;
        protected ExecutorService threadPool = Executors.newFixedThreadPool(10);

        public ThreadPooledServer(int port)
        {
            this.serverPort = port;
        }

        private int cnt = 0;

        public void run()
        {
            synchronized(this)
            {
                this.runningThread = Thread.currentThread();
            }

            openServerSocket();

            while(!isStopped())
            {
                Socket clientSocket;
                try
                {
                    clientSocket = this.serverSocket.accept();
                }
                catch(Exception ex)
                {
                    if(isStopped())
                    {
                        //Log.i(TAG, "Server Stopped.");
                        break;
                    }
                    throw new RuntimeException("Error accepting client connection", ex);
                }

                String id = "Thread Pooled Server " + cnt++;

                this.threadPool.execute(new WorkerRunnable(clientSocket, id));
            }

            this.threadPool.shutdown();
            //Log.i(TAG, "Server Stopped.");
        }

        private synchronized boolean isStopped()
        {
            return this.isStopped;
        }

        public synchronized void stop()
        {
            this.isStopped = true;
            try
            {
                this.serverSocket.close();
            }
            catch (Exception ex)
            {
                throw new RuntimeException("Error closing server", ex);
            }
        }

        private void openServerSocket()
        {
            try
            {
                this.serverSocket = new ServerSocket(this.serverPort);
            }
            catch (Exception ex)
            {
                throw new RuntimeException("Cannot open port " + serverPort, ex);
            }
        }
    }

    /**
     * stop server
     */
    public void StopServer()
    {
        if(mServer != null) {
            mServer.stop();
        }
    }

    /**
     * one server thread
     */
    public class WorkerRunnable implements Runnable
    {
        protected Socket clientSocket = null;
        protected String serverText   = null;

        public WorkerRunnable(Socket clientSocket, String serverText)
        {
            this.clientSocket = clientSocket;
            this.serverText   = serverText;
        }

        public void run()
        {
            try
            {
                InputStream input  = clientSocket.getInputStream();
                OutputStream output = clientSocket.getOutputStream();

                byte[] inBuff = new byte[1024];
                DataInputStream in = new DataInputStream(input);
                int bytesRead = in.read(inBuff);
                String header = new String(inBuff, 0, bytesRead);
                HttpRequestParser httpp = new HttpRequestParser();
                httpp.parseRequest(header);
                String page = httpp.getPage();

                // chrome:  User-Agent: Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/47.0.2526.106 Safari/537.36
                // firefox: User-Agent: Mozilla/5.0 (Windows NT 10.0; WOW64; rv:39.0) Gecko/20100101 Firefox/39.0
                // ie11:    User-Agent: Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko
                // edge:    User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/46.0.2486.0 Safari/537.36 Edge/13.10586
                boolean ie = false;
                String userAgent = httpp.getHeaderParam("User-Agent");
                if(userAgent.contains("Trident") || userAgent.contains("Edge"))
                    ie = true;

                try
                {
                    if (mData != null) {
                        if (page.equals("/image.jpg")) {
                            sendJpeg(output, ie);
                        }
                        else if (page.equals("/image.html")) {
                            sendBase64(output);
                        }
                        else if (page.equals("/upUp")) {
                            handler.sendEmptyMessage(2);
                            sendHtml(output, true);
                        }
                        else if (page.equals("/upDown")) {
                            handler.sendEmptyMessage(1);
                            sendHtml(output, true);
                        }
                        else if (page.equals("/downUp")) {
                            handler.sendEmptyMessage(2);
                            sendHtml(output, true);
                        }
                        else if (page.equals("/downDown")) {
                            handler.sendEmptyMessage(3);
                            sendHtml(output, true);
                        }
                        else if (page.equals("/leftUp")) {
                            handler.sendEmptyMessage(5);
                            sendHtml(output, true);
                        }
                        else if (page.equals("/leftDown")) {
                            handler.sendEmptyMessage(4);
                            sendHtml(output, true);
                        }
                        else if (page.equals("/rightUp")) {
                            handler.sendEmptyMessage(5);
                            sendHtml(output, true);
                        }
                        else if (page.equals("/rightDown")) {
                            handler.sendEmptyMessage(6);
                            sendHtml(output, true);
                        }
                        else if (page.equals("/end")) {
                            handler.sendEmptyMessage(9);
                            sendHtml(output, true);
                        }
                        else {
                            sendHtml(output, false);
                        }
                    }
                }
                catch(Exception ex)
                {
                    Log.i(TAG, "WorkerRunnable " + ex);
                }

                output.close();
                input.close();
                //Log.i(TAG, "Request processed: " + this.serverText);
            }
            catch (Exception e)
            {
                //report exception somewhere.
                e.printStackTrace();
            }
        }
    }

    /**
     * Http Request Parser
     */
    public class HttpRequestParser
    {
        private String _requestLine;// GET /image.jpg?nocache=1450171107343 HTTP/1.1
        private String _method;
        private String _httpVersion;
        private String _httpPage;
        private Hashtable<String, String> _requestParams;
        private Hashtable<String, String> _requestHeaders;
        private StringBuffer _messagetBody;

        public class HttpFormatException extends Exception
        {
            private static final long serialVersionUID = 837910846054497673L;

            public HttpFormatException()
            {
                // TODO Auto-generated constructor stub
            }

            public HttpFormatException(String message)
            {
                super(message);
                // TODO Auto-generated constructor stub
            }

            public HttpFormatException(Throwable cause)
            {
                super(cause);
                // TODO Auto-generated constructor stub
            }

            public HttpFormatException(String message, Throwable cause)
            {
                super(message, cause);
                // TODO Auto-generated constructor stub
            }
        }

        public HttpRequestParser()
        {
            _requestHeaders = new Hashtable<String, String>();
            _requestParams = new Hashtable<String, String>();
            _messagetBody = new StringBuffer();
        }

        /**
         * Parse and HTTP request.
         *
         * @param request
         *            String holding http request.
         * @throws Exception
         *             If an I/O error occurs reading the input stream.
         * @throws HttpFormatException
         *             If HTTP Request is malformed
         */
        public void parseRequest(String request) throws Exception, HttpFormatException
        {
            BufferedReader reader = new BufferedReader(new StringReader(request));

            setRequestLine(reader.readLine()); // Request-Line ; Section 5.1

            String header = reader.readLine();
            while (header.length() > 0)
            {
                appendHeaderParameter(header);
                header = reader.readLine();
            }

            String bodyLine = reader.readLine();
            while (bodyLine != null)
            {
                appendMessageBody(bodyLine);
                bodyLine = reader.readLine();
            }

            String[] headArr = _requestLine.split(" ");
            _method = headArr[0];
            _httpVersion = headArr[2];

            headArr = headArr[1].split("[?]");
            _httpPage = headArr[0];
            if(headArr.length > 1)
            {
                headArr = headArr[1].split("&");
                if (headArr.length > 0)
                {
                    String[] pair;
                    for (int i = 0; i < headArr.length; i++)
                    {
                        pair = headArr[i].split("=");
                        if (pair.length == 1)
                            _requestParams.put(pair[0], "");
                        if (pair.length == 2)
                            _requestParams.put(pair[0], pair[1]);
                    }
                }
            }
        }

        /**
         *
         * 5.1 Request-Line The Request-Line begins with a method token, followed by
         * the Request-URI and the protocol version, and ending with CRLF. The
         * elements are separated by SP characters. No CR or LF is allowed except in
         * the final CRLF sequence.
         *
         * @return String with Request-Line
         */
        public String getRequestLine() {
            return _requestLine;
        }

        private void setRequestLine(String requestLine) throws HttpFormatException
        {
            if (requestLine == null || requestLine.length() == 0)
            {
                throw new HttpFormatException("Invalid Request-Line: " + requestLine);
            }
            _requestLine = requestLine;
        }

        private void appendHeaderParameter(String header) throws HttpFormatException
        {
            int idx = header.indexOf(":");
            if (idx == -1)
            {
                throw new HttpFormatException("Invalid Header Parameter: " + header);
            }
            _requestHeaders.put(header.substring(0, idx), header.substring(idx + 1, header.length()));
        }

        /**
         * The message-body (if any) of an HTTP message is used to carry the
         * entity-body associated with the request or response. The message-body
         * differs from the entity-body only when a transfer-coding has been
         * applied, as indicated by the Transfer-Encoding header field (section
         * 14.41).
         * @return String with message-body
         */
        public String getMessageBody() {
            return _messagetBody.toString();
        }

        private void appendMessageBody(String bodyLine) {
            _messagetBody.append(bodyLine).append("\r\n");
        }

        /**
         * For list of available headers refer to sections: 4.5, 5.3, 7.1 of RFC 2616
         * @param headerName Name of header
         * @return String with the value of the header or null if not found.
         */
        public String getHeaderParam(String headerName){
            return _requestHeaders.get(headerName);
        }

        public String getPage()
        {
            return _httpPage;
        }

        public String getMethod()
        {
            return _method;
        }

        public String getVersion()
        {
            return _httpVersion;
        }
    }

    /**
     * send jpeg file
     * @param output OutputStream
     * @param ie boolean
     * @throws Exception
     */
    private void sendJpeg(OutputStream output, boolean ie) throws Exception
    {
        if(mData == null)
            return;
        //Log.i(TAG, "start sending jpeg " + mWidth + "x" + mHeight);
        long timestamp = System.currentTimeMillis();
        String boundary = "netcam" + timestamp + "netcam";
        output.write(("HTTP/1.0 200 OK\r\n" +
                "Server: CamServer\r\n" +
                "Connection: close\r\n" +
                "Max-Age: 0\r\n" +
                "Expires: 0\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: multipart/x-mixed-replace; " +
                "boundary=" + boundary + "\r\n" +
                "\r\n").getBytes());
        if(!ie)
            output.write(("--" + boundary + "\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: " + mData.length + "\r\n" +
                    "X-Timestamp:" + timestamp + "\r\n" +
                    "\r\n").getBytes());

        inProgress = true;
        Rect rect = new Rect(0, 0, mWidth, mHeight);
        // all bytes are in YUV format therefore to use the YUV helper functions we are putting in a YUV object
        YuvImage yuv_image = new YuvImage(mData, imageFormat, mWidth, mHeight, null);
        // image has now been converted to the jpg format and bytes have been written to the output_stream object
        yuv_image.compressToJpeg(rect, 20, output);
        inProgress = false;

        handler.sendEmptyMessage(10);

        output.write(("\r\n--" + boundary + "--\r\n").getBytes());
        //Log.i(TAG, "end sending jpeg " + mWidth + "x" + mHeight);
    }

    /**
     * send base 64 encoded image in a html page
     * @param output OutputStream
     * @throws Exception
     */
    private synchronized void sendBase64(OutputStream output) throws Exception
    {
        if(mData == null)
            return;

        long timestamp = System.currentTimeMillis();

        inProgress = true;
        ByteArrayOutputStream byteArrayBitmapStream = new ByteArrayOutputStream();
        Rect rect = new Rect(0, 0, mWidth, mHeight);
        // all bytes are in YUV format therefore to use the YUV helper functions we are putting in a YUV object
        YuvImage yuv_image = new YuvImage(mData, imageFormat, mWidth, mHeight, null);
        // image has now been converted to the jpg format and bytes have been written to the output_stream object
        yuv_image.compressToJpeg(rect, 30, byteArrayBitmapStream);
        inProgress = false;

        handler.sendEmptyMessage(10);

        byte[] b = byteArrayBitmapStream.toByteArray();
        String encodedImage = Base64.encodeToString(b, Base64.NO_WRAP);

        output.write(("HTTP/1.0 200 OK\r\n" +
                "Server: CamServer\r\n" +
                "Connection: close\r\n" +
                "Max-Age: 0\r\n" +
                "Expires: 0\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-type: text/html\r\n" +
                "X-Timestamp:" + timestamp + "\r\n" +
                "\r\n").getBytes());

        output.write((
                "<!DOCTYPE html>\r\n" +
                        "<html>\r\n" +
                        "<head>\r\n" +
                        "<title></title>\r\n" +
                        "<meta charset=\"utf-8\" />\r\n" +
                        "</head>\r\n" +
                        "<body>\r\n" +
                        "<img src=\"data:image/gif;base64," + encodedImage + "\"" +
                        "alt=\"Base64 encoded image\" width=\"" + mWidth + "\" height=\"" + mHeight + "\"/>\r\n" +
                        "</body>\r\n" +
                        "</html>\r\n"+
                        "\r\n").getBytes());
    }

    /**
     * send html
     * @param output OutputStream
     * @throws Exception
     */
    private void sendHtml(OutputStream output, boolean header) throws Exception
    {
        long timestamp = System.currentTimeMillis();
        output.write(("HTTP/1.0 200 OK\r\n" +
                "Server: CamServer\r\n" +
                "Connection: close\r\n" +
                "Max-Age: 0\r\n" +
                "Expires: 0\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-type: text/html\r\n" +
                "X-Timestamp:" + timestamp + "\r\n" +
                "\r\n").getBytes());

        if(!header) {
            output.write((indexHtml).getBytes());
        }
    }

    /**
     * load asset file
     * @param fName String
     * @return String
     */
    private String loadAsset(String fName)
    {
        StringBuffer sb = new StringBuffer();
        BufferedReader reader = null;
        try
        {
            reader = new BufferedReader(new InputStreamReader(context.getAssets().open(fName), "UTF-8"));

            // do reading, usually loop until end of file reading
            String line;
            while ((line = reader.readLine()) != null)
            {
                //process line
                sb.append(line);
                sb.append("\n");
            }
        }
        catch (Exception e)
        {
            Log.e(TAG, "" + e);
        }
        finally
        {
            if (reader != null)
            {
                try
                {
                    reader.close();
                }
                catch (Exception e)
                {
                    Log.e(TAG, "" + e);
                }
            }
        }

        return sb.toString();
    }

    /**
     * set image data
     * @param data image
     * @param width width
     * @param height height
     */
    public synchronized void setData(byte[] data, int width, int height) {
        if(!inProgress) {
            mWidth = width;
            mHeight = height;
            this.mData = data;
        }
    }
}
