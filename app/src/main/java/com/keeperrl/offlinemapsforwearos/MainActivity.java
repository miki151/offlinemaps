
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
import org.mapsforge.core.model.Dimension;
import org.mapsforge.core.model.LatLong;
        import org.mapsforge.core.model.MapPosition;
        import org.mapsforge.core.model.Point;
        import org.mapsforge.core.model.Tag;
import org.mapsforge.core.util.LatLongUtils;
import org.mapsforge.map.android.graphics.AndroidBitmap;
        import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
        import org.mapsforge.map.android.layers.MyLocationOverlay;
        import org.mapsforge.map.android.util.AndroidPreferences;
        import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.util.MapViewerTemplate;
import org.mapsforge.map.android.view.MapView;
        import org.mapsforge.map.datastore.MapDataStore;
        import org.mapsforge.map.datastore.MultiMapDataStore;
        import org.mapsforge.map.layer.GroupLayer;
        import org.mapsforge.map.layer.Layers;
        import org.mapsforge.map.layer.cache.TileCache;
        import org.mapsforge.map.layer.debug.TileCoordinatesLayer;
        import org.mapsforge.map.layer.debug.TileGridLayer;
        import org.mapsforge.map.layer.download.TileDownloadLayer;
        import org.mapsforge.map.layer.download.tilesource.OpenStreetMapMapnik;
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
import org.mapsforge.map.rendertheme.XmlRenderTheme;
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleMenu;
        import org.mapsforge.map.scalebar.DefaultMapScaleBar;
        import org.mapsforge.map.scalebar.MapScaleBar;
        import org.mapsforge.map.scalebar.MetricUnitAdapter;
        import org.mapsforge.map.view.InputListener;
        import org.mapsforge.poi.android.storage.AndroidPoiPersistenceManagerFactory;
        import org.mapsforge.poi.storage.PoiCategory;
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

/**
 * The simplest form of creating a map viewer based on the MapViewerTemplate.
 * It also demonstrates the use simplified cleanup operation at activity exit.
 */
public class MainActivity extends MapViewerTemplate {
    /**
     * This MapViewer uses the built-in default theme.
     *
     * @return the render theme to use
     */
    @Override
    protected XmlRenderTheme getRenderTheme() {
        return InternalRenderTheme.DEFAULT;
    }

    /**
     * This MapViewer uses the standard xml layout in the Samples app.
     */
    @Override
    protected int getLayoutId() {
        return R.layout.mapviewer;
    }

    /**
     * The id of the mapview inside the layout.
     *
     * @return the id of the MapView inside the layout.
     */
    @Override
    protected int getMapViewId() {
        return R.id.mapView;
    }

    /**
     * The name of the map file.
     *
     * @return map file name
     */
    @Override
    protected String getMapFileName() {
        return "berlin.map";
    }

    /**
     * Creates a simple tile renderer layer with the AndroidUtil helper.
     */
    @Override
    protected void createLayers() {
        TileRendererLayer tileRendererLayer = AndroidUtil.createTileRendererLayer(this.tileCaches.get(0),
                this.mapView.getModel().mapViewPosition, getMapFile(), getRenderTheme(), false, true, false);
        this.mapView.getLayerManager().getLayers().add(tileRendererLayer);
    }

    @Override
    protected void createMapViews() {
        super.createMapViews();
    }

    /**
     * Creates the tile cache with the AndroidUtil helper
     */
    @Override
    protected void createTileCaches() {
        this.tileCaches.add(AndroidUtil.createTileCache(this, getPersistableId(),
                this.mapView.getModel().displayModel.getTileSize(), this.getScreenRatio(),
                this.mapView.getModel().frameBufferModel.getOverdrawFactor()));
    }

    @Override
    protected MapPosition getInitialPosition() {
        int tileSize = this.mapView.getModel().displayModel.getTileSize();
        byte zoomLevel = LatLongUtils.zoomForBounds(new Dimension(tileSize * 4, tileSize * 4), getMapFile().boundingBox(), tileSize);
        return new MapPosition(getMapFile().boundingBox().getCenterPoint(), zoomLevel);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getClass().getSimpleName());
    }
}
