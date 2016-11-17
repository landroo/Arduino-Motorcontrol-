package org.landroo.arduino;

import android.app.Activity;
import android.app.Application;
import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class RemoteActivity extends Activity {
    private static final String TAG = "RemoteActivity";

    public static final int UP_START = 1;
    public static final int UP_STOP = 2;
    public static final int DOWN_START = 3;
    public static final int DOWN_STOP = 2;

    public static final int LEFT_START = 4;
    public static final int LEFT_STOP = 5;
    public static final int RIGHT_START = 6;
    public static final int RIGHT_STOP = 5;

    public static final int DISCONNECT = 9;

    private EditText mIPEdit;
    private int port = 8040;
    private String ip;
    private Socket clientSocket;
    private ServerSocket serverSocket;
    private DataOutputStream outStream = null;
    private DataInputStream inStream = null;
    private boolean connected = false;
    private Button ipconnectButton;
    private Button wificonnectButton;
    private TextView infoText;
    private ImageView remotePreview;

    private final IntentFilter intentFilter = new IntentFilter();
    private WifiP2pManager mManager;
    private WifiP2pManager.Channel mChannel;
    private List<WifiP2pDevice> peers = new ArrayList();
    private WifiP2pDevice device;
    private WifiP2pInfo p2pInfo;
    private AlertDialog alertDialog;

    private static PowerManager.WakeLock wakeLock = null;

    /**
     * BroadcastReceiver
     */
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                // Determine if Wifi P2P mode is enabled or not, alert the Activity.
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    wificonnectButton.setEnabled(true);
                }
                else {
                    wificonnectButton.setEnabled(false);
                }
            }
            else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                // The peer list has changed!  We should probably do something about that.
                if (mManager != null) {
                    mManager.requestPeers(mChannel, peerListListener);
                }
                //Log.d(TAG, "remote WIFI_P2P_PEERS_CHANGED_ACTION");
            }
            else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                // Connection state changed!  We should probably do something about that.
                Log.d(TAG, "remote WIFI_P2P_CONNECTION_CHANGED_ACTION");

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
                                            //Log.i(TAG, "Remote will act as a server.");
                                            new ServerAsyncTask().execute();
                                        }
                                        else if (info.groupFormed) {
                                            //Log.i(TAG, "Remote will act as a client.");
                                            new ClientAsyncTask().execute();
                                        }
                                    }
                                }
                            }
                    );
                }
                else {
                    //Log.i(TAG, "remote network not connected");
                }
            }
            else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "remote WIFI_P2P_THIS_DEVICE_CHANGED_ACTION");

                WifiP2pDevice device = (WifiP2pDevice) intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if(device.status == WifiP2pDevice.AVAILABLE) {
                    connected = false;
                }
            }
        }
    };

    private WifiP2pManager.PeerListListener peerListListener;
    private WifiP2pManager.ChannelListener channelListener = new WifiP2pManager.ChannelListener() {
        @Override
        public void onChannelDisconnected() {
            Log.i(TAG, "Channel Disconnected!");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote);

        mIPEdit = (EditText) findViewById(R.id.editText);

        infoText = (TextView) findViewById(R.id.infoView);

        remotePreview = (ImageView) findViewById(R.id.remotePreview);
        //remotePreview.setRotation(90);

        ipconnectButton = (Button) findViewById(R.id.button_ip_connect);
        ipconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isConnected(RemoteActivity.this) == 1) {
                    ip = mIPEdit.getText().toString();

                    if (clientSocket != null) {
                        try {
                            clientSocket.close();
                        } catch (Exception ex) {
                            Log.i(TAG, "clientSocket " + ex);
                        }
                    }

                    new ConnectTask().execute();

                    putInfo();
                } else {
                    String sMess = getResources().getString(R.string.no_wifi);
                    Toast.makeText(RemoteActivity.this, sMess, Toast.LENGTH_LONG).show();
                }
            }
        });

        wificonnectButton = (Button) findViewById(R.id.button_wifi_connect);
        wificonnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ipconnectButton.setEnabled(false);
                mIPEdit.setEnabled(false);

                peerListListener = new WifiP2pManager.PeerListListener() {
                    @Override
                    public void onPeersAvailable(WifiP2pDeviceList peerList) {

                        // Out with the old, in with the new.
                        peers.clear();
                        peers.addAll(peerList.getDeviceList());

                        if (peers.size() == 0) {
                            //Log.i(TAG, "No devices found");
                            return;
                        } else {
                            if(!connected) {
                                showDialog();
                            }
                        }
                    }
                };

                mManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        //Log.i(TAG, "WiFi Direct Discovery Initiated");
                    }

                    @Override
                    public void onFailure(int reasonCode) {
                        //Log.i(TAG, "Discovery Failed : " + reasonCode);
                    }
                });
            }
        });

        Button btn = (Button) findViewById(R.id.button_left);
        btn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                    if (connected) {
                        try {
                            outStream.writeInt(LEFT_START);
                        } catch (Exception e) {
                            Log.e(TAG, "" + e);
                        }
                    }
                    return true;
                }
                if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    if (connected) {
                        try {
                            outStream.writeInt(LEFT_STOP);
                        } catch (Exception e) {
                            Log.e(TAG, "" + e);
                        }
                    }
                    return true;
                }
                return false;
            }
        });
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
            }
        });

        btn = (Button) findViewById(R.id.button_right);
        btn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                    if (connected) {
                        try {
                            outStream.writeInt(RIGHT_START);
                        } catch (Exception e) {
                            Log.e(TAG, "" + e);
                        }
                    }
                    return true;
                }
                if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    if (connected) {
                        try {
                            outStream.writeInt(RIGHT_STOP);
                        } catch (Exception e) {
                            Log.e(TAG, "" + e);
                        }
                    }
                    return true;
                }
                return false;
            }
        });
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
            }
        });

        btn = (Button) findViewById(R.id.button_forward);
        btn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                    if (connected) {
                        try {
                            outStream.writeInt(UP_START);
                        } catch (Exception e) {
                            Log.e(TAG, "" + e);
                        }
                    }
                    return true;
                }
                if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    if (connected) {
                        try {
                            outStream.writeInt(UP_STOP);
                        } catch (Exception e) {
                            Log.e(TAG, "" + e);
                        }
                    }
                    return true;
                }
                return false;
            }
        });
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
            }
        });

        btn = (Button) findViewById(R.id.button_backward);
        btn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                    if (connected) {
                        try {
                            outStream.writeInt(DOWN_START);
                        } catch (Exception e) {
                            Log.e(TAG, "" + e);
                        }
                    }
                    return true;
                }
                if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    if (connected) {
                        try {
                            outStream.writeInt(DOWN_STOP);
                        } catch (Exception e) {
                            Log.e(TAG, "" + e);
                        }
                    }
                    return true;
                }
                return false;
            }
        });
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
            }
        });

        getInfo();

        // wifi direct
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        mManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        mChannel = mManager.initialize(RemoteActivity.this, getMainLooper(), channelListener);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "ArduinoRemoteLock");
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();

        unregisterReceiver(mReceiver);

        if (clientSocket != null) {
            try {
                outStream.writeInt(DISCONNECT);
                clientSocket.close();
            } catch (Exception ex) {
                Log.i(TAG, "clientSocket " + ex);
            }
        }

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

        connected = false;

        wakeLock.release();
    }

    @Override
    protected void onResume() {
        super.onResume();

        registerReceiver(mReceiver, intentFilter);

        wakeLock.acquire();
    }

    /**
     * Check wifi connection
     *
     * @param context activity context
     * @return boolean
     */
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
     * write configure
     */
    private void putInfo() {
        String text = mIPEdit.getText().toString();
        SharedPreferences settings = getSharedPreferences("org.landroo.arduino_preferences", MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString("ip", text);
        editor.putString("default_port", "" + port);
        editor.commit();
    }

    /**
     * read configure
     */
    private void getInfo() {
        SharedPreferences settings = getSharedPreferences("org.landroo.arduino_preferences", Context.MODE_PRIVATE);
        String ip = settings.getString("ip", "192.168.0.1");
        mIPEdit.setText(ip);
    }

    // ip client task
    private class ConnectTask extends AsyncTask<Integer, Integer, Long> {
        protected Long doInBackground(Integer... data) {
            try {
                // connect to server socket of Ip address
                clientSocket = new Socket(ip, port);
                outStream = new DataOutputStream(clientSocket.getOutputStream());
                inStream = new DataInputStream(clientSocket.getInputStream());
                ClientServiceThread clientThread = new ClientServiceThread(clientSocket, 1);
                clientThread.start();
                connected = true;
            } catch (Exception e) {
                Log.i(TAG, "ConnectTask " + e);
                RemoteActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        infoText.setText("Connection failed!");
                    }
                });
            }

            return (long) 0;
        }
    }

    // image receiver thread
    class ClientServiceThread extends Thread {
        Socket socket;
        public int clientID = -1;
        public boolean running = true;

        ClientServiceThread(Socket soc, int id) {
            socket = soc;
            clientID = id;
        }

        public void run() {
            while (running) {
                try {
                    int len = inStream.readInt();

                    if (len == 1) {
                        RemoteActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                infoText.setText("Connection success!");
                                ipconnectButton.setEnabled(false);
                                mIPEdit.setEnabled(false);
                                //Log.i(TAG, "Connection success!");
                            }
                        });
                    }

                    if (len > 10 && len < 200000) {
                        byte[] buffer = new byte[len];
                        inStream.readFully(buffer, 0, len);

                        final Bitmap image = BitmapFactory.decodeByteArray(buffer, 0, len);
                        if (image != null) {
                            RemoteActivity.this.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    remotePreview.setImageBitmap(image);
                                    remotePreview.invalidate();
                                    //Log.i(TAG, "Image shown!");
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    Log.i(TAG, "ClientServiceThread " + e);
                    running = false;
                }
            }
        }
    }

    // wifi direct peer list dialog
    private void showDialog() {
        AlertDialog.Builder builderSingle = new AlertDialog.Builder(RemoteActivity.this);
        builderSingle.setIcon(R.mipmap.ic_launcher);
        builderSingle.setTitle("Select the Server: ");

        final ArrayAdapter<String> arrayAdapter = new ArrayAdapter<String>(
                RemoteActivity.this,
                android.R.layout.select_dialog_item);

        for (WifiP2pDevice p : peers)
            arrayAdapter.add(p.deviceName);
        //Log.i(TAG, "" + peers.size());

        builderSingle.setNegativeButton(
                "cancel",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogBox, int which) {
                        //dialogBox.dismiss();
                    }
                });

        builderSingle.setAdapter(
                arrayAdapter,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogBox, int which) {
                        //String strName = arrayAdapter.getItem(which);
                        connect(which);
                    }
                });

        if(alertDialog == null || !alertDialog.isShowing()) {
            alertDialog = builderSingle.show();
        }
        else {
            alertDialog.getListView().setAdapter(arrayAdapter);
        }
    }

    // wifi direct connect to a device
    public void connect(int num) {
        // Picking the first device found on the network.
        device = peers.get(num);

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;

        mManager.connect(mChannel, config, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess() {
                //Log.i(TAG, "Client connection success!");
                connected = true;
            }

            @Override
            public void onFailure(int reason) {
                Toast.makeText(RemoteActivity.this, "Connect failed. Retry.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // wifi direct client task
    public class ClientAsyncTask extends AsyncTask<Void, Void, String> {

        public ClientAsyncTask() {
        }

        @Override
        protected String doInBackground(Void... params) {
            clientSocket = new Socket();
            try {
                clientSocket.bind(null);
                clientSocket.connect(new InetSocketAddress(p2pInfo.groupOwnerAddress.getHostAddress(), port), 5000);

                outStream = new DataOutputStream(clientSocket.getOutputStream());
                inStream = new DataInputStream(clientSocket.getInputStream());

                ClientServiceThread clientThread = new ClientServiceThread(clientSocket, 1);
                clientThread.start();

                RemoteActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        alertDialog.dismiss();
                        String sMess = "WiFi Direct connected! " + p2pInfo.groupOwnerAddress.getHostAddress();
                        Toast.makeText(RemoteActivity.this, sMess, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception ex) {
                final String er = "" + ex;
                Log.i(TAG, "WiFi socket failed " + ex);
                    RemoteActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        alertDialog.dismiss();
                        String sMess = "WiFi Direct connection failed! " + er;
                        Toast.makeText(RemoteActivity.this, sMess, Toast.LENGTH_LONG).show();
                    }
                });
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

    // wifi direct server task
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
                //Log.i(TAG, "Remote server socket started!");
                clientSocket = serverSocket.accept();
                //Log.i(TAG, "Remote server socket accepted!");

                outStream = new DataOutputStream(clientSocket.getOutputStream());
                inStream = new DataInputStream(clientSocket.getInputStream());

                ClientServiceThread clientThread = new ClientServiceThread(clientSocket, 0);
                clientThread.start();

                connected = true;

                return "";
            } catch (IOException e) {
                Log.e(TAG, e.getMessage());
                return null;
            }

        }


        @Override
        protected void onPostExecute(String result) {
        }


        @Override
        protected void onPreExecute() {
        }

    }

}
