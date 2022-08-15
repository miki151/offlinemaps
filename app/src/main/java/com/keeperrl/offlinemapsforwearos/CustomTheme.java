package com.keeperrl.offlinemapsforwearos;

import org.mapsforge.map.rendertheme.XmlRenderTheme;
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback;
import org.mapsforge.map.rendertheme.XmlThemeResourceProvider;

import java.io.InputStream;

public class CustomTheme implements XmlRenderTheme {

    private final String path;

    CustomTheme(String path) {
        this.path = path;
    }

    @Override
    public XmlRenderThemeMenuCallback getMenuCallback() {
        return null;
    }

    @Override
    public String getRelativePathPrefix() {
        return "/assets/";
    }

    @Override
    public InputStream getRenderThemeAsStream() {
        return getClass().getResourceAsStream(this.path);
    }

    @Override
    public XmlThemeResourceProvider getResourceProvider() {
        return null;
    }

    @Override
    public void setMenuCallback(XmlRenderThemeMenuCallback menuCallback) {
    }

    @Override
    public void setResourceProvider(XmlThemeResourceProvider resourceProvider) {
    }
}