package nisargpatel.deadreckoning.activity;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

import nisargpatel.deadreckoning.R;
import nisargpatel.deadreckoning.adapter.GuideAdapter;
import nisargpatel.deadreckoning.model.GuideItem;

public class GuideActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private GuideAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);

        initViews();
        setupToolbar();
        loadGuideItems();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerViewGuide);
        adapter = new GuideAdapter(new ArrayList<>());
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void loadGuideItems() {
        List<GuideItem> items = new ArrayList<>();

        items.add(new GuideItem(
            "1. How does Dead Reckoning work?",
            "Dead Reckoning (navigation by estimation) calculates your position using:\n\n" +
            "• Steps: detected by the accelerometer\n" +
            "• Direction: calculated by the gyroscope and magnetometer\n" +
            "• GPS calibration: corrects errors over time\n\n" +
            "The more you walk, the better the system becomes!",
            R.drawable.ic_directions_walk
        ));

        items.add(new GuideItem(
            "2. Turn detection",
            "The app automatically detects turns:\n\n" +
            "• Left turn (Left)\n" +
            "• Right turn (Right)\n" +
            "• Slight turns (↰ ↱)\n" +
            "• U-Turn\n\n" +
            "The heading is calculated in real time with a Kalman filter for more precision.",
            R.drawable.ic_turn_left
        ));

        items.add(new GuideItem(
            "3. GPS Calibration",
            "On first launch, calibrate outside with a clear view of the sky:\n\n" +
            "• Wait for GPS accuracy under 15 m\n" +
            "• Walk straight for about 30 m while calibration is running\n" +
            "• Stop and save after at least 20 m and 15 steps are recorded\n\n" +
            "The app saves your measured stride length and adjusts it against GPS while fixes are available. " +
            "A straight route makes the start-to-end distance useful for calibration.",
            R.drawable.ic_gps
        ));

        items.add(new GuideItem(
            "4. Step Calibration",
            "To calibrate step length:\n\n" +
            "1. Go to the Calibration tab\n" +
            "2. Measure a known distance (e.g., 10m)\n" +
            "3. Count your steps over this distance\n" +
            "4. Enter the distance and number of steps\n\n" +
            "Average length: 0.65 - 0.85 m depending on height",
            R.drawable.ic_settings
        ));

        items.add(new GuideItem(
            "5. Data Export",
            "You can export your trips:\n\n" +
            "• CSV format: for Excel, data analysis\n" +
            "• GPX format: for import into other applications\n\n" +
            "Files are saved in the app's folder.",
            R.drawable.ic_history
        ));

        items.add(new GuideItem(
            "6. Tips for accuracy",
            "For best results:\n\n" +
            "• Hold the phone steadily\n" +
            "• Avoid sudden movements\n" +
            "• Reset before each trip\n" +
            "• Check calibration regularly\n\n" +
            "Accuracy also depends on the quality of your device's sensors.",
            R.drawable.ic_help
        ));

        items.add(new GuideItem(
            "7. No GPS Mode (Dead Reckoning)",
            "When GPS is unavailable:\n\n" +
            "• Tap 'No GPS' button to switch to dead reckoning\n" +
            "• Use arrow buttons to turn (left/right/u-turn)\n" +
            "• Each tap turns 90 degrees\n" +
            "• Distance is calculated from step count\n\n" +
            "The app will estimate your position based on steps and heading.",
            R.drawable.ic_directions_walk
        ));

        items.add(new GuideItem(
            "8. Adding Markers on the Map",
            "Add custom markers to save locations:\n\n" +
            "• Tap the + button (orange) to enter marker mode\n" +
            "• Tap on the map where you want to place a marker\n" +
            "• Choose an emoji icon from the list\n" +
            "• Optionally add a label for the marker\n" +
            "• Tap Confirm to save the marker\n\n" +
            "Markers are saved permanently and will appear when you reopen the app.",
            R.drawable.ic_add_location
        ));

        items.add(new GuideItem(
            "9. Rotate the Map (360°)",
            "Rotate the map view with two fingers:\n\n" +
            "• Place two fingers on the map\n" +
            "• Rotate in a circular motion\n" +
            "• The map will rotate 360° like Google Maps\n" +
            "• This helps align the map with your direction of travel\n\n" +
            "Tip: Double-tap to reset to North-up orientation.",
            R.drawable.ic_turn_around
        ));

        items.add(new GuideItem(
            "10. Clearing Data",
            "Manage your saved data:\n\n" +
            "• Tap the trash icon to open the clear menu\n" +
            "• Options:\n" +
            "  - Tracks only: Clear current path\n" +
            "  - Markers only: Delete all saved markers\n" +
            "  - Everything: Clear both tracks and markers\n\n" +
            "Note: Saved trips in History are not affected.",
            R.drawable.ic_delete
        ));

        items.add(new GuideItem(
            "About & Credits",
            "SignalZero · Dead Reckoning\n\n" +
            "Developed by numslock\n\n" +
            "Based on the original project:\n" +
            "https://github.com/nisargnp/DeadReckoning\n\n" +
            "Libraries Used:\n" +
            "• OSMDroid (OpenStreetMap)\n" +
            "• Google Play Services Location\n" +
            "• Material Components\n\n" +
            "This is an open source project.\n" +
            "Feel free to contribute and modify!",
            R.drawable.ic_help
        ));

        adapter.updateItems(items);
    }
}
