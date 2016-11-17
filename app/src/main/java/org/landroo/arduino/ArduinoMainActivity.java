package org.landroo.arduino;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ArduinoMainActivity extends Activity {
    private static final String TAG = ArduinoMainActivity.class.getSimpleName();
    private static final int MESSAGE_REFRESH = 101;

    private SerialInputOutputManager mSerialIoManager;

    private UsbManager mUsbManager;
    private static UsbSerialPort sPort = null;
    private int baudRate = 9600;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private TextView mLogView;
    private String[] mLog = new String[20];
    private ScrollView mScrollView;

    private Button clientButton;
    private Button ipserverButton;
    private Button webserverButton;

    private int port = 8040;
    private ServerSocket serverSocket;
    private Socket socket;

    private static PowerManager.WakeLock wakeLock = null;

    public DataOutputStream outStream = null;
    private boolean isSend = false;

    private CamPreview mPreview;
    private SurfaceHolder mHolder;
    public Camera mCamera;
    private List<Camera.Size> supportedSizes;
    private FrameLayout senderPreview;

    private int displayWidth;
    private int displayHeight;
    private int mWidth = 0;
    private int mHeight = 0;
    private int imageFormat = ImageFormat.JPEG; //ImageFormat.JPEG;
    private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;

    private final IntentFilter intentFilter = new IntentFilter();
    private WifiP2pManager mManager;
    private WifiP2pManager.Channel mChannel;
    private WifiP2pInfo p2pInfo;
    private List<WifiP2pDevice> peers = new ArrayList();
    private boolean connected = false;

    private WebServer webServer;

    private WifiP2pManager.ChannelListener channelListener = new WifiP2pManager.ChannelListener() {
        @Override
        public void onChannelDisconnected() {
            Log.i(TAG, "Channel Disconnected!");
        }
    };

    // wifi direct peer listener
    private WifiP2pManager.PeerListListener peerListListener = new WifiP2pManager.PeerListListener() {
        @Override
        public void onPeersAvailable(WifiP2pDeviceList peerList) {

            // Out with the old, in with the new.
            peers.clear();
            peers.addAll(peerList.getDeviceList());

            if (peers.size() == 0) {
                Log.i(TAG, "No wifi direct devices found!");
                return;
            }
            else
            {
                for(WifiP2pDevice p:peers)
                    Log.i(TAG, p.deviceName);
            }
        }
    };

    /**
     * BroadcastReceiver
     */
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                //closeDevice();
            }
            else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                // Determine if Wifi P2P mode is enabled or not, alert the Activity.
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    //wifiserverButton.setEnabled(true);
                }
                else {
                    //wifiserverButton.setEnabled(false);
                }
            }
            else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                // The peer list has changed!  We should probably do something about that.
                if (mManager != null) {
                    mManager.requestPeers(mChannel, peerListListener);
                }
                //Log.d(ArduinoMainActivity.TAG, "server WIFI_P2P_PEERS_CHANGED_ACTION");
            }
            else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                // Connection state changed!  We should probably do something about that.
                //Log.i(TAG, "server WIFI_P2P_CONNECTION_CHANGED_ACTION");

                NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (networkInfo.isConnected()) {
                    mManager.requestConnectionInfo(mChannel,
                            new WifiP2pManager.ConnectionInfoListener() {
                                @Override
                                public void onConnectionInfoAvailable(WifiP2pInfo info) {
                                    //Log.i(TAG, "" + info);
                                    p2pInfo = info;
                                    if (info.groupOwnerAddress != null) {
                                        // When connection is established with other device, We can find that info from wifiP2pInfo here.
                                        if (info.groupFormed && info.isGroupOwner) {
                                            //Log.i(TAG, "Server will act as a server.");
                                            showLog("Direct Wifi server started! " + info.groupOwnerAddress.getHostAddress());
                                            new ServerAsyncTask().execute();
                                        }
                                        else if (info.groupFormed) {
                                            //Log.i(TAG, "Server will act as a client.");
                                            showLog("Direct Wifi client started! " + info.groupOwnerAddress.getHostAddress());
                                            new ClientAsyncTask().execute();
                                        }
                                    }

                                }
                            }
                    );
                }
                else {
                    //Log.i(TAG, "server network not connected");
                }
            }
            else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                //Log.i(TAG, "server WIFI_P2P_THIS_DEVICE_CHANGED_ACTION");
                WifiP2pDevice device = (WifiP2pDevice) intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                final String name = "WiFi id: " + device.deviceName;
                showLog(name);

                showLog(getDeviceStatus(device.status));

                if(device.status == WifiP2pDevice.AVAILABLE) {
                    connected = false;
                    mManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            ArduinoMainActivity.this.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    showLog("WiFi Direct Discovery Initiated");
                                }
                            });
                        }

                        @Override
                        public void onFailure(int reasonCode) {
                            //Log.i(TAG, "Discovery Failed : " + reasonCode);
                            ArduinoMainActivity.this.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    showLog("WiFi Direct Discovery Failed");
                                }
                            });
                        }
                    });
                }
            }
        }
    };

    /**
     * wifi direct states
     * @param deviceStatus device status code
     * @return status string
     */
    private static String getDeviceStatus(int deviceStatus) {
        switch (deviceStatus) {
            case WifiP2pDevice.AVAILABLE:
                return "Available";
            case WifiP2pDevice.INVITED:
                return "Invited";
            case WifiP2pDevice.CONNECTED:
                return "Connected";
            case WifiP2pDevice.FAILED:
                return "Failed";
            case WifiP2pDevice.UNAVAILABLE:
                return "Unavailable";
            default:
                return "Unknown";

        }
    }

    // usb listener
    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                @Override
                public void onRunError(Exception e) {
                    Log.d(TAG, "Runner stopped.");
                }

                @Override
                public void onNewData(final byte[] data) {
                    ArduinoMainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showLog(new String(data));
                        }
                    });
                }
            };

    // message handler
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            //Log.i(TAG, "handler :" + msg.what);
            if(msg.what > 0 && msg.what < 10 &&sPort != null) {
                String str = "" + msg.what;
                try {
                    sPort.write(str.getBytes(), 200);
                }
                catch(Exception ex) {
                   Log.i(TAG, "" + ex);
                }
            }
            else switch (msg.what) {
                case 10: //webserver sent the picture
                    isSend = false;
                    break;
                case MESSAGE_REFRESH:
                    refreshDeviceList();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }

    };

    // camera preview surface
    class CamPreview extends SurfaceView implements SurfaceHolder.Callback {

        public CamPreview(Context context) {
            super(context);

            mHolder = getHolder();// initialize the Surface Holder
            mHolder.addCallback(this); // add the call back to the surface holder
            mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }

        // Called once the holder is ready
        public void surfaceCreated(SurfaceHolder holder) {
            // The Surface has been created, acquire the camera and tell it where to draw.
            try {
                mCamera.setPreviewDisplay(holder);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Called when the holder is destroyed
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (outStream != null) {
                try {
                    outStream.writeInt(-1);
                    outStream.close();
                    outStream = null;
                } catch (Exception e) {
                    Log.i(TAG, "outStream " + e);
                }
            }
        }

        // Called when holder has changed
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            try {
                float rat = 0;

                if(mWidth > mHeight)
                    rat = (float)mHeight / (float)mWidth;
                else
                    rat = (float)mWidth / (float)mHeight;

                int w, h;
                if(width > height) {
                    h = height;
                    w = (int)(height / rat);
                }
                else {
                    w = width;
                    h = (int)(width / rat);
                }

                holder.setFixedSize(w, h);

                //Log.i(TAG, "holder: " + width + " " + height);
                if (mCamera == null)
                    mCamera = Camera.open();// activate the camera
                if (mCamera != null)
                    mCamera.startPreview();// camera frame preview starts when user launches application screen

                Camera.Parameters parameters = mCamera.getParameters();

                Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
                if (display.getRotation() == Surface.ROTATION_0) {
                    parameters.setPreviewSize(mHeight, mWidth);
                    mCamera.setDisplayOrientation(90);
                }
                else if (display.getRotation() == Surface.ROTATION_90) {
                    parameters.setPreviewSize(mWidth, mHeight);
                }
                else if (display.getRotation() == Surface.ROTATION_180) {
                    parameters.setPreviewSize(mHeight, mWidth);
                }
                else if (display.getRotation() == Surface.ROTATION_270) {
                    parameters.setPreviewSize(mWidth, mHeight);
                    mCamera.setDisplayOrientation(180);
                }

                mCamera.setPreviewCallback(new Camera.PreviewCallback() {
                    // Called for each frame previewed
                    public void onPreviewFrame(byte[] data, Camera camera) {
                        System.gc();
                        if(!isSend) {
                            if (outStream != null && connected) {
                                isSend = true;
                                sendImage(data, camera);
                            } else if (webServer != null) {
                                isSend = true;
                                webServer.setData(data.clone(), camera.getParameters().getPreviewSize().width, camera.getParameters().getPreviewSize().height);
                            }
                        }
                    }
                });
                mCamera.setParameters(parameters);// setting the parameters to the camera but this line is not required
            }
            catch (Exception e) {
                Log.i(TAG, "surfaceChanged " + e);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_arduino_main);

        mScrollView = (ScrollView) findViewById(R.id.scroller);
        mLogView = (TextView) findViewById(R.id.tvTopText);
        for (int i = 0; i < mLog.length; i++)
            mLog[i] = "";

        ipserverButton = (Button) findViewById(R.id.ip_server_btn);
        ipserverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                webserverButton.setEnabled(false);

                if (isConnected(ArduinoMainActivity.this) == 1) {

                    try {
                        if (serverSocket != null) {
                            serverSocket.close();
                        }

                        serverSocket = new ServerSocket(port);

                        new ServerTask().execute(0);

                        clientButton.setEnabled(false);

                        showLog("IPServer started: " + getLocalIpAddress() + ":" + port);

                    } catch (Exception e) {
                        Log.e(TAG, "" + e);
                    }
                } else {
                    final String sMess = getResources().getString(R.string.no_wifi);
                    ArduinoMainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showLog(sMess);
                        }
                    });
                }
            }
        });

        webserverButton = (Button) findViewById(R.id.web_server_btn);
        webserverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ipserverButton.setEnabled(false);

                if(webServer != null)
                    webServer.StopServer();

                Camera.Size size = mCamera.getParameters().getPreviewSize();
                webServer = new WebServer(port, ArduinoMainActivity.this, size.width, size.height, mHandler);

                showLog("WebServer started: " + getLocalIpAddress() + ":" + port);

                clientButton.setEnabled(false);
            }
        });

        clientButton = (Button) findViewById(R.id.client_btn);
        clientButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(ArduinoMainActivity.this, RemoteActivity.class);
                startActivity(intent);
                ArduinoMainActivity.this.finish();
            }
        });

        Display display = getWindowManager().getDefaultDisplay();
        displayWidth = display.getWidth();
        displayHeight = display.getHeight();

        createCamera();

        senderPreview = (FrameLayout) findViewById(R.id.senderPreview);

        mPreview = new CamPreview(this);
        senderPreview.addView(mPreview);

        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // usb host
        intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        // wifi direct
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "ArduinoServerLock");
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.sendEmptyMessage(MESSAGE_REFRESH);

        resumeCamera();

        registerReceiver(mReceiver, intentFilter);

        mManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        mChannel = mManager.initialize(ArduinoMainActivity.this, getMainLooper(), channelListener);

        wakeLock.acquire();
    }

    @Override
    protected void onPause() {
        super.onPause();

        // unregister the intent filtered actions
        unregisterReceiver(mReceiver);

        mHandler.removeMessages(MESSAGE_REFRESH);

        if (sPort != null) {
            try {
                sPort.close();
            } catch (IOException e) {
                // Ignore.
            }
            sPort = null;
        }

        try {
            if (mCamera != null) {
                // Call stopPreview() to stop updating the preview surface.
                mCamera.stopPreview();

                mCamera.setPreviewCallback(null);
                mPreview.getHolder().removeCallback(mPreview);

                // Important: Call release() to release the camera for use by other
                // applications. Applications should release the camera immediately
                // during onPause() and re-open() it during onResume()).
                mCamera.release();

                mCamera = null;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        if (outStream != null) {
            try {
                outStream.writeInt(-1);
                outStream.close();
                outStream = null;
            } catch (Exception e) {
                Log.i(TAG, "outStream " + e);
            }
        }

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ex) {
                Log.i(TAG, "toReceiver " + ex);
            }
        }

        wakeLock.release();

        mManager.removeGroup(mChannel, new WifiP2pManager.ActionListener() {

            @Override
            public void onFailure(int reasonCode) {
                Log.d(TAG, "WiFi disconnect failed. Reason :" + reasonCode);
            }

            @Override
            public void onSuccess() {
                Log.d(TAG, "WiFi disconnected.");
            }

        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.net_cam_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings)
        {
            Intent PreferenceScreen = new Intent(this, Preferences.class);
            startActivity(PreferenceScreen);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    // update usb device list
    private void refreshDeviceList() {
        final List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mUsbManager);

        final List<UsbSerialPort> result = new ArrayList<UsbSerialPort>();
        for (final UsbSerialDriver driver : drivers) {
            final List<UsbSerialPort> ports = driver.getPorts();
            result.addAll(ports);
        }

        if (result.size() > 0) {
            sPort = result.get(0);

            UsbDeviceConnection connection = mUsbManager.openDevice(sPort.getDriver().getDevice());

            if (connection == null) {
                showLog("Opening device failed");
                return;
            }

            try {
                sPort.open(connection);
                sPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

                showLog("Serial device: " + sPort.getClass().getSimpleName());
            } catch (IOException e) {
                //Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                showLog("Error opening device: " + e.getMessage());
                try {
                    sPort.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                sPort = null;

                return;
            }

            stopIoManager();
            startIoManager();

        } else {
            showLog("No serial device.");
        }


        return;
    }

    // Stopping usb io manager
    private void stopIoManager() {
        if (mSerialIoManager != null) {
            //Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    // Starting usb io manager
    private void startIoManager() {
        if (sPort != null) {
            //Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(sPort, mListener);
            mExecutor.submit(mSerialIoManager);
        }
    }

    // ip server task
    private class ServerTask extends AsyncTask<Integer, Integer, Long> {
        protected Long doInBackground(Integer... num) {
            try {
                int id = 0;
                ClientServiceThread clientThread = null;
                while (true) {
                    socket = serverSocket.accept();
                    clientThread = new ClientServiceThread(socket, id);
                    clientThread.start();
                    outStream = new DataOutputStream(socket.getOutputStream());
                    //Log.i(TAG, "New client " + id);
                    final String txt = "New client connected " + id;
                    ArduinoMainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showLog(new String(txt));
                        }
                    });
                    id++;

                    connected = true;
                }
            } catch (Exception e) {
                Log.i(TAG, "ShowTask " + e);
            }
            //Log.i(TAG, "Exit");

            return (long) 0;
        }
    }

    // ip server thread
    class ClientServiceThread extends Thread {
        Socket socket;
        public int clientID = -1;
        public boolean running = true;

        ClientServiceThread(Socket soc, int id) {
            socket = soc;
            clientID = id;

            // send connection success sign
            try {
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                dos.writeInt(1);
            } catch (Exception ex) {
                Log.i(TAG, "" + ex);
            }
        }

        // receive command loop
        public void run() {
            while (running) {
                try {
                    DataInputStream dis = new DataInputStream(socket.getInputStream());
                    int command = dis.readInt();
                    if (command > 0 && command < 10) {
                        if (command == RemoteActivity.DISCONNECT) {
                            running = false;
                        }

                        if (sPort != null) {
                            String str = "" + command;
                            sPort.write(str.getBytes(), 200);
                        }

                        final String txt = "New command " + command + " from " + clientID + " client";
                        ArduinoMainActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showLog(new String(txt));
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.i(TAG, "ClientServiceThread " + e);
                    running = false;
                }
            }
        }
    }

    /**
     * Get the IP address of the device
     *
     * @return String 192.168.0.1
     */
    public String getLocalIpAddress() {
        String res = "";
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        res += " " + inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Log.i(TAG, "getLocalIpAddress " + ex);
        }
        //Log.i(TAG, res);

        return res;
    }

    /**
     * write configure
     */
    private void putInfo()
    {
        SharedPreferences settings = getSharedPreferences("org.landroo.arduino_preferences", MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString("default_port", "" + port);
        editor.putString("baudrate_preference", "" + baudRate);
        editor.commit();
    }

    /**
     * read configure
     */
    private void getInfo() {
        SharedPreferences settings = getSharedPreferences("org.landroo.arduino_preferences", Context.MODE_PRIVATE);
        String txt = settings.getString("default_port", "8040");
        port = Integer.parseInt(txt);
        txt = settings.getString("baudrate_preference", "9600");
        baudRate = Integer.parseInt(txt);
    }

    /**
     * show info line
     * @param line info
     */
    private void showLog(String line) {

        for (int i = 0; i < mLog.length - 1; i++) {
            mLog[i] = mLog[i + 1];
        }
        mLog[mLog.length - 1] = line;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mLog.length; i++) {
            if (!mLog[i].equals("")) {
                sb.append(mLog[i]);
                sb.append("\n");
            }
        }
        mLogView.setText(sb.toString());
        mScrollView.smoothScrollTo(0, mLogView.getBottom());
    }

    // is wifi turned on
    private int isConnected(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = null;
        int bOK = 0;
        if (connectivityManager != null) {
            try {
                networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (networkInfo != null && networkInfo.isConnectedOrConnecting()) {
                    bOK = 1;
                }
            } catch (Exception ex) {
                Log.i(TAG, "getNetworkInfo " + ex);
            }
        }

        if (networkInfo == null) {
            bOK = 0;
        }

        return bOK;
    }

    /**
     * create camera preview and enumerate the sizes
     *
     * @return
     */
    private boolean createCamera() {
        try {
            mCamera = Camera.open(); // attempt to get a Camera instance
            if (mCamera == null)
                mCamera = Camera.open(0);
            if (mCamera == null) {
                mCamera = Camera.open(1);
                currentCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
            }
            if (mCamera == null)
                return false;

            // acquire the parameters for the camera
            Camera.Parameters parameters = mCamera.getParameters();

            supportedSizes = parameters.getSupportedPictureSizes();
            int w = Integer.MAX_VALUE;
            for (Camera.Size s : supportedSizes) {
                if (s.width < w)
                    w = s.width;
                if(s.width < displayWidth / 2)
                    break;
            }

            int i = 0;
            for (Camera.Size s : supportedSizes) {
                if (s.width == w)
                    break;
                i++;
            }
            mWidth = supportedSizes.get(i).width;
            mHeight = supportedSizes.get(i).height;
            parameters.setPreviewSize(mWidth, mHeight);

            mCamera.setPreviewCallback(new Camera.PreviewCallback() {
                // Called for each frame previewed
                public void onPreviewFrame(byte[] data, Camera camera) {
                    System.gc();
                    if(!isSend) {
                        if (outStream != null && connected) {
                            isSend = true;
                            sendImage(data, camera);
                        } else if (webServer != null) {
                            isSend = true;
                            webServer.setData(data.clone(), camera.getParameters().getPreviewSize().width, camera.getParameters().getPreviewSize().height);
                        }
                    }
                }
            });
            mCamera.setParameters(parameters);// setting the parameters to the camera but this line is not required

            imageFormat = parameters.getPreviewFormat();
        } catch (Exception ex) {
            Log.e(TAG, "createCamera " + ex);
        }

        return true;
    }

    // resume camera
    private void resumeCamera() {
        try {
            if (mCamera != null)
                mCamera.startPreview();
            else {
                createCamera();

                senderPreview = (FrameLayout) findViewById(R.id.senderPreview);

                mPreview = new CamPreview(this);
                senderPreview.addView(mPreview);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * send an image through the opened socket to the client
     * @param data  byte array image data
     */
    private void sendImage(byte[] data, Camera camera) {
        try {
            Camera.Size size = camera.getParameters().getPreviewSize();

            YuvImage yuv_image = new YuvImage(data, imageFormat, size.width, size.height, null);
            // all bytes are in YUV format therefore to use the YUV helper functions we are putting in a YUV object

            Rect rect = new Rect(0, 0, size.width, size.height);
            ByteArrayOutputStream output_stream = new ByteArrayOutputStream();

            yuv_image.compressToJpeg(rect, 20, output_stream);
            // image has now been converted to the jpg format and bytes have been written to the output_stream object

            byte[] tmp = output_stream.toByteArray();

            outStream.writeInt(tmp.length);
            outStream.write(tmp);// writing the array to the socket output stream
            outStream.flush();
        } catch (Exception ex) {
            Log.i(TAG, "SendTask " + ex);
        }

        data = null;

        isSend = false;
    }

    // start direct wifi server
    public class ServerAsyncTask extends AsyncTask<Void, Void, String> {

        public ServerAsyncTask() {
        }

        @Override
        protected String doInBackground(Void... params) {
            try {
                if(serverSocket != null)
                    serverSocket.close();

                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(port));
                socket = serverSocket.accept();
                ArduinoMainActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showLog("Direct Wifi connection accepted!");
                    }
                });

                outStream = new DataOutputStream(socket.getOutputStream());

                ClientServiceThread clientThread = new ClientServiceThread(socket, 0);
                clientThread.start();

                connected = true;

                return "";
            } catch (IOException e) {
                Log.e(TAG, e.getMessage());
                return null;
            }
        }

        /*
         * (non-Javadoc)
         * @see android.os.AsyncTask#onPostExecute(java.lang.Object)
         */
        @Override
        protected void onPostExecute(String result) {
        }

        /*
         * (non-Javadoc)
         * @see android.os.AsyncTask#onPreExecute()
         */
        @Override
        protected void onPreExecute() {
        }

    }

    // connect with wifi direct client
    public class ClientAsyncTask extends AsyncTask<Void, Void, String> {

        public ClientAsyncTask() {
        }

        @Override
        protected String doInBackground(Void... params) {
            socket = new Socket();
            try {
                socket.bind(null);
                socket.connect(new InetSocketAddress(p2pInfo.groupOwnerAddress.getHostAddress(), port), 5000);

                outStream = new DataOutputStream(socket.getOutputStream());

                ClientServiceThread clientThread = new ClientServiceThread(socket, 0);
                clientThread.start();

                connected = true;

            } catch (Exception ex) {
                Log.i(TAG, "WiFi socket failed " + ex);
            }

            return "";
        }


        @Override
        protected void onPostExecute(String result) {
        }


        @Override
        protected void onPreExecute() {
        }
    }


}
