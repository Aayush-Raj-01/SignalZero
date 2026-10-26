package nisargpatel.deadreckoning.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import nisargpatel.deadreckoning.R;

public class MainContainerActivity extends AppCompatActivity {

    private BottomNavigationView bottomNavigation;
    private FloatingActionButton fabStartStop;

    private MapFragment mapFragment;
    private StepsFragment stepsFragment;
    private Fragment currentFragment;

    private boolean isTracking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_container);

        initViews();
        initFragments();
        setupBottomNavigation();
    }

    private void initViews() {
        bottomNavigation = findViewById(R.id.bottomNavigation);
        fabStartStop = findViewById(R.id.fabStartStop);

        fabStartStop.setOnClickListener(v -> toggleTracking());
        
        fabStartStop.hide();
    }

    private void initFragments() {
        // FragmentManager restores fragments from saved state after process death.
        // Recreating and adding them unconditionally can crash launch with
        // "Fragment already added" or duplicate container entries.
        mapFragment = (MapFragment) getSupportFragmentManager().findFragmentByTag("map");
        stepsFragment = (StepsFragment) getSupportFragmentManager().findFragmentByTag("steps");
        if (mapFragment == null) mapFragment = new MapFragment();
        if (stepsFragment == null) stepsFragment = new StepsFragment();

        androidx.fragment.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        if (!stepsFragment.isAdded()) {
            transaction.add(R.id.fragmentContainer, stepsFragment, "steps");
        }
        transaction.hide(stepsFragment);
        if (!mapFragment.isAdded()) {
            transaction.add(R.id.fragmentContainer, mapFragment, "map");
        }
        transaction.show(mapFragment).commit();

        currentFragment = mapFragment;
    }

    private void setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            
            if (itemId == R.id.nav_map) {
                showFragment(mapFragment);
                return true;
            } else if (itemId == R.id.nav_steps) {
                showFragment(stepsFragment);
                return true;
            } else if (itemId == R.id.nav_history) {
                startActivity(new Intent(this, HistoryActivity.class));
                return false;
            } else if (itemId == R.id.nav_calibrate) {
                startActivity(new Intent(this, CalibrationActivity.class));
                return false;
            } else if (itemId == R.id.nav_guide) {
                startActivity(new Intent(this, GuideActivity.class));
                return false;
            }
            
            return false;
        });
    }

    private void showFragment(Fragment fragment) {
        if (fragment == currentFragment) return;

        getSupportFragmentManager().beginTransaction()
                .hide(currentFragment)
                .show(fragment)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .commit();

        currentFragment = fragment;
        isTracking = mapFragment != null && mapFragment.isTracking();
        fabStartStop.setImageResource(isTracking ? R.drawable.ic_pause : R.drawable.ic_play);
        fabStartStop.setBackgroundTintList(getColorStateList(
                isTracking ? R.color.colorError : R.color.colorPrimary));
        
        if (fragment instanceof MapFragment) {
            fabStartStop.hide();
        } else {
            fabStartStop.show();
        }
    }

    private void toggleTracking() {
        if (mapFragment == null) return;
        if (!isTracking) {
            mapFragment.startTracking();
            isTracking = mapFragment.isTracking();
            if (!isTracking) return;
            fabStartStop.setImageResource(R.drawable.ic_pause);
            fabStartStop.setBackgroundTintList(getColorStateList(R.color.colorError));
        } else {
            mapFragment.stopTracking();
            isTracking = false;
            fabStartStop.setImageResource(R.drawable.ic_play);
            fabStartStop.setBackgroundTintList(getColorStateList(R.color.colorPrimary));
        }
    }

    public boolean isTracking() {
        return isTracking;
    }

    @Override
    protected void onResume() {
        super.onResume();
        bottomNavigation.setSelectedItemId(R.id.nav_map);
    }
}
