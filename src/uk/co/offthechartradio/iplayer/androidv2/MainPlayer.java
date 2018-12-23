/*
 * OTC iPlayer for Android
 *
 * MainPlayer by Andrew Bonney
 * MainPlayer code and all related resources (excluding BASS code) (C) Off The Chart Radio 2011
 * BASS code snippets from their example NetRadio player are used
 */
// REMEMBER TO INCREMENT THE VERSION NUMBER!!!
// adb logcat *:V
// Feature Idea: Scrobbling support?
// Approx 11MB/hr data usage on cellular network
// Would use up 500MB in 46hrs
// TODO: Handle case where connection is totally unavailable - seems to get things into a broken state at the mo
package uk.co.offthechartradio.iplayer.androidv2;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.un4seen.bass.BASS;
import com.un4seen.bass.BASS_AAC;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.http.util.ByteArrayBuffer;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 *
 * @author Andy
 */
public class MainPlayer extends Activity {

    private TextView currentShowTimes, currentShowTitle, currentShowNowPlaying, currentShowUpNext;
    private ImageView currentShowImage;
    private String lastShowTitle, lastNowPlaying, lastMinisite, lastEmail, lastImage;
    private boolean notificationActive = false;
    private boolean errorShown = false;
    private ImageButton listenButton, minisiteButton, emailButton, facebookButton, twitterButton;
    //private ImageButton closeButton;
    private File downloadedImage = null;
    private ShowInfoUpdater shinfo = null;
    private final Handler refreshHandler = new Handler();
    private boolean stopUpdating = false;
    private boolean stoppedUpdating = false;
    private Intent notificationIntent;
    private PendingIntent contentIntent;
    private CharSequence contentText, contentTitle, tickerText;
    private Notification notification;
    private static final int NOTIFIER = 1;
    private String ns = Context.NOTIFICATION_SERVICE;
    private NotificationManager mNotificationManager;
    private boolean mpstatus = false;
    private int notificationIcon = R.drawable.ic_stat_notify_main;
    private long notificationWhen = System.currentTimeMillis();
    private Context context;
    private PackageInfo pinfo;
    private int versionNum = 0;
    private String versionTxt = "0";
    //private IntentFilter inf = new IntentFilter("android.intent.action.PHONE_STATE");
    private int connectStage = 0;
    // 0 = stopped, 1 = preparing icons, 2 = starting player, 3 = connecting, 4 = buffering, 5 = playing, 6 = stopping
    private int connectErrors = 0;
    private boolean paused = false;
    private WifiManager wifi;
    private boolean wifistate = false;
    private int req; // request number/counter
    private int chan; // stream handle
    private Handler handler = new Handler();
    private Runnable timer;
    private Object lock = new Object();
    private boolean killStream = false;
    private ServiceReceiver sr = new ServiceReceiver();
    private IntentFilter inf = new IntentFilter("android.intent.action.PHONE_STATE");
    private boolean alreadyBuffered = false;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.main);

        //closeButton = (ImageButton) findViewById(R.id.button_close);
        listenButton = (ImageButton) findViewById(R.id.button_listen);
        minisiteButton = (ImageButton) findViewById(R.id.button_minisite);
        emailButton = (ImageButton) findViewById(R.id.button_email);
        facebookButton = (ImageButton) findViewById(R.id.button_facebook);
        twitterButton = (ImageButton) findViewById(R.id.button_twitter);
        //closeButton.setOnClickListener(closeButtonListener);
        listenButton.setOnClickListener(listenButtonListener);
        minisiteButton.setOnClickListener(minisiteButtonListener);
        emailButton.setOnClickListener(emailButtonListener);
        facebookButton.setOnClickListener(facebookButtonListener);
        twitterButton.setOnClickListener(twitterButtonListener);

        currentShowImage = (ImageView) findViewById(R.id.on_air_img);
        currentShowTimes = (TextView) findViewById(R.id.on_air_times);
        currentShowTitle = (TextView) findViewById(R.id.on_air_title);
        currentShowNowPlaying = (TextView) findViewById(R.id.on_air_now_playing);
        currentShowUpNext = (TextView) findViewById(R.id.on_air_up_next);
        lastShowTitle = null;
        lastNowPlaying = null;
        lastMinisite = null;
        lastEmail = null;
        lastImage = null;

        notification = new Notification(notificationIcon, tickerText, notificationWhen);
        notificationIntent = new Intent(this, MainPlayer.class);
        contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        mNotificationManager = (NotificationManager) getSystemService(ns);

        context = getApplicationContext();
        try {
            pinfo = context.getPackageManager().getPackageInfo("uk.co.offthechartradio.iplayer.androidv2", 0);
            versionNum = pinfo.versionCode;
            versionTxt = pinfo.versionName;
        } catch (NameNotFoundException ex) {
            Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
        }

        contentTitle = "OTC iPlayer";
        contentText = "The Internet's #1 Hit Music Station";

        // initialize output device
        if (!BASS.BASS_Init(-1, 44100, 0)) {
            Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, "Can't initialize audio device");
            return;
        }
        BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_PLAYLIST, 1); // enable playlist processing
        BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_PREBUF, 0); // minimize automatic pre-buffering, so we can do it (and display it) instead
        BASS.BASS_SetConfigPtr(BASS.BASS_CONFIG_NET_AGENT, "OTC iPlayer/" + versionTxt + " (Android)");

        wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);

        timer = new Runnable() {

            public void run() {
                // monitor prebuffering progress
                long progress = BASS.BASS_StreamGetFilePosition(chan, BASS.BASS_FILEPOS_BUFFER)
                        * 100 / BASS.BASS_StreamGetFilePosition(chan, BASS.BASS_FILEPOS_END); // percentage of buffer filled
                if (progress > 75 || BASS.BASS_StreamGetFilePosition(chan, BASS.BASS_FILEPOS_CONNECTED) == 0) { // over 75% full (or end of download)
                    // get the stream title and set sync for subsequent titles
                    BASS.BASS_ChannelSetSync(chan, BASS.BASS_SYNC_META, 0, MetaSync, 0); // Shoutcast
                    BASS.BASS_ChannelSetSync(chan, BASS.BASS_SYNC_OGG_CHANGE, 0, MetaSync, 0); // Icecast/OGG
                    // set sync for end of stream
                    BASS.BASS_ChannelSetSync(chan, BASS.BASS_SYNC_END, 0, EndSync, 0);
                    // play it!
                    if (killStream) {
                        Kill();
                    } else {
                        BASS.BASS_ChannelPlay(chan, false);
                        connectStage = 5;
                        connectErrors = 0;
                        if (wifistate) {
                            tickerText = "Playing Wi-Fi Stream...";
                        } else {
                            tickerText = "Playing Mobile Stream...";
                        }
                        updateNotification(true);
                    }
                } else {
                    // BUFFERING
                    if (!alreadyBuffered) {
                        alreadyBuffered = true;
                        Log.d("OTC", "onBuffering");
                        tickerText = "Buffering...";
                        updateNotification(true);
                        connectStage = 4;
                    }
                    if (!killStream) {
                        handler.postDelayed(this, 50);
                    } else {
                        Kill();
                    }
                }
            }
        };

        shinfo = (ShowInfoUpdater) new ShowInfoUpdater().execute();
        initPlayer();
    }
    OnClickListener listenButtonListener = new OnClickListener() {

        public void onClick(View v) {
            if (connectStage == 0) {
                connectErrors = 0;
                initPlayer();
            } else if (connectStage != 6) {
                stopPlayer();
            } else {
                CharSequence text = "Player component not ready, please try again";
                int duration = Toast.LENGTH_LONG;
                Toast toast = Toast.makeText(context, text, duration);
                toast.show();
            }
        }

        public void onClick(DialogInterface arg0, int arg1) {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    };
    OnClickListener minisiteButtonListener = new OnClickListener() {

        public void onClick(View v) {
            // Launch minisite
            if (lastMinisite != null) {
                Intent miniIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(lastMinisite));
                startActivity(miniIntent);
            }
        }

        public void onClick(DialogInterface arg0, int arg1) {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    };
    OnClickListener emailButtonListener = new OnClickListener() {

        public void onClick(View v) {
            // Launch e-mail to current DJ
            if (lastEmail != null) {
                Intent emailIntent = new Intent(Intent.ACTION_SEND);
                String aEmailList[] = {lastEmail};
                emailIntent.putExtra(Intent.EXTRA_EMAIL, aEmailList);
                emailIntent.putExtra(Intent.EXTRA_SUBJECT, "[Studio] Message from the OTC iPlayer for Android");
                emailIntent.setType("plain/text");
                try {
                    startActivity(emailIntent);
                } catch (Exception ex) {
                    CharSequence text = "No configured e-mail client found";
                    int duration = Toast.LENGTH_LONG;
                    Toast toast = Toast.makeText(context, text, duration);
                    toast.show();
                }
            }
        }

        public void onClick(DialogInterface arg0, int arg1) {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    };
    OnClickListener facebookButtonListener = new OnClickListener() {

        public void onClick(View v) {
            // Launch facebook.com/offthechartradio
            Intent fbIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.facebook.com/offthechartradio"));
            startActivity(fbIntent);
        }

        public void onClick(DialogInterface arg0, int arg1) {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    };
    OnClickListener twitterButtonListener = new OnClickListener() {

        public void onClick(View v) {
            // Launch twitter.com/offthechart
            Intent twIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.twitter.com/offthechart"));
            startActivity(twIntent);
        }

        public void onClick(DialogInterface arg0, int arg1) {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    };
    Runnable updateShowInfo = new Runnable() {

        public void run() {
            if (!stopUpdating) {
                shinfo = (ShowInfoUpdater) new ShowInfoUpdater().execute();
            } else {
                shinfo.cancel(true);
                stoppedUpdating = true;
            }
        }
    };

    public class ServiceReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context arg0, Intent arg1) {
            CallListener calllistener = new CallListener();
            TelephonyManager telephony = (TelephonyManager) arg0.getSystemService(Context.TELEPHONY_SERVICE);
            telephony.listen(calllistener, PhoneStateListener.LISTEN_CALL_STATE);
        }
    }

    public class CallListener extends PhoneStateListener {

        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if ((state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) && mpstatus) {
                stopPlayer();
                if (paused) {
                    stopUpdating = true;
                }
            }
        }
    }

    private void updateNotification(boolean updateTicker) {
        /*
         * if (notificationActive) { mNotificationManager.cancel(NOTIFIER);
         * notificationActive = false; }
         */
        if (updateTicker) {
            notification = new Notification(notificationIcon, tickerText, notificationWhen);
            notificationIntent = new Intent(this, MainPlayer.class);
            contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        }
        if (lastShowTitle != null) {
            contentTitle = lastShowTitle;
        } else {
            contentTitle = "OTC iPlayer";
        }
        if (lastNowPlaying != null) {
            contentText = lastNowPlaying;
        } else {
            contentText = "The Internet's #1 Hit Music Station";
        }

        notification.setLatestEventInfo(context, contentTitle, contentText, contentIntent);
        notification.flags |= Notification.FLAG_ONGOING_EVENT;
        notification.flags |= Notification.FLAG_NO_CLEAR;

        mNotificationManager.notify(NOTIFIER, notification);
        notificationActive = true;
    }

    @Override
    protected void onPause() {
        paused = true;
        if (!mpstatus) {
            stopUpdating = true;
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!mpstatus) {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        stopUpdating = false;
        // Need to check stoppedUpdating, otherwise if the user returns to the app <30 secs after a pause we'll have double the web requests happening
        if (stoppedUpdating) {
            shinfo = (ShowInfoUpdater) new ShowInfoUpdater().execute();
            stoppedUpdating = false;
        }
    }

    public void stopPlayer() {
        Log.d("OTC", "Stopping");
        listenButton.setImageResource(R.drawable.bt_listen_action);
        if (connectStage >= 1 && connectStage <= 4) {
            killStream = true;
        }
        connectStage = 6;
        if (!killStream) {
            Kill();
        }
        try {
            unregisterReceiver(sr);
        } catch (IllegalArgumentException ex) {
            Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
        }
        connectStage = 0;
        mpstatus = false;
        Log.d("OTC", "Killing notification");
        mNotificationManager.cancel(NOTIFIER);
        notificationActive = false;
    }

    public void initPlayer() {
        mpstatus = false;
        Kill();
        req = 0;
        connectStage = 1;
        listenButton.setImageResource(R.drawable.bt_listen_stop_action);
        tickerText = "Connecting...";
        updateNotification(true);
        startPlayer();
    }

    public void startPlayer() {
        alreadyBuffered = false;
        registerReceiver(sr, inf);
        connectStage = 2;
        String playUrl;
        if (wifi.isWifiEnabled() & wifi.getConnectionInfo().getSSID() != null) {
            BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_BUFFER, 5000);
            BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_TIMEOUT, 10000);
            BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_READTIMEOUT, 5000);
            playUrl = "http://icecast.offthechart.co.uk/otcaacmed";
            wifistate = true;
        } else {
            BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_BUFFER, 30000);
            BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_TIMEOUT, 20000);
            BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_READTIMEOUT, 20000);
            playUrl = "http://icecast.offthechart.co.uk/otcaaclo";
            wifistate = false;
        }
        mpstatus = true;
        Play(playUrl);
    }

    private void showReconnectDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("Unable to tune in. Please check your connection.").setTitle("Error").setCancelable(false).setNegativeButton("OK", new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
                stopPlayer();
            }
        });
        AlertDialog alert = builder.create();
        alert.show();
    }

    private void streamErrorHandler() {
        Log.d("OTC","Error handler");
        connectErrors++;
        if (connectErrors > 2) {
            showReconnectDialog();
        } else {
            stopPlayer();
            tickerText = "Reconnecting...";
            updateNotification(true);
            initPlayer();
        }
    }

    @Override
    protected void onDestroy() {
        if (downloadedImage != null) {
            downloadedImage.deleteOnExit();
        }
        if (mpstatus) {
            BASS.BASS_Stop();
        }
        BASS.BASS_Free();
        super.onDestroy();
    }

    // Prevent back button killing main app if mediaplayer is running
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mpstatus) {
                moveTaskToBack(true);
            } else {
                super.onKeyDown(keyCode, event);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    class RunnableParam implements Runnable {

        Object param;

        RunnableParam(Object p) {
            param = p;
        }

        public void run() {
        }
    }
    // Callback for when title streaming data changes
    BASS.SYNCPROC MetaSync = new BASS.SYNCPROC() {

        public void SYNCPROC(int handle, int channel, int data, Object user) {
            runOnUiThread(new Runnable() {

                public void run() {
                }
            });
        }
    };
    // Callback for when stream ends
    BASS.SYNCPROC EndSync = new BASS.SYNCPROC() {

        public void SYNCPROC(int handle, int channel, int data, Object user) {
            runOnUiThread(new Runnable() {

                public void run() {
                    Log.d("OTC", "onCompletion");
                    if (connectStage == 5) {
                        Log.d("OTC", "Reconnecting");
                        if (!killStream) {
                            streamErrorHandler();
                        }
                    } else {
                        Log.d("OTC", "Stopping player");
                        stopPlayer();
                    }
                }
            });
        }
    };
    BASS.DOWNLOADPROC StatusProc = new BASS.DOWNLOADPROC() {

        public void DOWNLOADPROC(ByteBuffer buffer, int length, Object user) {
            if (buffer != null && length == 0 && (Integer) user == req) { // got HTTP/ICY tags, and this is still the current request
                String[] s;
                try {
                    CharsetDecoder dec = Charset.forName("ISO-8859-1").newDecoder();
                    s = dec.decode(buffer).toString().split("\0"); // convert buffer to string array
                } catch (Exception e) {
                    return;
                }
                runOnUiThread(new RunnableParam(s[0]) { // 1st string = status

                    public void run() {
                    }
                });
            }
        }
    };

    public class OpenURL implements Runnable {

        String url;

        public OpenURL(String p) {
            url = p;
        }

        public void run() {
            int r;
            synchronized (lock) { // make sure only 1 thread at a time can do the following
                r = ++req; // increment the request counter for this request
            }
            BASS.BASS_StreamFree(chan); // close old stream
            runOnUiThread(new Runnable() {

                public void run() {
                    connectStage = 3;
                }
            });
            int c = BASS_AAC.BASS_AAC_StreamCreateURL(url, 0, BASS.BASS_STREAM_BLOCK | BASS.BASS_STREAM_STATUS | BASS.BASS_STREAM_AUTOFREE, StatusProc, r); // open URL
            synchronized (lock) {
                if (r != req) { // there is a newer request, discard this stream
                    if (c != 0) {
                        BASS.BASS_StreamFree(c);
                    }
                    return;
                }
                chan = c; // this is now the current stream
            }
            if (chan == 0) { // failed to open
                runOnUiThread(new Runnable() {

                    public void run() {
                        Log.d("OTC", "Reconnecting");
                        streamErrorHandler();
                    }
                });
            } else {
                if (!killStream) {
                    handler.postDelayed(timer, 50); // start prebuffer monitoring
                } else {
                    Kill();
                }
            }
        }
    }

    public void Kill() {
        synchronized (lock) {
            BASS.BASS_StreamFree(chan);
            chan = 0;
            killStream = false;
        }
    }

    public void Play(String url) {
        new Thread(new OpenURL(url)).start();
    }

    //
    private class ShowInfoUpdater extends AsyncTask<Void, Void, String[]> {

        @Override
        protected String[] doInBackground(Void... arg0) {
            //Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, "running");
            String startURL = "http://www.offthechartradio.co.uk/iplayer/livedata.php?appkey=password&version=" + versionTxt;
            String resultString[] = new String[]{""};
            URL currentURL;
            HttpURLConnection conn = null;
            // Show times, title, now playing, up next, minisite
            // Already have: website address, facebook, twitter, email
            conn = null;
            try {
                currentURL = new URL(startURL);
                conn = (HttpURLConnection) currentURL.openConnection();
                conn.setDoInput(true);
                conn.setDoOutput(true);
                conn.setUseCaches(false);
                conn.setRequestMethod("GET");
                conn.connect();
                conn.getOutputStream().flush();
            } catch (UnknownHostException ex) {
                Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
                conn = null;
            } catch (MalformedURLException ex) {
                Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
                conn = null;
            } catch (IOException ex) {
                Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
                conn = null;
            }
            if (conn != null) {
                errorShown = false;
                InputStream is = null;
                try {
                    is = conn.getInputStream();
                    int ch;
                    StringBuffer sb = new StringBuffer();
                    while ((ch = is.read()) != -1) {
                        sb.append((char) ch);
                    }
                    resultString[0] = sb.toString();
                } catch (Exception e) {
                    Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, e);
                } finally {
                    try {
                        if (is != null) {
                            is.close();
                        }
                    } catch (Exception e) {
                    }
                }
                try {
                    JSONTokener jp = new JSONTokener(resultString[0]);
                    JSONObject json = (JSONObject) jp.nextValue();
                    if (json.getString("status").equals("ok")) {
                        if (!json.getJSONObject("show-now").getString("image").equals(lastImage)) {
                            lastImage = json.getJSONObject("show-now").getString("image");
                            downloadedImage = new File(context.getCacheDir(), "on_air_img_cache.png");
                            conn = null;
                            try {
                                currentURL = new URL(json.getString("url-base") + lastImage);
                                conn = (HttpURLConnection) currentURL.openConnection();
                                conn.setDoInput(true);
                                conn.setDoOutput(true);
                                conn.setUseCaches(false);
                                conn.setRequestMethod("GET");
                                conn.connect();
                                conn.getOutputStream().flush();
                            } catch (UnknownHostException ex) {
                                Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
                                conn = null;
                            } catch (MalformedURLException ex) {
                                Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
                                conn = null;
                            } catch (IOException ex) {
                                Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
                                conn = null;
                            }
                            if (conn != null) {
                                InputStream is2 = null;
                                try {
                                    is2 = conn.getInputStream();
                                    BufferedInputStream bis = new BufferedInputStream(is2);
                                    /*
                                     * Read bytes to the Buffer until there is
                                     * nothing more to read(-1).
                                     */
                                    ByteArrayBuffer baf = new ByteArrayBuffer(50);
                                    int current = 0;
                                    while ((current = bis.read()) != -1) {
                                        baf.append((byte) current);
                                    }
                                    /*
                                     * Convert the Bytes read to a String.
                                     */
                                    FileOutputStream fos = new FileOutputStream(downloadedImage);
                                    fos.write(baf.toByteArray());
                                    fos.close();
                                } catch (FileNotFoundException ex) {
                                    Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
                                } catch (IOException ex) {
                                    Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
                                }

                            } else {
                                downloadedImage = null;
                            }
                        }
                    }
                } catch (JSONException ex) {
                    Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
                } catch (RuntimeException ex) {
                    Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            return resultString;
        }

        private void doError(String errorText) {
            if (!errorShown) {
                CharSequence text = "Unable to connect to offthechartradio.co.uk";
                int duration = Toast.LENGTH_LONG;
                Toast toast = Toast.makeText(context, text, duration);
                toast.show();
                errorShown = true;
            }
            if (!errorText.equals("")) {
                currentShowTitle.setText(errorText);
            }
        }

        @Override
        protected void onPostExecute(String[] result) {
            boolean titleupdate = false;
            boolean npupdate = false;
            if (!result[0].equals("")) {
                try {
                    JSONTokener jp = new JSONTokener(result[0]);
                    JSONObject json = (JSONObject) jp.nextValue();
                    if (json.getString("status").equals("ok")) {
                        JSONObject current = json.getJSONObject("show-now");
                        currentShowTimes.setText(current.getString("time"));
                        if (!current.getString("title").equals("")) {
                            currentShowTitle.setText(current.getString("title"));
                            if (lastShowTitle != null) {
                                if (!lastShowTitle.equals(current.getString("title"))) {
                                    titleupdate = true;
                                }
                            } else {
                                titleupdate = true;
                            }
                            lastShowTitle = current.getString("title");
                        } else {
                            currentShowTitle.setText("Loading...");
                            lastShowTitle = "";
                        }
                        if (!current.getString("minisite").equals("")) {
                            minisiteButton.setImageResource(R.drawable.bt_minisite_action);
                            lastMinisite = json.getString("url-base") + current.getString("minisite");
                        } else {
                            minisiteButton.setImageResource(R.drawable.bt_minisite_off);
                            lastMinisite = null;
                        }
                        if (!current.getString("email").equals("")) {
                            emailButton.setImageResource(R.drawable.bt_message_action);
                            lastEmail = current.getString("email");
                        } else {
                            emailButton.setImageResource(R.drawable.bt_message_off);
                            lastEmail = null;
                        }
                        if (!current.getString("image").equals("") && downloadedImage != null) {
                            Bitmap newImage = BitmapFactory.decodeFile(downloadedImage.getAbsolutePath());
                            currentShowImage.setImageBitmap(newImage);
                        } else {
                            currentShowImage.setImageResource(R.drawable.img_on_air);
                        }
                        if (!json.getJSONObject("show-next").getString("title").equals("") && !json.getJSONObject("show-next").getString("time").equals("")) {
                            currentShowUpNext.setText("Up next: " + json.getJSONObject("show-next").getString("title") + " @ " + json.getJSONObject("show-next").getString("time"));
                        } else {
                            currentShowUpNext.setText("");
                        }
                        if (!json.getString("now-playing").equals("")) {
                            currentShowNowPlaying.setText("Now Playing: " + json.getString("now-playing"));
                            if (lastNowPlaying != null) {
                                if (!lastNowPlaying.equals(json.getString("now-playing"))) {
                                    npupdate = true;
                                }
                            } else {
                                npupdate = true;
                            }
                            lastNowPlaying = json.getString("now-playing");
                        } else {
                            if (lastNowPlaying != null) {
                                currentShowNowPlaying.setText("");
                                npupdate = true;
                            }
                            lastNowPlaying = null;
                        }
                    } else {
                        doError(json.getString("error"));
                    }
                } catch (JSONException ex) {
                    Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
                    doError("");
                } catch (RuntimeException ex) {
                    Logger.getLogger(MainPlayer.class.getName()).log(Level.SEVERE, null, ex);
                    doError("");
                }
            } else {
                doError("");
            }

            if (notificationActive) {
                if (titleupdate) {
                    CharSequence changedTitle;
                    if (lastShowTitle != null) {
                        changedTitle = lastShowTitle;
                    } else {
                        changedTitle = "OTC iPlayer";
                    }
                    if (changedTitle.equals(contentTitle)) {
                        titleupdate = false;
                    } else {
                        contentTitle = changedTitle;
                    }
                }
                if (npupdate) {
                    CharSequence changedText;
                    if (lastNowPlaying != null) {
                        changedText = lastNowPlaying;
                    } else {
                        changedText = "The Internet's #1 Hit Music Station";
                    }
                    if (changedText.equals(contentText)) {
                        npupdate = false;
                    } else {
                        contentText = changedText;
                    }
                }
                if (titleupdate || npupdate) {
                    updateNotification(false);
                }
            }
            refreshHandler.postDelayed(updateShowInfo, 30000);
        }
    }
}
