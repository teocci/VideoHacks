package com.github.teocci.videohacks;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.github.teocci.videohacks.interfaces.SurfaceTextureReceiver;

import java.lang.ref.WeakReference;

/**
 * Handles camera operation requests from other threads.  Necessary because the Camera
 * must only be accessed from one thread.
 * <p>
 * The object is created on the UI thread, and all handlers run there.  Messages are
 * sent from other threads, using sendMessage().
 * <p>
 * Created by teocci.
 *
 * @author teocci@yandex.com on 2017/Apr/12
 */
public class CameraHandler extends Handler
{
    private static final String TAG = CameraHandler.class.getSimpleName();

    private SurfaceTextureReceiver surfaceTextureReceiver;

    public static final int MSG_SET_SURFACE_TEXTURE = 0;

    // Weak reference to the Activity; only access this from the UI thread.
    private WeakReference<Activity> weakReference;

    public CameraHandler(Activity activity)
    {
        weakReference = new WeakReference<>(activity);
        surfaceTextureReceiver = null;
    }

    public void setSurfaceTextureReceiver(SurfaceTextureReceiver listener)
    {
        surfaceTextureReceiver = listener;
    }

    /**
     * Drop the reference to the activity.  Useful as a paranoid measure to ensure that
     * attempts to access a stale Activity through a handler are caught.
     */
    public void invalidateHandler()
    {
        weakReference.clear();
    }

    @Override  // runs on UI thread
    public void handleMessage(Message inputMessage)
    {
        int what = inputMessage.what;
        Log.d(TAG, "CameraHandler [" + this + "]: what=" + what);

        Activity activity = weakReference.get();
        if (activity == null) {
            Log.w(TAG, "CameraHandler.handleMessage: activity is null");
            return;
        }

        switch (what) {
            case MSG_SET_SURFACE_TEXTURE:
                if (surfaceTextureReceiver != null)
                    surfaceTextureReceiver.onSetSurfaceTexture((SurfaceTexture) inputMessage.obj);
                break;
            default:
                throw new RuntimeException("unknown msg " + what);
        }
    }
}
