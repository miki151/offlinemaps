
package com.keeperrl.offlinemapsforwearos;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.InputDeviceCompat;
import androidx.core.view.MotionEventCompat;

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
import org.mapsforge.poi.android.storage.AndroidPoiPersistenceManagerFactory;
import org.mapsforge.poi.storage.PoiCategoryFilter;
import org.mapsforge.poi.storage.PoiCategoryManager;
import org.mapsforge.poi.storage.PoiPersistenceManager;
import org.mapsforge.poi.storage.PointOfInterest;
import org.mapsforge.poi.storage.WhitelistPoiCategoryFilter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

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
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

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
        if (lockedLocation) {
            setLocation(location);
            button.setBackgroundColor(getResources().getColor(R.color.blue));
        } else
            button.setBackgroundColor(getResources().getColor(R.color.translucent));
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
                    Log.i(TAG, "Completed dist " + Double.toString(distCounter) + " " + Double.toString(p1.vincentyDistance(p)));
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

    private boolean lockedLocation = false;
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
                lockedLocation = true;
                if (lastLocation != null) {
                    locationButton.setBackgroundColor(getResources().getColor(R.color.blue));
                    setLocation(lastLocation);
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

    void tracksMenu() {
        if (tracks.isEmpty()) {
            confirmationDialog("To import a track, click on a GPX file and open it with this app.", null);
            return;
        }
        View popupView = ((LayoutInflater)getBaseContext()
                .getSystemService(LAYOUT_INFLATER_SERVICE)).inflate(R.layout.categorypopup, null);
        final PopupWindow popupWindow = new PopupWindow(
                popupView, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        ListView listView = (ListView)popupView.findViewById(R.id.categories);
        List<String> elems = new ArrayList<>();
        for (Track t : tracks)
            elems.add(t.name);
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(MainActivity.this,
                        R.layout.categoryelem, elems);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int button, long l) {
                multiMenu(Arrays.asList("Activate", "Erase"), Arrays.asList(new Runnable() {
                    @Override
                    public void run() {
                        currentTrack = tracks.get(button);
                        popupWindow.dismiss();
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
                                popupWindow.dismiss();
                            }
                        });
                    }
                }));
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
        String[] elems = new String[]{"Find address", "Show POIs", "Tracks", "Download maps", "Settings"};
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(MainActivity.this,
                        R.layout.categoryelem, elems);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                switch (i) {
                    case 0:
                        streetAction();
                        popupWindow.dismiss();
                        break;
                    case 1:
                        searchAction();
                        popupWindow.dismiss();
                        break;
                    case 2:
                        tracksMenu();
                        popupWindow.dismiss();
                        break;
                    case 3:
                        downloadMapsAction();
                        break;
                    case 4:
                        break;
                    default:
                        throw new RuntimeException("Unknown menu item: " + Integer.toString(i));

                }
            }
        });
        popupWindow.showAsDropDown(mapView, 50, -30);
    }

    private class DownloadInfo {
        public DownloadInfo(String name, String extension, String url, int size) {
            this.name = name;
            this.url = url;
            this.size = size;
            this.extension = extension;
        }

        public String getFileName() {
            return name + extension;
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

        String name;
        String extension;
        String url;
        int size;
    }

    final DownloadInfo[] allMaps = new DownloadInfo[]{
            new DownloadInfo("Sweden", ".map",
                    "http://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/europe/sweden.map", 100),
            new DownloadInfo("Sweden POI", ".poi",
                    "http://192.168.0.5/~michal/sweden.poi", 100),
            new DownloadInfo("Poland", ".map",
                    "http://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/europe/poland.map", 100),
            new DownloadInfo("Andorra", ".map",
                    "http://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/europe/andorra.map", 100)
    };


    private int getMapIndex(String name) {
        for (int i = 0; i < allMaps.length; ++i)
            if (allMaps[i].getTmpName().equals(name))
                return i;
        throw new RuntimeException("Bad map name: " + name);
    }

    StringBuffer[] labels = null;
    ArrayAdapter mapDownloadsAdapter;
    HashMap<Integer, Long> fetching;
    Set<Integer> ready;

    void downloadMapsAction() {
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
        labels = new StringBuffer[allMaps.length];
        for (int i = 0; i < allMaps.length; ++i)
            labels[i] = new StringBuffer(allMaps[i].name);
        ready = new HashSet<Integer>();
        fetching = new HashMap<Integer, Long>();
        for (int i = 0; i < allMaps.length; ++i) {
            if (allMaps[i].getDownloadedPath().exists()) {
                ready.add(i);
                labels[i].append("[Ready]");
            }
        }
        DownloadManager downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        Cursor cursor = downloadManager.query(q);
        if (cursor.moveToFirst()) {
            do {
                String uri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_URI));
                String path = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                long id = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_ID));
                for (int i = 0; i < allMaps.length; ++i)
                    if (allMaps[i].url.equals(uri)) {
                        Log.i(TAG, "Fetching " + allMaps[i].name + " to " + path);
                        labels[i].replace(0, 100000, allMaps[i].name + " [Fetching]");
                        fetching.put(i, id);
                    }
            } while (cursor.moveToNext());
        }
        mapDownloadsAdapter = new ArrayAdapter<StringBuffer>(MainActivity.this,
                R.layout.categoryelem, labels);
        listView.setAdapter(mapDownloadsAdapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                if (fetching.containsKey(i)) {
                    Intent intent = new Intent();
                    intent.setAction(DownloadManager.ACTION_VIEW_DOWNLOADS);
                    startActivity(intent);
                } else
                if (ready.contains(i)) {
                    multiMenu(Arrays.asList("Erase file"), Arrays.asList(new Runnable() {
                        @Override
                        public void run() {
                            confirmationDialog("Erase " + allMaps[i].name + "?", new Runnable() {
                                        @Override
                                        public void run() {
                                            allMaps[i].getDownloadedPath().delete();
                                            ready.remove(i);
                                            labels[i].replace(0, 100000, allMaps[i].name);
                                            mapDownloadsAdapter.notifyDataSetChanged();
                                            popupWindow.dismiss();
                                            reloadLayers();
                                        }
                                    });
                        }
                    }));
                } else {
                    downloadMap(i, allMaps[i].url);
                    labels[i].replace(0, 100000, allMaps[i].name + " [Fetching]");
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

    void downloadMap(int index, String url) {
        DownloadManager downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        //Set whether this download may proceed over a roaming connection.
        request.setAllowedOverRoaming(false);
        //Set the title of this download, to be displayed in notifications (if enabled).
        request.setTitle("Offline map download (" + allMaps[index].name + ")");
        //Set a description of this download, to be displayed in notifications (if enabled)
        request.setDescription("");
        //Set the local destination for the downloaded file to a path within the application's external files directory
        File target = allMaps[index].getTmpPath();
        if (target.exists())
            target.delete();
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, allMaps[index].getTmpName());
        downloadManager.enqueue(request);
        //Enqueue a new download and same the referenceId
        //downloadReference = downloadManager.enqueue(request);
    }

    private BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0);
            Log.i(TAG,"Checking download status for id: " + downloadId);
            DownloadManager downloadManager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            Cursor cursor = downloadManager.query(new DownloadManager.Query().setFilterById(downloadId));

            if (cursor.moveToFirst()) {
                String path = Uri.parse(cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))).getPath();
                File file = new File(path);
                int mapIndex = getMapIndex(file.getName());
                int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_FAILED) {
                    labels[mapIndex].replace(0, 100000, allMaps[mapIndex].name);
                    fetching.remove(mapIndex);
                    mapDownloadsAdapter.notifyDataSetChanged();
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    file.renameTo(allMaps[mapIndex].getDownloadedPath());
                    Log.i(TAG, "Downloaded map:" + allMaps[mapIndex].name);
                    Toast.makeText(MainActivity.this, "Download is ready: " + allMaps[mapIndex].name, Toast.LENGTH_SHORT).show();
                    labels[mapIndex].replace(0, 100000, allMaps[mapIndex].name + " [Ready]");
                    fetching.remove(mapIndex);
                    ready.add(mapIndex);
                    mapDownloadsAdapter.notifyDataSetChanged();
                    reloadLayers();
                }
            }
        }
    };

    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra("level", 0);
            TextView view = (TextView) findViewById(R.id.batteryView);
            view.setText(Integer.toString(level) + "%");
        }
    };

    View getPopupView() {
        LayoutInflater layoutInflater =
                (LayoutInflater)getBaseContext()
                        .getSystemService(LAYOUT_INFLATER_SERVICE);
        return layoutInflater.inflate(R.layout.categorypopup, null);
    }

    void searchAction() {
        if (groupLayer != null) {
            mapView.getLayerManager().getLayers().remove(groupLayer);
        }
        View popupView = getPopupView();
        final PopupWindow popupWindow = new PopupWindow(
                popupView, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        ListView listView = (ListView)popupView.findViewById(R.id.categories);
        String[] elems = new String[]{"Food", "Shop", "Health care", "Public Transport", "Sport", "Tourism", "Natural", "Historic", "Emergency", "Playgrounds", "Bad weather shelters", "All"};
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(MainActivity.this, R.layout.categoryelem, elems);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                mapView.getLayerManager().redrawLayers();
                // POI search
                new MainActivity.PoiSearchTask(MainActivity.this, elems[i]).execute(mapView.getBoundingBox());
                Log.i(TAG, "Selected " + elems[i] + " " + Integer.toString(i) + " " + Long.toString(l));
                popupWindow.dismiss();
            }
        });
        popupWindow.showAsDropDown(mapView, 50, -30);
    }

    class HouseNumberInfo {
        HouseNumberInfo(String number, LatLong location) {
            this.number = number;
            this.location = location;
        }
        String number;
        LatLong location;
    }

    int getHouseNumberInt(String number) {
        int ret = 0;
        String s = number.trim();
        while (!s.isEmpty() && Character.isDigit(s.charAt(0))) {
            ret = ret * 10 + Character.digit(s.charAt(0), 10);
            s = s.substring(1);
        }
        return ret;
    }

    List<HouseNumberInfo> getHouseNumbers(StreetInfo street, LatLong curLocation) {
        List<HouseNumberInfo> results = new ArrayList<>();
        for (SQLiteDatabase db : poiDatabases) {
            Cursor c = db.rawQuery("SELECT lat, lon, number from house_numbers where street_id=" + street.id + ";", null);
            int latIndex = c.getColumnIndex("lat");
            int lonIndex = c.getColumnIndex("lon");
            int numberIndex = c.getColumnIndex("number");
            if (!c.moveToFirst())
                continue;
            do {
                results.add(new HouseNumberInfo(c.getString(numberIndex), new LatLong(
                        c.getDouble(latIndex), c.getDouble(lonIndex))));
            } while (c.moveToNext());
        }
        results.sort(new Comparator<HouseNumberInfo>() {
            @Override
            public int compare(HouseNumberInfo t1, HouseNumberInfo t2) {
                double d1 = curLocation.sphericalDistance(t1.location);
                double d2 = curLocation.sphericalDistance(t2.location);
                return Double.compare(d1, d2);
            }
        });
        int lastSorted = 0;
        for (int i = 1; i <= results.size(); ++i)
            if (i == results.size() || (i - lastSorted > 1 && results.get(i - 1).location.sphericalDistance(results.get(i).location) > 5000)) {
                results.subList(lastSorted, i).sort(new Comparator<HouseNumberInfo>() {
                    @Override
                    public int compare(HouseNumberInfo t1, HouseNumberInfo t2) {
                        return getHouseNumberInt(t1.number) - getHouseNumberInt(t2.number);
                    }
                });
                lastSorted = i;
            }
        return results;
    }

    String distanceToString(double dist) {
        if (dist < 1000)
            return Long.toString(Math.round(dist)) + " m";
        return Long.toString(Math.round(dist / 1000)) + " km";
    }

    void addressSearch(StreetInfo street) {
        LatLong curLocation = mapView.getBoundingBox().getCenterPoint();
        List<HouseNumberInfo> numbers = getHouseNumbers(street, curLocation);
        View popupView = getPopupView();
        final PopupWindow popupWindow = new PopupWindow(
                popupView, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        ListView listView = (ListView)popupView.findViewById(R.id.categories);
        List<String> numbersLabels = new ArrayList<>();
        for (HouseNumberInfo elem : numbers)
            numbersLabels.add(elem.number + " " + distanceToString(elem.location.sphericalDistance(curLocation)));
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(MainActivity.this, R.layout.categoryelem, numbersLabels);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                groupLayer = new GroupLayer();
                Bitmap bitmap = new AndroidBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.marker_green));
                Marker marker = new MainActivity.MarkerImpl(numbers.get(i).location, bitmap, 0, -bitmap.getHeight() / 2,
                        street.name + " " + numbers.get(i).number);
                groupLayer.layers.add(marker);
                mapView.getLayerManager().getLayers().add(groupLayer);
                mapView.setCenter(numbers.get(i).location);
                mapView.getLayerManager().redrawLayers();
                lockedLocation = false;
                popupWindow.dismiss();
            }
        });
        popupWindow.showAsDropDown(mapView, 50, -30);
    }

    void addressSearchFuzzy(String streetname) {
        List<StreetInfo> streets = findStreets(streetname);
        View popupView = getPopupView();
        final PopupWindow popupWindow = new PopupWindow(
                popupView, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        ListView listView = (ListView)popupView.findViewById(R.id.categories);
        List<String> streetNames = new ArrayList<>();
        for (StreetInfo elem : streets)
            streetNames.add(elem.name);
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(MainActivity.this, R.layout.categoryelem, streetNames);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                addressSearch(streets.get(i));
                popupWindow.dismiss();
            }
        });
        popupWindow.showAsDropDown(mapView, 50, -30);
    }

    class StreetInfo {
        StreetInfo(String name, int id, double match) {
            this.name = name;
            this.id = id;
            this.match = match;
        }
        String name;
        int id;
        double match;
    }

    String normalize(String s) {
        s = Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFKD);
        s = s.replaceAll("\\p{M}", "");
        return s;
    }

    List<StreetInfo> findStreets(String street) {
        Log.i(TAG, "Searching for " + street);
        QGram similarity = new QGram(3);
        List<StreetInfo> results = new ArrayList<>();
        for (SQLiteDatabase db : poiDatabases) {
            Cursor c = db.rawQuery("SELECT name, rowid from streets;", null);
            int nameIndex = c.getColumnIndex("name");
            int idIndex = c.getColumnIndex("rowid");
            if (!c.moveToFirst())
                continue;
            do {
                String name = c.getString(nameIndex);
                results.add(new StreetInfo(name, c.getInt(idIndex), similarity.distance(normalize(street), normalize(name))));
            } while (c.moveToNext());
        }
        results.sort(new Comparator<StreetInfo>() {
            @Override
            public int compare(StreetInfo t1, StreetInfo t2) {
                if (t1.match < t2.match)
                    return -1;
                if (t1.match > t2.match)
                    return 1;
                return t1.id - t2.id;
            }
        });
        int cnt = 0;
        for (StreetInfo elem : results) {
            Log.i(TAG, "Result: " + elem.name + " " + Double.toString(elem.match));
        }
        return results;
    }

    void streetAction() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Title");

// Set up the input
        final EditText input = new EditText(this);
// Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

// Set up the buttons
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                addressSearchFuzzy(input.getText().toString());
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

    private GroupLayer groupLayer;
    private List<PoiPersistenceManager> persistenceManager = new ArrayList<>();
    private List<SQLiteDatabase> poiDatabases = new ArrayList<>();

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

    void reloadPois() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        for (File f : dir.listFiles()) {
            if (f.getName().endsWith(".poi")) {
                Log.i(TAG, "Found POI file: " + f.getAbsolutePath());
                persistenceManager.add(AndroidPoiPersistenceManagerFactory.getPoiPersistenceManager(f.getAbsolutePath()));
                poiDatabases.add(SQLiteDatabase.openDatabase(f,
                        new SQLiteDatabase.OpenParams.Builder().addOpenFlags(SQLiteDatabase.OPEN_READONLY).build()));
            }
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
                tracks.add(new Track(f.getName().substring(0, f.getName().length() - 4), f, decodeGPX(f)));
            }
        }
    }

    MapDataStore getDownloadedMaps() {
        MultiMapDataStore dataStore = new MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_FIRST);
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
        reloadPois();
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
                lockedLocation = false;
                if (lastLocation != null) {
                    ImageButton button = (ImageButton) findViewById(R.id.locationButton);
                    button.setBackgroundColor(getResources().getColor(R.color.translucent));
                }
            }
            @Override
            public void onZoomEvent() {
            }
        });
        for (String provider : this.locationManager.getProviders(true)) {
            if (LocationManager.GPS_PROVIDER.equals(provider)
                    || LocationManager.NETWORK_PROVIDER.equals(provider)) {
                this.locationManager.requestLocationUpdates(provider, 0, 0, this);
            }
        }
        //this.mapView.getModel().displayModel.setFilter(Filter.INVERT);
        this.mapView.getModel().displayModel.setBackgroundColor(0x000000);
    }

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

    private List<LatLong> decodeGPX(File file){
        List<LatLong> list = new ArrayList<LatLong>();

        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            Document document = documentBuilder.parse(file);
            Element elementRoot = document.getDocumentElement();

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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
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
        for (PoiPersistenceManager elem : persistenceManager)
            elem.close();
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
        Cursor returnCursor = getContentResolver().query(uri, null, null, null, null);
        assert returnCursor != null;
        int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        returnCursor.moveToFirst();
        String name = returnCursor.getString(nameIndex);
        returnCursor.close();
        return name;
    }

    private void tryImportingTrack(Intent intent) {
        Uri data = intent.getData();
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
        tryImportingTrack(getIntent());
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

    @Override
    protected void onNewIntent(Intent intent) {
        tryImportingTrack(intent);
    }

    private class PoiSearchTask extends android.os.AsyncTask<BoundingBox, Void, Collection<PointOfInterest>> {
        private final WeakReference<MainActivity> weakActivity;
        private final String category;

        private PoiSearchTask(MainActivity activity, String category) {
            this.weakActivity = new WeakReference<>(activity);
            this.category = category;
        }

        @Override
        protected Collection<PointOfInterest> doInBackground(BoundingBox... params) {
            // Search POI
            List<PointOfInterest> ret = new ArrayList<>();
            for (PoiPersistenceManager elem : persistenceManager)
                try {
                    PoiCategoryFilter categoryFilter = null;
                    if (!category.equals("All")) {
                        PoiCategoryManager categoryManager = elem.getCategoryManager();
                        categoryFilter = new WhitelistPoiCategoryFilter();
                        categoryFilter.addCategory(categoryManager.getPoiCategoryByTitle(category));
                    }
                    for (PointOfInterest poi : elem.findInRect(params[0], categoryFilter, null, Integer.MAX_VALUE))
                        ret.add(poi);
                } catch (Throwable t) {
                    Log.e(TAG, t.getMessage(), t);
                }
            return ret;
        }

        @Override
        protected void onPostExecute(Collection<PointOfInterest> pointOfInterests) {
            final MainActivity activity = weakActivity.get();
            if (activity == null) {
                return;
            }
            Toast.makeText(activity, category + ": " + (pointOfInterests != null ? pointOfInterests.size() : 0), Toast.LENGTH_SHORT).show();
            if (pointOfInterests == null) {
                return;
            }

            // Overlay POI
            groupLayer = new GroupLayer();
            Bitmap bitmap = new AndroidBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.marker_green));
            for (final PointOfInterest pointOfInterest : pointOfInterests) {
                Marker marker = new MainActivity.MarkerImpl(pointOfInterest.getLatLong(), bitmap, 0, -bitmap.getHeight() / 2, pointOfInterest.getCategory().toString());
                groupLayer.layers.add(marker);
            }
            mapView.getLayerManager().getLayers().add(groupLayer);
            mapView.getLayerManager().redrawLayers();
        }
    }

    private class MarkerImpl extends Marker {
        private final String name;

        private MarkerImpl(LatLong latLong, Bitmap bitmap, int horizontalOffset, int verticalOffset, String name) {
            super(latLong, bitmap, horizontalOffset, verticalOffset);
            this.name = name;
        }

        @Override
        public boolean onTap(LatLong tapLatLong, Point layerXY, Point tapXY) {
            if (name != null) {
                // GroupLayer does not have a position, layerXY is null
                layerXY = mapView.getMapViewProjection().toPixels(getPosition());
                if (contains(layerXY, tapXY)) {
                    Toast.makeText(MainActivity.this, name, Toast.LENGTH_SHORT).show();
                    //Log.i(TAG, name);
                    return true;
                }
            }
            return false;
        }
    }}
