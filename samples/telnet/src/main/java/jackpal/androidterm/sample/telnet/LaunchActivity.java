package jackpal.androidterm.sample.telnet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View.OnClickListener;
import android.widget.EditText;

/**
 * Provides a UI to launch the terminal emulator activity, connected to
 * either a local shell or a Telnet server.
 */
public class LaunchActivity extends Activity
{

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.launch_activity);
        final Context context = this;
        addClickListener(R.id.launchLocal, v -> {
            Intent intent = new Intent(context, TermActivity.class);
            intent.putExtra("type", "local");
            startActivity(intent);
        });

        final EditText hostEdit = findViewById(R.id.hostname);
        addClickListener(R.id.launchTelnet, v -> {
            Intent intent = new Intent(context, TermActivity.class);
            intent.putExtra("type", "telnet");
            String hostname = hostEdit.getText().toString();
            intent.putExtra("host", hostname);
            startActivity(intent);
        });
    }

    private void addClickListener(int buttonId, OnClickListener onClickListener) {
        findViewById(buttonId).setOnClickListener(onClickListener);
    }
}
