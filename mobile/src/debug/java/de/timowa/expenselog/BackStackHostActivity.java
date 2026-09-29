package de.timowa.expenselog;

import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

/**
 * An empty activity with MainActivity's fragment container and nothing else, so that
 * BackNavigationTest can drive {@link Navigator}'s back stack. MainActivity itself builds the
 * Dagger graph and opens the real database on the way up; see DialogHostActivity for why that
 * rules it out, and for why a test-only activity lives in {@code src/debug/}.
 */
public class BackStackHostActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout container = new FrameLayout(this);
        container.setId(R.id.mainContentFragment);
        setContentView(container);
    }
}
