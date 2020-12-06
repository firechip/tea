package jackpal.androidterm.sample.telnet;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.Editable;
import android.text.method.TextKeyListener;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.EditText;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import jackpal.androidterm.emulatorview.EmulatorView;
import jackpal.androidterm.emulatorview.TermSession;

/**
 * This sample activity demonstrates the use of EmulatorView.
 *
 * This activity also demonstrates how to set up a simple TermSession connected
 * to a local program.  The Telnet connection demonstrates a more complex case;
 * see the TelnetSession class for more details.
 */
public class TermActivity extends Activity
{
    final private static String TAG = "TermActivity";
    private EditText mEntry;
    private EmulatorView mEmulatorView;
    private TermSession mSession;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.term_activity);

        /* Text entry box at the bottom of the activity.  Note that you can
           also send input (whether from a hardware device or soft keyboard)
           directly to the EmulatorView. */
        mEntry = findViewById(R.id.term_entry);
        mEntry.setOnEditorActionListener((v, action, ev) -> {
            // Ignore enter-key-up events
            if (ev != null && ev.getAction() == KeyEvent.ACTION_UP) {
                return false;
            }
            // Don't try to send something if we're not connected yet
            TermSession session = mSession;
            if (mSession == null) {
                return true;
            }

            Editable e = (Editable) v.getText();
            // Write to the terminal session
            session.write(e.toString());
            session.write('\r');
            TextKeyListener.clear(e);
            return true;
        });

        /* Sends the content of the text entry box to the terminal, without
           sending a carriage return afterwards */
        Button sendButton = findViewById(R.id.term_entry_send);
        sendButton.setOnClickListener(v -> {
            // Don't try to send something if we're not connected yet
            TermSession session = mSession;
            if (mSession == null) {
                return;
            }
            Editable e = mEntry.getText();
            session.write(e.toString());
            TextKeyListener.clear(e);
        });

        EmulatorView view = findViewById(R.id.emulatorView);
        mEmulatorView = view;

        /* Let the EmulatorView know the screen's density. */
        DisplayMetrics metrics = new DisplayMetrics();
        metrics = view.getResources().getDisplayMetrics();
        view.setDensity(metrics);

        /* Create a TermSession. */
        Intent myIntent = getIntent();
        String sessionType = myIntent.getStringExtra("type");
        TermSession session;

        if (sessionType != null && sessionType.equals("telnet")) {
            /* Telnet connection: we need to do the network connect on a
               separate thread, so kick that off and wait for it to finish. */
            connectToTelnet(myIntent.getStringExtra("host"));
            return;
        } else {
            // Create a local shell session.
            session = createLocalTermSession();
            if (session == null) {
                finish();
                return;
            }
            mSession = session;
        }

        /* Attach the TermSession to the EmulatorView. */
        view.attachSession(session);

        /* That's all you have to do!  The EmulatorView will call the attached
           TermSession's initializeEmulator() automatically, once it can
           calculate the appropriate screen size for the terminal emulator. */
    }

    @Override
    protected void onResume() {
        super.onResume();

        /* You should call this to let EmulatorView know that it's visible
           on screen. */
        mEmulatorView.onResume();

        mEntry.requestFocus();
    }

    @Override
    protected void onPause() {
        /* You should call this to let EmulatorView know that it's no longer
           visible on screen. */
        mEmulatorView.onPause();

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mSession != null) {
            mSession.finish();
        }

        super.onDestroy();
    }

    /**
     * Create a TermSession connected to a local shell.
     */
    private TermSession createLocalTermSession() {
        /* Instantiate the TermSession ... */
        TermSession session = new TermSession();

        /* ... create a process ... */
        /* TODO:Make local session work without exec pty.
        String execPath = LaunchActivity.getDataDir(this) + "/bin/execpty";
        ProcessBuilder execBuild =
                new ProcessBuilder(execPath, "/system/bin/sh", "-");
        */
        ProcessBuilder execBuild =
                new ProcessBuilder("/system/bin/sh", "-");
        execBuild.redirectErrorStream(true);
        Process exec;
        try {
            exec = execBuild.start();
        } catch (Exception e) {
            Log.e(TAG, "Could not start terminal process.", e);
            return null;
        }

        /* ... and connect the process's I/O streams to the TermSession. */
        session.setTermIn(exec.getInputStream());
        session.setTermOut(exec.getOutputStream());

        /* You're done! */
        return session;

    }

    /**
     * Connect to the Telnet server.
     */
    public void connectToTelnet(String server) {
        String[] telnetServer = server.split(":", 2);
        final String hostname = telnetServer[0];
        int port = 23;
        if (telnetServer.length == 2) {
            port = Integer.parseInt(telnetServer[1]);
        }
        final int portNum = port;

        /* On Android API >= 11 (Honeycomb and later), network operations
           must be done off the main thread, so kick off a new thread to
           perform the connect. */
        new Thread() {
            public void run() {
                // Connect to the server
                try {
                    mSocket = new Socket(hostname, portNum);
                } catch (IOException e) {
                    Log.e(TAG, "Could not create socket", e);
                    return;
                }

                // Notify the main thread of the connection
                mHandler.sendEmptyMessage(MSG_CONNECTED);
            }
        }.start();
    }

    /**
     * Handler which will receive the message from the Telnet connect thread
     * that the connection has been established.
     */
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_CONNECTED) {
                createTelnetSession();
            }
        }
    };

    Socket mSocket;
    private static final int MSG_CONNECTED = 1;

    /* Create the TermSession which will handle the Telnet protocol and
       terminal emulation. */
    private void createTelnetSession() {
        Socket socket = mSocket;

        // Get the socket's input and output streams
        InputStream termIn;
        OutputStream termOut;
        try {
            termIn = socket.getInputStream();
            termOut = socket.getOutputStream();
        } catch (IOException e) {
            // Handle exception here
            return;
        }

        /* Create the TermSession and attach it to the view.  See the
           TelnetSession class for details. */
        TermSession session = new TelnetSession(termIn, termOut);
        mEmulatorView.attachSession(session);
        mSession = session;
    }
}
