package jackpal.androidterm.sample.telnet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText

/**
 * Provides a UI to launch the terminal emulator activity, connected to
 * either a local shell or a Telnet server.
 */
class LaunchActivity : Activity() {
    /** Called when the activity is first created.  */
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.launch_activity)
        val context: Context = this
        addClickListener(R.id.launchLocal) { v: View? ->
            val intent = Intent(context, TermActivity::class.java)
            intent.putExtra("type", "local")
            startActivity(intent)
        }
        val hostEdit = findViewById<EditText>(R.id.hostname)
        addClickListener(R.id.launchTelnet) { v: View? ->
            val intent = Intent(context, TermActivity::class.java)
            intent.putExtra("type", "telnet")
            val hostname = hostEdit.text.toString()
            intent.putExtra("host", hostname)
            startActivity(intent)
        }
    }

    private fun addClickListener(buttonId: Int, onClickListener: View.OnClickListener) {
        findViewById<View>(buttonId).setOnClickListener(onClickListener)
    }
}