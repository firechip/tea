package jackpal.androidterm.sample.telnet

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.method.TextKeyListener
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import jackpal.androidterm.emulatorview.EmulatorView
import jackpal.androidterm.emulatorview.TermSession
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * This sample activity demonstrates the use of EmulatorView.
 *
 * This activity also demonstrates how to set up a simple TermSession connected
 * to a local program.  The Telnet connection demonstrates a more complex case;
 * see the TelnetSession class for more details.
 */
class TermActivity : Activity() {
    private var mEntry: EditText? = null
    private var mEmulatorView: EmulatorView? = null
    private var mSession: TermSession? = null

    /** Called when the activity is first created.  */
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.term_activity)

        /* Text entry box at the bottom of the activity.  Note that you can
           also send input (whether from a hardware device or soft keyboard)
           directly to the EmulatorView. */mEntry = findViewById(R.id.term_entry)
        mEntry?.setOnEditorActionListener(OnEditorActionListener setOnEditorActionListener@{ v: TextView, _: Int, ev: KeyEvent? ->
            // Ignore enter-key-up events
            if (ev != null && ev.action == KeyEvent.ACTION_UP) {
                return@setOnEditorActionListener false
            }
            // Don't try to send something if we're not connected yet
            val session = mSession
            if (mSession == null) {
                return@setOnEditorActionListener true
            }
            val e = v.text as Editable
            // Write to the terminal session
            session!!.write(e.toString())
            session.write('\r'.toInt())
            TextKeyListener.clear(e)
            true
        })

        /* Sends the content of the text entry box to the terminal, without
           sending a carriage return afterwards */
        val sendButton = findViewById<Button>(R.id.term_entry_send)
        sendButton.setOnClickListener {
            // Don't try to send something if we're not connected yet
            val session = mSession
            if (mSession == null) {
                return@setOnClickListener
            }
            val e = mEntry?.text
            session!!.write(e.toString())
            TextKeyListener.clear(e)
        }
        val view: EmulatorView = findViewById(R.id.emulatorView)
        mEmulatorView = view

        /* Let the EmulatorView know the screen's density. */
        val metrics: DisplayMetrics?
        metrics = view.resources.displayMetrics
        view.setDensity(metrics)

        /* Create a TermSession. */
        val myIntent = intent
        val sessionType = myIntent.getStringExtra("type")
        val session: TermSession?
        if (sessionType != null && sessionType == "telnet") {
            /* Telnet connection: we need to do the network connect on a
               separate thread, so kick that off and wait for it to finish. */
            connectToTelnet(myIntent.getStringExtra("host"))
            return
        } else {
            // Create a local shell session.
            session = createLocalTermSession()
            if (session == null) {
                finish()
                return
            }
            mSession = session
        }

        /* Attach the TermSession to the EmulatorView. */view.attachSession(session)

        /* That's all you have to do!  The EmulatorView will call the attached
           TermSession's initializeEmulator() automatically, once it can
           calculate the appropriate screen size for the terminal emulator. */
    }

    override fun onResume() {
        super.onResume()

        /* You should call this to let EmulatorView know that it's visible
           on screen. */mEmulatorView!!.onResume()
        mEntry!!.requestFocus()
    }

    override fun onPause() {
        /* You should call this to let EmulatorView know that it's no longer
           visible on screen. */
        mEmulatorView!!.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (mSession != null) {
            mSession!!.finish()
        }
        super.onDestroy()
    }

    /**
     * Create a TermSession connected to a local shell.
     */
    private fun createLocalTermSession(): TermSession? {
        /* Instantiate the TermSession ... */
        val session = TermSession()

        /* ... create a process ... */
        /* TODO:Make local session work without exec pty.
        String execPath = LaunchActivity.getDataDir(this) + "/bin/execpty";
        ProcessBuilder execBuild =
                new ProcessBuilder(execPath, "/system/bin/sh", "-");
        */
        val execBuild = ProcessBuilder("/system/bin/sh", "-")
        execBuild.redirectErrorStream(true)
        val exec: Process
        exec = try {
            execBuild.start()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start terminal process.", e)
            return null
        }

        /* ... and connect the process's I/O streams to the TermSession. */session.termIn = exec.inputStream
        session.termOut = exec.outputStream

        /* You're done! */return session
    }

    /**
     * Connect to the Telnet server.
     */
    private fun connectToTelnet(server: String?) {
        val telnetServer = server?.split(";", ignoreCase = true, limit = 2)?.toTypedArray()
        val hostname = telnetServer?.get(0)
        var port = 23
        if (telnetServer?.size == 2) {
            port = telnetServer[1].toInt()
        }
        val portNum = port

        /* On Android API >= 11 (Honeycomb and later), network operations
           must be done off the main thread, so kick off a new thread to
           perform the connect. */object : Thread() {
            override fun run() {
                // Connect to the server
                mSocket = try {
                    Socket(hostname, portNum)
                } catch (e: IOException) {
                    Log.e(TAG, "Could not create socket", e)
                    return
                }

                // Notify the main thread of the connection
                mHandler.sendEmptyMessage(MSG_CONNECTED)
            }
        }.start()
    }

    /**
     * Handler which will receive the message from the Telnet connect thread
     * that the connection has been established.
     */
    private val mHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == MSG_CONNECTED) {
                createTelnetSession()
            }
        }
    }
    var mSocket: Socket? = null

    /* Create the TermSession which will handle the Telnet protocol and
       terminal emulation. */
    private fun createTelnetSession() {
        val socket = mSocket

        // Get the socket's input and output streams
        val termIn: InputStream
        val termOut: OutputStream
        try {
            termIn = socket!!.getInputStream()
            termOut = socket.getOutputStream()
        } catch (e: IOException) {
            // Handle exception here
            return
        }

        /* Create the TermSession and attach it to the view.  See the
           TelnetSession class for details. */
        val session: TermSession = TelnetSession(termIn, termOut)
        mEmulatorView!!.attachSession(session)
        mSession = session
    }

    companion object {
        private const val TAG = "TermActivity"
        private const val MSG_CONNECTED = 1
    }
}