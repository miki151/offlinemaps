
package com.keeperrl.offlinemapsforwearos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.view.InputDeviceCompat;
import androidx.core.view.MotionEventCompat;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.graphics.Color;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.MapPosition;
import org.mapsforge.core.model.Point;
import org.mapsforge.map.android.graphics.AndroidBitmap;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.layers.MyLocationOverlay;
import org.mapsforge.map.android.util.AndroidPreferences;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.datastore.MultiMapDataStore;
import org.mapsforge.map.layer.GroupLayer;
import org.mapsforge.map.layer.Layers;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.labels.LabelLayer;
import org.mapsforge.map.layer.labels.MapDataStoreLabelStore;
import org.mapsforge.map.layer.labels.ThreadedLabelLayer;
import org.mapsforge.map.layer.overlay.Circle;
import org.mapsforge.map.layer.overlay.Marker;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.model.IMapViewPosition;
import org.mapsforge.map.model.common.PreferencesFacade;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.InternalRenderTheme;
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleMenu;
import org.mapsforge.map.scalebar.DefaultMapScaleBar;
import org.mapsforge.map.scalebar.MapScaleBar;
import org.mapsforge.map.scalebar.MetricUnitAdapter;
import org.mapsforge.map.view.InputListener;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.sql.Array;
import java.text.DecimalFormat;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import info.debatty.java.stringsimilarity.Levenshtein;
import info.debatty.java.stringsimilarity.NGram;
import info.debatty.java.stringsimilarity.QGram;

public class MainActivity extends Activity implements LocationListener {

    protected MapView mapView;
    protected List<TileCache> tileCaches = new ArrayList<TileCache>();

    static final String TAG = "OfflineMaps";

    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        Log.i(TAG, "Generic motion " + ev.toString());
        if (ev.getAction() == MotionEvent.ACTION_SCROLL &&
                ev.isFromSource(InputDeviceCompat.SOURCE_ROTARY_ENCODER)
        ) {
            Log.i(TAG, "Rotary motion " + ev.toString());
            // Don't forget the negation here
            if (ev.getAxisValue(MotionEventCompat.AXIS_SCROLL) < 0)
                mapView.getModel().mapViewPosition.zoomIn(false);
            else if (ev.getAxisValue(MotionEventCompat.AXIS_SCROLL) > 0)
                mapView.getModel().mapViewPosition.zoomOut(false);

            return true;
        }
        return false;
    }

    private static Paint getPaint(int color, int strokeWidth, Style style) {
        Paint paint = AndroidGraphicFactory.INSTANCE.createPaint();
        paint.setColor(color);
        paint.setStrokeWidth(strokeWidth);
        paint.setStyle(style);
        return paint;
    }

    private LocationManager locationManager;
    private MyLocationOverlay myLocationOverlay;
    private MyLocationOverlay gpxLocationOverlay;
    private Location lastLocation = null;

    private void setLocation(Location location) {
        this.mapView.setCenter(new LatLong(location.getLatitude(), location.getLongitude()));
    }

    double getLatLongMult(LatLong position) {
        return position.vincentyDistance(new LatLong(position.latitude - 0.01, position.longitude)) /
                position.vincentyDistance(new LatLong(position.latitude, position.longitude - 0.01));
    }

    LatLong getClosestPoint(LatLong p, LatLong s1, LatLong s2, double mult) {
        double a = p.latitude - s1.latitude;
        double b = (p.longitude - s1.longitude) / mult;
        double c = s2.latitude - s1.latitude;
        double d = (s2.longitude - s1.longitude) / mult;
        double lenSq = c * c + d * d;
        double param;
        if (lenSq == 0)
            param = -1;
        else
            param = (a * c + b * d) / lenSq;
        if (param < 0)
            return s1;
        else if (param > 1)
            return s2;
        else
            return new LatLong(s1.latitude + param * c, s1.longitude + param * d * mult);
    }

    @Override
    public void onLocationChanged(Location location) {
        this.myLocationOverlay.setPosition(location.getLatitude(), location.getLongitude(), location.getAccuracy());
        lastLocation = location;
        ImageButton button = (ImageButton) findViewById(R.id.locationButton);
        if (isLockedLocation()) {
            setLocation(location);
        }
        button.setBackgroundColor(getResources().getColor(lockedLocation == Long.MAX_VALUE ? R.color.translucent : R.color.blue));
        if (currentTrack != null) {
            LatLong myPos = new LatLong(location.getLatitude(), location.getLongitude());
            double latLongMult = getLatLongMult(myPos);
            Log.i(TAG, "Lat long mult " + Double.toString(latLongMult));
            LatLong closest = null;
            double myDist = 0;
            double completedDist = 0;
            double distCounter = 0;
            for (int i = 1; i < currentTrack.points.size(); ++i) {
                LatLong p1 = currentTrack.points.get(i - 1);
                LatLong p2 = currentTrack.points.get(i);
                LatLong p = getClosestPoint(myPos, p1, p2, latLongMult);
                double dist = p.vincentyDistance(myPos);
                if (closest == null || dist < myDist) {
                    myDist = dist;
                    closest = p;
//                    Log.i(TAG, "Completed dist " + Double.toString(distCounter) + " " + Double.toString(p1.vincentyDistance(p)));
                    completedDist = distCounter + p1.vincentyDistance(p);
                }
                distCounter += p1.vincentyDistance(p2);
            }
            if (closest != null)
                this.gpxLocationOverlay.setPosition(closest.latitude, closest.longitude, 0);
            TextView distanceLabel = (TextView) findViewById(R.id.distanceLabel);
            DecimalFormat df = new DecimalFormat("0.00");
            distanceLabel.setText(df.format(completedDist / 1000) + " km");
            TextView distanceTotalLabel = (TextView) findViewById(R.id.distanceTotalLabel);
            distanceTotalLabel.setText(df.format(currentTrack.length / 1000) + " km");
        }
    }

    private void toggleView(View view) {
        if (view.getVisibility() == View.GONE)
            view.setVisibility(View.VISIBLE);
        else
            view.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        toggleView(findViewById(R.id.timerOverlay));
        toggleView(findViewById(R.id.trackOverlay));
    }

    private long lockedLocation = Long.MIN_VALUE;
    private boolean isLockedLocation() {
        return lockedLocation < System.currentTimeMillis() - 2000;
    }
    private boolean displayOn = false;
    private Long timerStart = null;

    Handler timerHandler = new Handler();
    Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            TextView timerButton = (TextView) findViewById(R.id.timerLabel);
            if (timerStart == null)
                timerButton.setText("00:00");
            else {
                long millis = System.currentTimeMillis() - timerStart;
                int seconds = (int)millis / 1000;
                int minutes = seconds / 60;
                seconds = seconds % 60;
                timerButton.setText(String.format("%d:%02d", minutes, seconds));
                timerHandler.postDelayed(this, 500);
            }
        }
    };

    protected void createControls() {
        initializePosition(mapView.getModel().mapViewPosition);
        TextView timerButton = (TextView) findViewById(R.id.timerLabel);
        timerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (timerStart == null) {
                    timerStart = System.currentTimeMillis();
                    timerHandler.postDelayed(timerRunnable, 0);
                } else
                    timerStart = null;
            }
        });
        ImageButton locationButton = (ImageButton) findViewById(R.id.locationButton);
        locationButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isLockedLocation()) {
                    lockedLocation = Long.MIN_VALUE;
                    if (lastLocation != null) {
                        locationButton.setBackgroundColor(getResources().getColor(R.color.blue));
                        setLocation(lastLocation);
                    }
                } else {
                    lockedLocation = Long.MAX_VALUE;
                    ImageButton button = (ImageButton) findViewById(R.id.locationButton);
                    button.setBackgroundColor(getResources().getColor(R.color.translucent));
                }
            }
        });
        ImageButton displayButton = (ImageButton) findViewById(R.id.displayButton);
        displayButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!displayOn) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    displayButton.setBackgroundColor(getResources().getColor(R.color.blue));
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    displayButton.setBackgroundColor(getResources().getColor(R.color.translucent));
                }
                displayOn = !displayOn;
            }
        });
    }

    void downloadGpx(String url, String code) {
        DownloadManager downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        //Set whether this download may proceed over a roaming connection.
        request.setAllowedOverRoaming(true);
        //Set the title of this download, to be displayed in notifications (if enabled).
        request.setTitle("Gpx file download");
        //Set a description of this download, to be displayed in notifications (if enabled)
        request.setDescription("");
        //Set the local destination for the downloaded file to a path within the application's external files directory
        String tmpName = code + ".gpx";
        File target = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), tmpName);

        if (target.exists())
            target.delete();
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, tmpName);
        long id = downloadManager.enqueue(request);
        downloadingDialog(id, null);
    }

    void downloadTrackExplanation() {
        LayoutInflater layoutInflater =
                (LayoutInflater)getBaseContext()
                        .getSystemService(LAYOUT_INFLATER_SERVICE);
        View popupView = layoutInflater.inflate(R.layout.categorypopup, null);
        final PopupWindow popupWindow = new PopupWindow(
                popupView, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);

        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        ListView listView = (ListView)popupView.findViewById(R.id.categories);
        String[] elems = new String[]{"RWGPS.com", "Pastebin.com"};
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(MainActivity.this,
                        R.layout.categoryelem, elems);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                switch (i) {
                    case 0:
                        downloadTrackMenu(elems[i], "https://ridewithgps.com/routes/", ".gpx?sub_format=track");
                        break;
                    case 1:
                        downloadTrackMenu(elems[i], "https://pastebin.com/", "");
                        break;
                }
                popupWindow.dismiss();
            }
        });
        popupWindow.showAsDropDown(mapView, 50, -30);
//        AlertDialog.Builder builder = new AlertDialog.Builder(this);
//        TextView myMsg = new TextView(this);
//        myMsg.setText("Upload your track to pastebin.com and enter the code from the URL after the /");
//        myMsg.setGravity(Gravity.CENTER_HORIZONTAL);
//        myMsg.setTextSize(14);
//        //set custom title
//        builder.setCustomTitle(myMsg);
//
//// Set up the buttons
//        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
//            @Override
//            public void onClick(DialogInterface dialog, int which) {
//            }
//        });
//        builder.show();
    }

    HashMap<Long, AlertDialog> downloadDialogs = new HashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    void downloadingDialog(long downloadId, DownloadInfo mapDownload) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        TextView myMsg = new TextView(this);
        myMsg.setText("Downloading file");
        myMsg.setGravity(Gravity.CENTER_HORIZONTAL);
        myMsg.setTextSize(14);
        //set custom title
        builder.setCustomTitle(myMsg);

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                DownloadManager downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
                downloadManager.remove(downloadId);
                if (mapDownload != null) {
                    mapDownload.setStatus(MapDownloadStatus.ABSENT);
                    mapDownloadsAdapter.notifyDataSetChanged();
                }
            }
        });
        if (mapDownload != null)
            builder.setPositiveButton("Background", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    downloadDialogs.remove(downloadId);
                }
            });
        builder.setMessage("0%");
        AlertDialog dialog = builder.show();
        downloadDialogs.put(downloadId, dialog);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean isDownloadFinished = false;
                while (!isDownloadFinished && dialog.isShowing()) {
                    DownloadManager downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
                    Cursor cursor = downloadManager.query(new DownloadManager.Query().setFilterById(downloadId));
                    String progress = "";
                    if (cursor.moveToFirst()) {
                        int downloadStatus = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        switch (downloadStatus) {
                            case DownloadManager.STATUS_RUNNING:
                                long totalBytes = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                                if (totalBytes > 0) {
                                    long downloadedBytes = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            dialog.setMessage((int) (downloadedBytes * 100 / totalBytes) + "% of "
                                                    + totalBytes + " bytes");
                                        }
                                    });
                                }

                                break;
                            case DownloadManager.STATUS_PAUSED:
                            case DownloadManager.STATUS_PENDING:
                                break;
                            case DownloadManager.STATUS_SUCCESSFUL:
                            case DownloadManager.STATUS_FAILED:
                                isDownloadFinished = true;
                                break;
                        }
                    }
                }
            }
        });
    }

    void chooseGpxSite() {

    }

    void downloadTrackMenu(String siteName, String url, String suffix) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter " + siteName + " code:");
        final EditText input = new EditText(this);
// Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

// Set up the buttons
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String code = input.getText().toString();
                downloadGpx(url + code + suffix, code);
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        builder.show();
    }

    void trackClick(int button) {
        multiMenu(Arrays.asList("Activate", "Erase"), Arrays.asList(new Runnable() {
            @Override
            public void run() {
                currentTrack = tracks.get(button);
//                popupWindow.dismiss();
                reloadLayers();
            }
        }, new Runnable() {
            @Override
            public void run() {
                confirmationDialog("Erase this track?", new Runnable() {
                    @Override
                    public void run() {
                        Track toRemove = tracks.get(button);
                        if (currentTrack == toRemove) {
                            currentTrack = null;
                            reloadLayers();
                        }
                        toRemove.file.delete();
                        tracks.remove(button);
                        tracksMenu();
                    }
                });
            }
        }));
    }

    void tracksMenu() {
        View popupView = ((LayoutInflater)getBaseContext()
                .getSystemService(LAYOUT_INFLATER_SERVICE)).inflate(R.layout.categorypopup, null);
        final PopupWindow popupWindow = new PopupWindow(
                popupView, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        ListView listView = (ListView)popupView.findViewById(R.id.categories);
        List<String> elems = new ArrayList<>();
        elems.add("<Download track>");
        for (Track t : tracks)
            elems.add(t.name);
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(MainActivity.this,
                        R.layout.categoryelem, elems);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int button, long l) {
                if (button > 0) {
                    trackClick(button - 1);
                }
                else
                    downloadTrackExplanation();
                popupWindow.dismiss();
            }
        });
        popupWindow.showAsDropDown(mapView, 50, -30);
    }

    void popupMenu() {
        LayoutInflater layoutInflater =
                (LayoutInflater)getBaseContext()
                        .getSystemService(LAYOUT_INFLATER_SERVICE);
        View popupView = layoutInflater.inflate(R.layout.categorypopup, null);
        final PopupWindow popupWindow = new PopupWindow(
                popupView, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);

        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        ListView listView = (ListView)popupView.findViewById(R.id.categories);
        String[] elems = new String[]{"Tracks", "Download maps", "Settings"};
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(MainActivity.this,
                        R.layout.categoryelem, elems);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                switch (i) {
                    case 0:
                        tracksMenu();
                        popupWindow.dismiss();
                        break;
                    case 1:
                        chooseMapGroupAction();
                        break;
                    case 2:
                        break;
                    default:
                        throw new RuntimeException("Unknown menu item: " + Integer.toString(i));

                }
            }
        });
        popupWindow.showAsDropDown(mapView, 50, -30);
    }

    void chooseMapGroupAction() {
        LayoutInflater layoutInflater =
                (LayoutInflater)getBaseContext()
                        .getSystemService(LAYOUT_INFLATER_SERVICE);
        View popupView = layoutInflater.inflate(R.layout.categorypopup, null);
        final PopupWindow popupWindow = new PopupWindow(
                popupView, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);

        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        ListView listView = (ListView)popupView.findViewById(R.id.categories);
        String[] elems = allMaps.keySet().toArray(new String[0]);
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(MainActivity.this,
                        R.layout.categoryelem, elems);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                downloadMapsAction(elems[i]);
            }
        });
        popupWindow.showAsDropDown(mapView, 50, -30);
    }
    public enum MapDownloadStatus {
        ABSENT,
        READY,
        ERROR
    }
    private class DownloadInfo {
        public DownloadInfo(String name, String subdir) {
            this.name = name;
            this.url = "http://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/" + subdir + "/" + name.toLowerCase() + ".map";
        }

        public String getFileName() {
            return name + ".map";
        }

        public File getDownloadedPath() {
            return new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), getFileName());
        }

        public String getTmpName() {
            return getFileName() + ".tmp";
        }

        public File getTmpPath() {
            return new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), getTmpName());
        }

        public long downloadId = 0;
        public boolean isFetching() {
            return downloadId != 0;
        }

        @NonNull
        public String toString() {
            if (isFetching())
                return name + " (fetching)";
            switch (status) {
                case ABSENT: return name;
                case ERROR: return name + " (error downloading)";
                case READY: return name + " (ready)";
            }
            return name;
        }

        private MapDownloadStatus status = MapDownloadStatus.ABSENT;
        void setStatus(MapDownloadStatus status) {
            downloadId = 0;
            this.status = status;
        }
        String name;
        String url;
        int size;
    }

    final Map<String, DownloadInfo[]> allMaps = Map.of(
            "Africa", new DownloadInfo[]{
                    new DownloadInfo("algeria", "africa"),
                    new DownloadInfo("angola", "africa"),
                    new DownloadInfo("benin", "africa"),
                    new DownloadInfo("botswana", "africa"),
                    new DownloadInfo("burkina-faso", "africa"),
                    new DownloadInfo("burundi", "africa"),
                    new DownloadInfo("cameroon", "africa"),
                    new DownloadInfo("canary-islands", "africa"),
                    new DownloadInfo("cape-verde", "africa"),
                    new DownloadInfo("central-african-republic", "africa"),
                    new DownloadInfo("chad", "africa"),
                    new DownloadInfo("comores", "africa"),
                    new DownloadInfo("congo-brazzaville", "africa"),
                    new DownloadInfo("congo-democratic-republic", "africa"),
                    new DownloadInfo("djibouti", "africa"),
                    new DownloadInfo("egypt", "africa"),
                    new DownloadInfo("equatorial-guinea", "africa"),
                    new DownloadInfo("eritrea", "africa"),
                    new DownloadInfo("ethiopia", "africa"),
                    new DownloadInfo("gabon", "africa"),
                    new DownloadInfo("ghana", "africa"),
                    new DownloadInfo("guinea-bissau", "africa"),
                    new DownloadInfo("guinea", "africa"),
                    new DownloadInfo("ivory-coast", "africa"),
                    new DownloadInfo("kenya", "africa"),
                    new DownloadInfo("lesotho", "africa"),
                    new DownloadInfo("liberia", "africa"),
                    new DownloadInfo("libya", "africa"),
                    new DownloadInfo("madagascar", "africa"),
                    new DownloadInfo("malawi", "africa"),
                    new DownloadInfo("mali", "africa"),
                    new DownloadInfo("mauritania", "africa"),
                    new DownloadInfo("mauritius", "africa"),
                    new DownloadInfo("morocco", "africa"),
                    new DownloadInfo("mozambique", "africa"),
                    new DownloadInfo("namibia", "africa"),
                    new DownloadInfo("niger", "africa"),
                    new DownloadInfo("nigeria", "africa"),
                    new DownloadInfo("rwanda", "africa"),
                    new DownloadInfo("saint-helena-ascension-and-tristan-da-cunha", "africa"),
                    new DownloadInfo("sao-tome-and-principe", "africa"),
                    new DownloadInfo("senegal-and-gambia", "africa"),
                    new DownloadInfo("seychelles", "africa"),
                    new DownloadInfo("sierra-leone", "africa"),
                    new DownloadInfo("somalia", "africa"),
                    new DownloadInfo("south-africa-and-lesotho", "africa"),
                    new DownloadInfo("south-sudan", "africa"),
                    new DownloadInfo("sudan", "africa"),
                    new DownloadInfo("swaziland", "africa"),
                    new DownloadInfo("tanzania", "africa"),
                    new DownloadInfo("togo", "africa"),
                    new DownloadInfo("tunisia", "africa"),
                    new DownloadInfo("uganda", "africa"),
                    new DownloadInfo("zambia", "africa"),
                    new DownloadInfo("zimbabwe", "africa")
            },
            "Europe", new DownloadInfo[]{
                    new DownloadInfo("albania", "europe"),
                    new DownloadInfo("alps", "europe"),
                    new DownloadInfo("andorra", "europe"),
                    new DownloadInfo("austria", "europe"),
                    new DownloadInfo("azores", "europe"),
                    new DownloadInfo("belarus", "europe"),
                    new DownloadInfo("belgium", "europe"),
                    new DownloadInfo("bosnia-herzegovina", "europe"),
                    new DownloadInfo("bulgaria", "europe"),
                    new DownloadInfo("croatia", "europe"),
                    new DownloadInfo("cyprus", "europe"),
                    new DownloadInfo("czech-republic", "europe"),
                    new DownloadInfo("denmark", "europe"),
                    new DownloadInfo("estonia", "europe"),
                    new DownloadInfo("faroe-islands", "europe"),
                    new DownloadInfo("finland", "europe"),
                    new DownloadInfo("france", "europe"),
                    new DownloadInfo("georgia", "europe"),
                    new DownloadInfo("germany", "europe"),
                    new DownloadInfo("great-britain", "europe"),
                    new DownloadInfo("greece", "europe"),
                    new DownloadInfo("guernsey-jersey", "europe"),
                    new DownloadInfo("hungary", "europe"),
                    new DownloadInfo("iceland", "europe"),
                    new DownloadInfo("ireland-and-northern-ireland", "europe"),
                    new DownloadInfo("isle-of-man", "europe"),
                    new DownloadInfo("italy", "europe"),
                    new DownloadInfo("kosovo", "europe"),
                    new DownloadInfo("latvia", "europe"),
                    new DownloadInfo("liechtenstein", "europe"),
                    new DownloadInfo("lithuania", "europe"),
                    new DownloadInfo("luxembourg", "europe"),
                    new DownloadInfo("macedonia", "europe"),
                    new DownloadInfo("malta", "europe"),
                    new DownloadInfo("moldova", "europe"),
                    new DownloadInfo("monaco", "europe"),
                    new DownloadInfo("montenegro", "europe"),
                    new DownloadInfo("netherlands", "europe"),
                    new DownloadInfo("norway", "europe"),
                    new DownloadInfo("poland", "europe"),
                    new DownloadInfo("portugal", "europe"),
                    new DownloadInfo("romania", "europe"),
                    new DownloadInfo("serbia", "europe"),
                    new DownloadInfo("slovakia", "europe"),
                    new DownloadInfo("slovenia", "europe"),
                    new DownloadInfo("spain", "europe"),
                    new DownloadInfo("sweden", "europe"),
                    new DownloadInfo("switzerland", "europe"),
                    new DownloadInfo("turkey", "europe"),
                    new DownloadInfo("ukraine", "europe"),

            },
            "North America", new DownloadInfo[]{
                    new DownloadInfo("greenland", "north-america"),
                    new DownloadInfo("mexico", "north-america"),
                    new DownloadInfo("alabama", "north-america/us"),
                    new DownloadInfo("alaska", "north-america/us"),
                    new DownloadInfo("arizona", "north-america/us"),
                    new DownloadInfo("arkansas", "north-america/us"),
                    new DownloadInfo("california", "north-america/us"),
                    new DownloadInfo("colorado", "north-america/us"),
                    new DownloadInfo("connecticut", "north-america/us"),
                    new DownloadInfo("delaware", "north-america/us"),
                    new DownloadInfo("district-of-columbia", "north-america/us"),
                    new DownloadInfo("florida", "north-america/us"),
                    new DownloadInfo("georgia", "north-america/us"),
                    new DownloadInfo("hawaii", "north-america/us"),
                    new DownloadInfo("idaho", "north-america/us"),
                    new DownloadInfo("illinois", "north-america/us"),
                    new DownloadInfo("indiana", "north-america/us"),
                    new DownloadInfo("iowa", "north-america/us"),
                    new DownloadInfo("kansas", "north-america/us"),
                    new DownloadInfo("kentucky", "north-america/us"),
                    new DownloadInfo("louisiana", "north-america/us"),
                    new DownloadInfo("maine", "north-america/us"),
                    new DownloadInfo("maryland", "north-america/us"),
                    new DownloadInfo("massachusetts", "north-america/us"),
                    new DownloadInfo("michigan", "north-america/us"),
                    new DownloadInfo("minnesota", "north-america/us"),
                    new DownloadInfo("mississippi", "north-america/us"),
                    new DownloadInfo("missouri", "north-america/us"),
                    new DownloadInfo("montana", "north-america/us"),
                    new DownloadInfo("nebraska", "north-america/us"),
                    new DownloadInfo("nevada", "north-america/us"),
                    new DownloadInfo("new-hampshire", "north-america/us"),
                    new DownloadInfo("new-jersey", "north-america/us"),
                    new DownloadInfo("new-mexico", "north-america/us"),
                    new DownloadInfo("new-york", "north-america/us"),
                    new DownloadInfo("north-carolina", "north-america/us"),
                    new DownloadInfo("north-dakota", "north-america/us"),
                    new DownloadInfo("ohio", "north-america/us"),
                    new DownloadInfo("oklahoma", "north-america/us"),
                    new DownloadInfo("oregon", "north-america/us"),
                    new DownloadInfo("pennsylvania", "north-america/us"),
                    new DownloadInfo("puerto-rico", "north-america/us"),
                    new DownloadInfo("rhode-island", "north-america/us"),
                    new DownloadInfo("south-carolina", "north-america/us"),
                    new DownloadInfo("south-dakota", "north-america/us"),
                    new DownloadInfo("tennessee", "north-america/us"),
                    new DownloadInfo("texas", "north-america/us"),
                    new DownloadInfo("us-virgin-islands", "north-america/us"),
                    new DownloadInfo("utah", "north-america/us"),
                    new DownloadInfo("vermont", "north-america/us"),
                    new DownloadInfo("virginia", "north-america/us"),
                    new DownloadInfo("washington", "north-america/us"),
                    new DownloadInfo("west-virginia", "north-america/us"),
                    new DownloadInfo("wisconsin", "north-america/us"),
                    new DownloadInfo("wyoming", "north-america/us"),
                    new DownloadInfo("alberta", "north-america/canada"),
                    new DownloadInfo("british-columbia", "north-america/canada"),
                    new DownloadInfo("manitoba", "north-america/canada"),
                    new DownloadInfo("new-brunswick", "north-america/canada"),
                    new DownloadInfo("newfoundland-and-labrador", "north-america/canada"),
                    new DownloadInfo("northwest-territories", "north-america/canada"),
                    new DownloadInfo("nova-scotia", "north-america/canada"),
                    new DownloadInfo("nunavut", "north-america/canada"),
                    new DownloadInfo("ontario", "north-america/canada"),
                    new DownloadInfo("prince-edward-island", "north-america/canada"),
                    new DownloadInfo("quebec", "north-america/canada"),
                    new DownloadInfo("saskatchewan", "north-america/canada"),
                    new DownloadInfo("yukon", "north-america/canada"),
            },
            "Asia", new DownloadInfo[]{
                    new DownloadInfo("afghanistan", "asia"),
                    new DownloadInfo("armenia", "asia"),
                    new DownloadInfo("azerbaijan", "asia"),
                    new DownloadInfo("bangladesh", "asia"),
                    new DownloadInfo("bhutan", "asia"),
                    new DownloadInfo("cambodia", "asia"),
                    new DownloadInfo("china", "asia"),
                    new DownloadInfo("east-timor", "asia"),
                    new DownloadInfo("gcc-states", "asia"),
                    new DownloadInfo("india", "asia"),
                    new DownloadInfo("indonesia", "asia"),
                    new DownloadInfo("iran", "asia"),
                    new DownloadInfo("iraq", "asia"),
                    new DownloadInfo("israel-and-palestine", "asia"),
                    new DownloadInfo("japan", "asia"),
                    new DownloadInfo("jordan", "asia"),
                    new DownloadInfo("kazakhstan", "asia"),
                    new DownloadInfo("kyrgyzstan", "asia"),
                    new DownloadInfo("laos", "asia"),
                    new DownloadInfo("lebanon", "asia"),
                    new DownloadInfo("malaysia-singapore-brunei", "asia"),
                    new DownloadInfo("maldives", "asia"),
                    new DownloadInfo("mongolia", "asia"),
                    new DownloadInfo("myanmar", "asia"),
                    new DownloadInfo("nepal", "asia"),
                    new DownloadInfo("north-korea", "asia"),
                    new DownloadInfo("pakistan", "asia"),
                    new DownloadInfo("philippines", "asia"),
                    new DownloadInfo("south-korea", "asia"),
                    new DownloadInfo("sri-lanka", "asia"),
                    new DownloadInfo("syria", "asia"),
                    new DownloadInfo("taiwan", "asia"),
                    new DownloadInfo("tajikistan", "asia"),
                    new DownloadInfo("thailand", "asia"),
                    new DownloadInfo("turkmenistan", "asia"),
                    new DownloadInfo("uzbekistan", "asia"),
                    new DownloadInfo("vietnam", "asia"),
                    new DownloadInfo("yemen", "asia"),
            },
            "Australia-Oceania", new DownloadInfo[]{
                    new DownloadInfo("american-oceania", "australia-oceania"),
                    new DownloadInfo("australia", "australia-oceania"),
                    new DownloadInfo("cook-islands", "australia-oceania"),
                    new DownloadInfo("fiji", "australia-oceania"),
                    new DownloadInfo("ile-de-clipperton", "australia-oceania"),
                    new DownloadInfo("kiribati", "australia-oceania"),
                    new DownloadInfo("marshall-islands", "australia-oceania"),
                    new DownloadInfo("micronesia", "australia-oceania"),
                    new DownloadInfo("nauru", "australia-oceania"),
                    new DownloadInfo("new-caledonia", "australia-oceania"),
                    new DownloadInfo("new-zealand", "australia-oceania"),
                    new DownloadInfo("niue", "australia-oceania"),
                    new DownloadInfo("palau", "australia-oceania"),
                    new DownloadInfo("papua-new-guinea", "australia-oceania"),
                    new DownloadInfo("pitcairn-islands", "australia-oceania"),
                    new DownloadInfo("polynesie-francaise", "australia-oceania"),
                    new DownloadInfo("samoa", "australia-oceania"),
                    new DownloadInfo("solomon-islands", "australia-oceania"),
                    new DownloadInfo("tokelau", "australia-oceania"),
                    new DownloadInfo("tonga", "australia-oceania"),
                    new DownloadInfo("tuvalu", "australia-oceania"),
                    new DownloadInfo("vanuatu", "australia-oceania"),
                    new DownloadInfo("wallis-et-futuna", "australia-oceania"),
            },
            "Central America", new DownloadInfo[]{
                    new DownloadInfo("bahamas", "central-america"),
                    new DownloadInfo("belize", "central-america"),
                    new DownloadInfo("costa-rica", "central-america"),
                    new DownloadInfo("cuba", "central-america"),
                    new DownloadInfo("el-salvador", "central-america"),
                    new DownloadInfo("guatemala", "central-america"),
                    new DownloadInfo("haiti-and-domrep", "central-america"),
                    new DownloadInfo("honduras", "central-america"),
                    new DownloadInfo("jamaica", "central-america"),
                    new DownloadInfo("nicaragua", "central-america"),
                    new DownloadInfo("panama", "central-america"),
            },
            "South America", new DownloadInfo[]{
                    new DownloadInfo("argentina", "south-america"),
                    new DownloadInfo("bolivia", "south-america"),
                    new DownloadInfo("brazil", "south-america"),
                    new DownloadInfo("chile", "south-america"),
                    new DownloadInfo("colombia", "south-america"),
                    new DownloadInfo("ecuador", "south-america"),
                    new DownloadInfo("guyana", "south-america"),
                    new DownloadInfo("paraguay", "south-america"),
                    new DownloadInfo("peru", "south-america"),
                    new DownloadInfo("suriname", "south-america"),
                    new DownloadInfo("uruguay", "south-america"),
                    new DownloadInfo("venezuela", "south-america"),
            }
            );

    static class MapIndex {
        public String group;
        public int index;

        public MapIndex(String group, int i) {
            this.group = group;
            this.index = i;
        }
    }

    DownloadInfo getMap(MapIndex index) {
        return allMaps.get(index.group)[index.index];
    }
    private MapIndex getMapIndex(String name) {
        for (String group : allMaps.keySet())
            for (int i = 0; i < allMaps.get(group).length; ++i)
                if (allMaps.get(group)[i].getTmpName().equals(name))
                    return new MapIndex(group, i);
        throw new RuntimeException("Bad map name: " + name);
    }

    ArrayAdapter mapDownloadsAdapter;

    void downloadMapsAction(String group) {
        LayoutInflater layoutInflater =
                (LayoutInflater)getBaseContext()
                        .getSystemService(LAYOUT_INFLATER_SERVICE);
        View popupView = layoutInflater.inflate(R.layout.categorypopup, null);
        final PopupWindow popupWindow = new PopupWindow(
                popupView,  WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);

        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        ListView listView = (ListView)popupView.findViewById(R.id.categories);
        DownloadManager.Query q = new DownloadManager.Query();
        q.setFilterByStatus(DownloadManager.STATUS_RUNNING);
        DownloadInfo[] chosenMaps = allMaps.get(group);
        for (int i = 0; i < chosenMaps.length; ++i) {
            if (chosenMaps[i].getDownloadedPath().exists()) {
                chosenMaps[i].setStatus(MapDownloadStatus.READY);
            }
        }
        DownloadManager downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        Cursor cursor = downloadManager.query(q);
        if (cursor.moveToFirst()) {
            do {
                String uri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_URI));
                String path = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                long id = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_ID));
                for (int i = 0; i < chosenMaps.length; ++i)
                    if (chosenMaps[i].url.equals(uri)) {
                        chosenMaps[i].downloadId = id;
                    }
            } while (cursor.moveToNext());
        }
        mapDownloadsAdapter = new ArrayAdapter<DownloadInfo>(MainActivity.this,
                R.layout.categoryelem, chosenMaps);
        listView.setAdapter(mapDownloadsAdapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                if (chosenMaps[i].isFetching()) {
                    downloadingDialog(chosenMaps[i].downloadId, chosenMaps[i]);
                } else
                if (chosenMaps[i].status == MapDownloadStatus.READY) {
                    multiMenu(Arrays.asList("Erase file"), Arrays.asList(new Runnable() {
                        @Override
                        public void run() {
                            confirmationDialog("Erase " + chosenMaps[i].name + "?", new Runnable() {
                                        @Override
                                        public void run() {
                                            chosenMaps[i].getDownloadedPath().delete();
                                            mapDownloadsAdapter.notifyDataSetChanged();
                                            popupWindow.dismiss();
                                            reloadLayers();
                                        }
                                    });
                        }
                    }));
                } else {
                    downloadMap(chosenMaps[i]);
                    mapDownloadsAdapter.notifyDataSetChanged();
                }
            }
        });
        popupWindow.showAsDropDown(mapView, 50, -30);
    }

    void confirmationDialog(String message, Runnable action) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setMessage(message)
                .setIcon(android.R.drawable.ic_dialog_alert);
        if (action == null)
                builder.setPositiveButton(android.R.string.yes, null);
        else
                builder.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        action.run();
                    }
                })
                .setNegativeButton(android.R.string.no, null);
        builder.show();
    }

    void multiMenu(List<String> choices, List<Runnable> actions) {
        View popupView = ((LayoutInflater)getBaseContext()
                .getSystemService(LAYOUT_INFLATER_SERVICE)).inflate(R.layout.categorypopup, null);
        final PopupWindow popupWindow = new PopupWindow(
                popupView, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        ListView listView = (ListView)popupView.findViewById(R.id.categories);
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(MainActivity.this,
                        R.layout.categoryelem, choices);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int button, long l) {
                actions.get(button).run();
                popupWindow.dismiss();
            }
        });
        popupWindow.showAsDropDown(mapView, 50, -30);
    }

    void downloadMap(DownloadInfo map) {
        DownloadManager downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(map.url));
        //Set whether this download may proceed over a roaming connection.
        request.setAllowedOverRoaming(false);
        //Set the title of this download, to be displayed in notifications (if enabled).
        request.setTitle("Offline map download (" + map.name + ")");
        //Set a description of this download, to be displayed in notifications (if enabled)
        request.setDescription("");
        //Set the local destination for the downloaded file to a path within the application's external files directory
        File target = map.getTmpPath();
        if (target.exists())
            target.delete();
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, map.getTmpName());
        map.downloadId = downloadManager.enqueue(request);;
        downloadingDialog(map.downloadId, map);
    }

    private BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0);
            AlertDialog dialog = downloadDialogs.get(downloadId);
            if (dialog != null) {
                dialog.hide();
                downloadDialogs.remove(downloadId);
            }
            Log.i(TAG,"Checking download status for id: " + downloadId);
            DownloadManager downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            Cursor cursor = downloadManager.query(new DownloadManager.Query().setFilterById(downloadId));
            if (cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_FAILED) {
                    Toast.makeText(MainActivity.this, "Download failed.", Toast.LENGTH_SHORT).show();
                    return;
                }
                Uri uri = Uri.parse(cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)));
                String path = uri.getPath();
                String fileExt = MimeTypeMap.getFileExtensionFromUrl(path);
                File file = new File(path);
                if (fileExt.equals("tmp")) {
                    MapIndex mapIndex = getMapIndex(file.getName());
                    DownloadInfo map = getMap(mapIndex);
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        file.renameTo(map.getDownloadedPath());
//                    Log.i(TAG, "Downloaded map:" + allMaps[mapIndex].name);
//                    Toast.makeText(MainActivity.this, "Download is ready: " + allMaps[mapIndex].name, Toast.LENGTH_SHORT).show();
                        map.setStatus(MapDownloadStatus.READY);
                        mapDownloadsAdapter.notifyDataSetChanged();
                        reloadLayers();
                    }
                } else if (fileExt.equals("gpx")) {
                    reloadLayers();
                    tracksMenu();
                }
            }
            cursor.close();
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra("level", 0);
            TextView view = (TextView) findViewById(R.id.batteryView);
            view.setText(Integer.toString(level) + "%");
        }
    };

    File getAssetFile(String path) {
        try {
            InputStream stream = getAssets().open(path);
            File tmp = File.createTempFile("world", "map");
            byte[] buffer = new byte[1024];
            int read;
            OutputStream out = new FileOutputStream(tmp);
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return tmp;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    static double getTrackLength(List<LatLong> points) {
        double ret = 0;
        for (int i = 1; i < points.size(); ++i)
            ret += points.get(i).vincentyDistance(points.get(i - 1));
        return ret;
    }

    class Track {
        Track(String name, File file, List<LatLong> points) {
            this.name = name;
            this.file = file;
            this.points = points;
            this.length = getTrackLength(points);
        }
        double length;
        String name;
        File file;
        List<LatLong> points;
    };

    List<Track> tracks;
    Track currentTrack;

    void reloadTracks() {
        tracks = new ArrayList<>();
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        for (File f : dir.listFiles()) {
            if (f.getName().endsWith(".gpx")) {
                Log.i(TAG, "Found GPX file: " + f.getAbsolutePath());
                try {
                    GPXData data = decodeGPX(f);
                    String name = data.name;
                    if (name == null)
                        name = f.getName().substring(0, f.getName().length() - 4);
                    tracks.add(new Track(name, f, data.points));
                } catch (Exception e) {
                    Log.e(TAG, e.toString());
                }
            }
        }
    }

    MapDataStore getDownloadedMaps() {
        MultiMapDataStore dataStore = new MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL);
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        for (File f : dir.listFiles()) {
            if (f.getName().endsWith(".map")) {
                Log.i(TAG, "Found map file: " + f.getAbsolutePath());
                MapFile file = new MapFile(f);
                file.restrictToZoomRange((byte) 6, Byte.MAX_VALUE);
                dataStore.addMapDataStore(file, false, false);
                if (Character.isDigit(f.getName().charAt(f.getName().length() - 5)))
                    f.delete();
            } else if (!f.getName().endsWith(".poi") && !f.getName().endsWith(".gpx")) {
                Log.i(TAG, "Removing unknown file: " + f.getAbsolutePath());
                f.delete();
            }
        }
        dataStore.addMapDataStore(new MapFile(getAssetFile("world.map")), false, false);
        return dataStore;
    }

    void reloadLayers() {
        this.tileCaches.get(0).purge();
        MapDataStore mapStore = getDownloadedMaps();
        TileRendererLayer tileRendererLayer = new TileRendererLayer(tileCaches.get(0), mapStore,
                mapView.getModel().mapViewPosition, false, false, false, AndroidGraphicFactory.INSTANCE)  {
            @Override
            public boolean onLongPress(LatLong tapLatLong, Point layerXY, Point tapXY) {
                popupMenu();
                return true;
            }
        };
        tileRendererLayer.setXmlRenderTheme(new CustomTheme("/assets/default.xml"));
        this.mapView.getLayerManager().getLayers().clear(true);
        this.mapView.getLayerManager().getLayers().add(tileRendererLayer);
        MapDataStoreLabelStore labelStore = new MapDataStoreLabelStore(mapStore, tileRendererLayer.getRenderThemeFuture(),
                tileRendererLayer.getTextScale(), tileRendererLayer.getDisplayModel(), AndroidGraphicFactory.INSTANCE);
        LabelLayer labelLayer = new ThreadedLabelLayer(AndroidGraphicFactory.INSTANCE, labelStore);
        mapView.getLayerManager().getLayers().add(labelLayer);
        this.mapView.getLayerManager().getLayers().add(this.myLocationOverlay);
        this.mapView.getLayerManager().getLayers().add(this.gpxLocationOverlay);
        if (currentTrack != null)
            addTrack(mapView.getLayerManager().getLayers(), currentTrack.points);
        reloadTracks();
    }

    private void createLayers() {
        Bitmap bitmap = new AndroidBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.ic_maps_indicator_current_position));
        Marker marker = new Marker(null, bitmap, 0, 0);
        // circle to show the location accuracy (optional)
        Circle circle = new Circle(null, 0,
                getPaint(AndroidGraphicFactory.INSTANCE.createColor(48, 0, 0, 255), 0, Style.FILL),
                getPaint(AndroidGraphicFactory.INSTANCE.createColor(160, 0, 0, 255), 2, Style.STROKE));
        this.myLocationOverlay = new MyLocationOverlay(marker, circle);
        Bitmap bitmap2 = new AndroidBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.ic_maps_indicator_track_position));
        Marker marker2 = new Marker(null, bitmap2, 0, 0);
        this.gpxLocationOverlay = new MyLocationOverlay(marker2, null);
        reloadLayers();
        // create the overlay
        boolean isWatch = getResources().getBoolean(R.bool.watch);
        if (isWatch)
            this.mapView.setBuiltInZoomControls(false);
        DefaultMapScaleBar scaleBar = new DefaultMapScaleBar(mapView.getModel().mapViewPosition,
                mapView.getModel().mapViewDimension,
                AndroidGraphicFactory.INSTANCE, mapView.getModel().displayModel);
        mapView.setMapScaleBar(scaleBar);
        scaleBar.setScaleBarPosition(MapScaleBar.ScaleBarPosition.BOTTOM_CENTER);
        scaleBar.setScaleBarMode(DefaultMapScaleBar.ScaleBarMode.SINGLE);
        scaleBar.setDistanceUnitAdapter(new MetricUnitAdapter());
        this.locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        this.locationManager.removeUpdates(this);
        mapView.addInputListener(new InputListener() {
            @Override
            public void onMoveEvent() {
                if (isLockedLocation())
                    lockedLocation = System.currentTimeMillis();
            }
            @Override
            public void onZoomEvent() {
            }
        });
        subscribeToLocation();
        //this.mapView.getModel().displayModel.setFilter(Filter.INVERT);
        this.mapView.getModel().displayModel.setBackgroundColor(0x000000);
    }

    void subscribeToLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.i(MainActivity.TAG, "No location permission");
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                Log.i(MainActivity.TAG, "Explaining");
                showExplanation("Permission Needed", "Rationale", Manifest.permission.ACCESS_FINE_LOCATION, REQUEST_PERMISSION_GPS_STATE);
            } else {
                requestPermission(Manifest.permission.ACCESS_FINE_LOCATION, REQUEST_PERMISSION_GPS_STATE);
                Log.i(MainActivity.TAG, "Requesting");
            }
        } else {
            Log.i(MainActivity.TAG, "Have location permission");
            for (String provider : this.locationManager.getProviders(true)) {
                if (LocationManager.GPS_PROVIDER.equals(provider)
                        || LocationManager.NETWORK_PROVIDER.equals(provider)) {
                    this.locationManager.requestLocationUpdates(provider, 0, 0, this);
                }
            }
        }
    }

    private void requestPermission(String permissionName, int permissionRequestCode) {
        ActivityCompat.requestPermissions(this,
                new String[]{permissionName}, permissionRequestCode);
    }

    private void showExplanation(String title,
                                 String message,
                                 final String permission,
                                 final int permissionRequestCode) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        requestPermission(permission, permissionRequestCode);
                    }
                });
        builder.create().show();
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case REQUEST_PERMISSION_GPS_STATE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    subscribeToLocation();
                break;
        }
    }

    private final int REQUEST_PERMISSION_GPS_STATE=1;

    static Paint createPaint(int color, int strokeWidth, Style style) {
        Paint paint = AndroidGraphicFactory.INSTANCE.createPaint();
        paint.setColor(color);
        paint.setStrokeWidth(strokeWidth);
        paint.setStyle(style);
        return paint;
    }

    protected void addTrack(Layers layers, List<LatLong> track) {
        Polyline polyline = new Polyline(createPaint(
                AndroidGraphicFactory.INSTANCE.createColor(Color.RED),
                (int) (4 * mapView.getModel().displayModel.getScaleFactor()),
                Style.STROKE), AndroidGraphicFactory.INSTANCE);
        List<LatLong> latLongs = new ArrayList<>();
        for (LatLong p : track)
            latLongs.add(p);
        polyline.setPoints(latLongs);
        layers.add(polyline);
    }

    class GPXData {
        List<LatLong> points;
        String name;

        public GPXData(List<LatLong> points, String name) {
            this.points = points;
            this.name = name;
        }
    }
    private GPXData decodeGPX(File file) throws IOException, SAXException, ParserConfigurationException {
        List<LatLong> list = new ArrayList<LatLong>();

        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
        Document document = documentBuilder.parse(file);
        Element elementRoot = document.getDocumentElement();
        NodeList nameList = elementRoot.getElementsByTagName("name");
        String name = null;
        if (nameList.getLength() > 0)
            name = nameList.item(0).getFirstChild().getNodeValue();
        NodeList nodelist_trkpt = elementRoot.getElementsByTagName("trkpt");

        for(int i = 0; i < nodelist_trkpt.getLength(); i++){
            Node node = nodelist_trkpt.item(i);
            NamedNodeMap attributes = node.getAttributes();

            String newLatitude = attributes.getNamedItem("lat").getTextContent();
            Double newLatitude_double = Double.parseDouble(newLatitude);

            String newLongitude = attributes.getNamedItem("lon").getTextContent();
            Double newLongitude_double = Double.parseDouble(newLongitude);
            list.add(new LatLong(newLatitude_double, newLongitude_double));
        }
        return new GPXData(list, name);
    }

    private void createTileCaches() {
        /*this.tileCaches.add(AndroidUtil.createTileCache(this, getPersistableId(),
                this.mapView.getModel().displayModel.getTileSize(), this.getScreenRatio(),
                this.mapView.getModel().frameBufferModel.getOverdrawFactor(), true));*/
        boolean persistent = true;

        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE))
                .getDefaultDisplay();
        final int hypot;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
            android.graphics.Point point = new android.graphics.Point();
            display.getSize(point);
            hypot = (int) Math.hypot(point.x, point.y);
        } else {
            hypot = (int) Math.hypot(display.getWidth(), display.getHeight());
        }
        this.tileCaches.add(AndroidUtil.createTileCache(this,
                getPersistableId(),
                this.mapView.getModel().displayModel.getTileSize(), hypot,
                hypot,
                this.mapView.getModel().frameBufferModel.getOverdrawFactor(), true));
    }

    protected IMapViewPosition initializePosition(IMapViewPosition mvp) {
        LatLong center = mvp.getCenter();
        if (center.equals(new LatLong(0, 0))) {
            mvp.setMapPosition(new MapPosition(new LatLong(61.814784429854, 54.514741140922), (byte) 17));
        }
        mvp.setZoomLevelMax((byte) 24);
        mvp.setZoomLevelMin((byte) 0);
        return mvp;
    }

    @Override
    protected void onPause() {
        mapView.getModel().save(this.preferencesFacade);
        this.preferencesFacade.save();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mapView.destroyAll();
        AndroidGraphicFactory.clearResourceMemoryCache();
        super.onDestroy();
    }

    private PreferencesFacade preferencesFacade;
    private XmlRenderThemeStyleMenu renderThemeStyleMenu;

    private MapView getMapView() {
        setContentView(R.layout.mapviewer);
        return (MapView) findViewById(R.id.mapView);
    }

    private void createMapViews() {
        mapView = getMapView();
        mapView.getModel().init(this.preferencesFacade);
        mapView.getMapScaleBar().setVisible(true);
    }

    private String getPersistableId() {
        return this.getClass().getSimpleName();
    }

    private String queryName(Uri uri) {
        File file = new File(uri.getPath());
        return file.getName();
    }

    private void tryImportingTrack(Uri data) {
        if (data != null) {
            String path = data.getEncodedPath();
            String name = queryName(data);
            Log.i(MainActivity.TAG, "Opening " + path);
            try {
                {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(data)));
                    File target = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
                    String line;
                    BufferedWriter out = new BufferedWriter(new FileWriter(target));
                    while ((line = reader.readLine()) != null) {
                        out.write(line);
                        out.newLine();
                    }
                    out.close();
                }
                Toast.makeText(MainActivity.this, "Imported " + name, Toast.LENGTH_SHORT).show();
                reloadLayers();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tryImportingTrack(getIntent().getData());
        AndroidGraphicFactory.createInstance(this);
        this.preferencesFacade = new AndroidPreferences(this.getSharedPreferences(getPersistableId(), MODE_PRIVATE));
        createMapViews();
        createTileCaches();
        createLayers();
        createControls();
        setTitle(getClass().getSimpleName());
        registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }
}
