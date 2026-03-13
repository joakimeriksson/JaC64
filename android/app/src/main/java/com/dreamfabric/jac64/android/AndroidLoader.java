package com.dreamfabric.jac64.android;

import android.content.Context;
import android.content.res.AssetManager;

import com.dreamfabric.jac64.Loader;

import java.io.InputStream;

/**
 * Android-specific resource loader.
 * Loads ROM files and other resources from the Android assets folder.
 */
public class AndroidLoader extends Loader {

    private final AssetManager assetManager;

    public AndroidLoader(Context context) {
        this.assetManager = context.getAssets();
    }

    @Override
    public InputStream getResourceStream(String resource) {
        try {
            // Strip leading slash if present (assets paths don't use leading slash)
            if (resource.startsWith("/")) {
                resource = resource.substring(1);
            }
            return assetManager.open(resource);
        } catch (Exception e) {
            System.out.println("AndroidLoader: Failed to load resource: " + resource);
            e.printStackTrace();
        }
        return null;
    }
}
