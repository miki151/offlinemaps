
package com.keeperrl.offlinemapsforwearos;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.PopupWindow;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class MainActivity extends Activity implements LocationListener {

    protected MapView mapView;
    protected List<TileCache> tileCaches = new ArrayList<TileCache>();

    static private final String TAG = "OfflineMaps";

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
    private Location lastLocation = null;

    private void setLocation(Location location) {
        this.mapView.setCenter(new LatLong(location.getLatitude(), location.getLongitude()));
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
    }

    private boolean lockedLocation = false;
    private boolean displayOn = false;

    protected void createControls() {
        initializePosition(mapView.getModel().mapViewPosition);
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
        ImageButton searchButton = (ImageButton) findViewById(R.id.searchButton);
        searchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchAction();
            }
        });
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
        String[] elems = new String[]{"Find address", "Show POIs", "Download maps", "Settings"};
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
                        break;
                    case 1:
                        searchAction();
                        break;
                    case 2:
                        downloadMapsAction();
                        break;
                    case 3:
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
                    "http://keeperrl.com/~michal/sweden.poi", 100),
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
                    downloadedMapMenu(i);
                } else {
                    downloadMap(i, allMaps[i].url);
                    labels[i].replace(0, 100000, allMaps[i].name + " [Fetching]");
                    mapDownloadsAdapter.notifyDataSetChanged();
                }
            }
        });
        popupWindow.showAsDropDown(mapView, 50, -30);
    }

    void downloadedMapMenu(int mapIndex) {
        DownloadInfo map = allMaps[mapIndex];
        View popupView = ((LayoutInflater)getBaseContext()
                .getSystemService(LAYOUT_INFLATER_SERVICE)).inflate(R.layout.categorypopup, null);
        final PopupWindow popupWindow = new PopupWindow(
                popupView, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        ListView listView = (ListView)popupView.findViewById(R.id.categories);
        String[] elems = new String[]{"Erase map file"};
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(MainActivity.this,
                        R.layout.categoryelem, elems);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int button, long l) {
                map.getDownloadedPath().delete();
                ready.remove(mapIndex);
                labels[mapIndex].replace(0, 100000, allMaps[mapIndex].name);
                mapDownloadsAdapter.notifyDataSetChanged();
                popupWindow.dismiss();
                reloadLayers();
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

    void searchAction() {
        if (groupLayer != null) {
            mapView.getLayerManager().getLayers().remove(groupLayer);
        }
        LayoutInflater layoutInflater =
                (LayoutInflater)getBaseContext()
                        .getSystemService(LAYOUT_INFLATER_SERVICE);
        View popupView = layoutInflater.inflate(R.layout.categorypopup, null);
        final PopupWindow popupWindow = new PopupWindow(
                popupView, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);

        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        ListView listView = (ListView)popupView.findViewById(R.id.categories);
        String[] elems = new String[]{"Food", "Shop", "Health care", "Public Transport", "Sport", "Tourism", "Natural", "Historic", "Emergency", "Playgrounds", "All"};
        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(MainActivity.this,
                        R.layout.categoryelem, elems);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                mapView.getLayerManager().redrawLayers();
                // POI search
                new MainActivity.PoiSearchTask(MainActivity.this, elems[i], false).execute(mapView.getBoundingBox());
                Log.i(TAG, "Selected " + elems[i] + " " + Integer.toString(i) + " " + Long.toString(l));
                popupWindow.dismiss();
            }
        });
        popupWindow.showAsDropDown(mapView, 50, -30);
    }

    void chooseStreet(String street) {
        /*WhitelistPoiCategoryFilter filter = new WhitelistPoiCategoryFilter();
        try {
            filter.addCategory(persistenceManager.getCategoryManager().getPoiCategoryByTitle("Address"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Collection<PointOfInterest> results = persistenceManager.findInRect(mapView.getBoundingBox(), filter, null, Integer.MAX_VALUE);
        Set<String> streets = new HashSet<String>();
        for (PointOfInterest poi : results) {
            for (Tag t : poi.getTags())
                if (t.key.equals("addr:street"))
                    streets.add(t.value);
        }
        Log.i(TAG, Integer.toString(streets.size()) + " results");*/
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
                new MainActivity.PoiSearchTask(MainActivity.this, input.getText().toString(), true).execute(mapView.getBoundingBox());
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
            } else if (!f.getName().endsWith(".poi")) {
                Log.i(TAG, "Removing unknown file: " + f.getAbsolutePath());
                f.delete();
            }
        }
        dataStore.addMapDataStore(new MapFile(getAssetFile("mapsforge/world.map")), false, false);
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
        tileRendererLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT);
        this.mapView.getLayerManager().getLayers().clear(true);
        this.mapView.getLayerManager().getLayers().add(tileRendererLayer);
        MapDataStoreLabelStore labelStore = new MapDataStoreLabelStore(mapStore, tileRendererLayer.getRenderThemeFuture(),
                tileRendererLayer.getTextScale(), tileRendererLayer.getDisplayModel(), AndroidGraphicFactory.INSTANCE);
        LabelLayer labelLayer = new ThreadedLabelLayer(AndroidGraphicFactory.INSTANCE, labelStore);
        mapView.getLayerManager().getLayers().add(labelLayer);
        this.mapView.getLayerManager().getLayers().add(this.myLocationOverlay);
        reloadPois();
    }

    private void createLayers() {
        Bitmap bitmap = new AndroidBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.ic_maps_indicator_current_position));
        Marker marker = new Marker(null, bitmap, 0, 0);
        // circle to show the location accuracy (optional)
        Circle circle = new Circle(null, 0,
                getPaint(AndroidGraphicFactory.INSTANCE.createColor(48, 0, 0, 255), 0, Style.FILL),
                getPaint(AndroidGraphicFactory.INSTANCE.createColor(160, 0, 0, 255), 2, Style.STROKE));
        this.myLocationOverlay = new MyLocationOverlay(marker, circle);
        reloadLayers();
        // create the overlay
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
        try {
            List<LatLong> track = decodeGPX(getAssets().open("mapsforge/track.gpx"));
            addTrack(mapView.getLayerManager().getLayers(), track);
        } catch (Exception e) {
            throw new RuntimeException(e);
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

    private List<LatLong> decodeGPX(InputStream fileInputStream){
        List<LatLong> list = new ArrayList<LatLong>();

        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            Document document = documentBuilder.parse(fileInputStream);
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
            fileInputStream.close();
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
                this.mapView.getModel().frameBufferModel.getOverdrawFactor(), false));
    }

    protected IMapViewPosition initializePosition(IMapViewPosition mvp) {
        LatLong center = mvp.getCenter();
        if (center.equals(new LatLong(0, 0))) {
            mvp.setMapPosition(new MapPosition(new LatLong(61.814784429854, 54.514741140922), (byte) 8));
        }
        mvp.setZoomLevelMax((byte) 24);
        mvp.setZoomLevelMin((byte) 0);
        return mvp;
    }

    @Override
    protected void onDestroy() {
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidGraphicFactory.createInstance(this);
        this.preferencesFacade = new AndroidPreferences(this.getSharedPreferences(getPersistableId(), MODE_PRIVATE));
        createMapViews();
        createTileCaches();
        createLayers();
        createControls();
        setTitle(getClass().getSimpleName());
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        registerReceiver(downloadReceiver, filter);
    }

    private class PoiSearchTask extends android.os.AsyncTask<BoundingBox, Void, Collection<PointOfInterest>> {
        private final WeakReference<MainActivity> weakActivity;
        private final String category;
        private final boolean address;

        private PoiSearchTask(MainActivity activity, String category, boolean address) {
            this.weakActivity = new WeakReference<>(activity);
            this.category = category;
            this.address = address;
        }

        @Override
        protected Collection<PointOfInterest> doInBackground(BoundingBox... params) {
            if (address)
                chooseStreet(category);
            else {
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
            return null;
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
                Marker marker = new MainActivity.MarkerImpl(pointOfInterest.getLatLong(), bitmap, 0, -bitmap.getHeight() / 2, pointOfInterest);
                groupLayer.layers.add(marker);
            }
            mapView.getLayerManager().getLayers().add(groupLayer);
            mapView.getLayerManager().redrawLayers();
        }
    }

    private class MarkerImpl extends Marker {
        private final PointOfInterest pointOfInterest;

        private MarkerImpl(LatLong latLong, Bitmap bitmap, int horizontalOffset, int verticalOffset, PointOfInterest pointOfInterest) {
            super(latLong, bitmap, horizontalOffset, verticalOffset);
            this.pointOfInterest = pointOfInterest;
        }

        @Override
        public boolean onTap(LatLong tapLatLong, Point layerXY, Point tapXY) {
            // GroupLayer does not have a position, layerXY is null
            layerXY = mapView.getMapViewProjection().toPixels(getPosition());
            if (contains(layerXY, tapXY)) {
                Toast.makeText(MainActivity.this, pointOfInterest.getName(), Toast.LENGTH_SHORT).show();
                Log.i(TAG, pointOfInterest.toString());
                return true;
            }
            return false;
        }
    }}
