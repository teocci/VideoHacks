/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.teocci.videohacks.ui;

import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;

import com.github.teocci.videohacks.interfaces.SurfaceTextureReceiver;
import com.github.teocci.videohacks.utils.AspectFrameLayout;
import com.github.teocci.videohacks.camera.CameraSurfaceRenderer;
import com.github.teocci.videohacks.camera.CameraUtils;
import com.android.videohacks.R;
import com.github.teocci.videohacks.encoder.TextureMovieEncoder;
import com.github.teocci.videohacks.CameraHandler;

import java.io.File;
import java.io.IOException;

/**
 * Shows the camera preview on screen while simultaneously recording it to a .mp4 file.
 * <p>
 * Every time we receive a frame from the camera, we need to:
 * <ul>
 * <li>Render the frame to the SurfaceView, on GLSurfaceView's renderer thread.
 * <li>Render the frame to the mediacodec's input surface, on the encoder thread, if
 * recording is enabled.
 * </ul>
 * <p>
 * At any given time there are four things in motion:
 * <ol>
 * <li>The UI thread, embodied by this Activity.  We must respect -- or work around -- the
 * app lifecycle changes.  In particular, we need to release and reacquire the Camera
 * so that, if the user switches away from us, we're not preventing another app from
 * using the camera.
 * <li>The Camera, which will busily generate preview frames once we hand it a
 * SurfaceTexture.  We'll get notifications on the main UI thread unless we define a
 * Looper on the thread where the SurfaceTexture is created (the GLSurfaceView renderer
 * thread).
 * <li>The video encoder thread, embodied by TextureMovieEncoder.  This needs to share
 * the Camera preview external texture with the GLSurfaceView renderer, which means the
 * EGLContext in this thread must be created with a reference to the renderer thread's
 * context in hand.
 * <li>The GLSurfaceView renderer thread, embodied by CameraSurfaceRenderer.  The thread
 * is created for us by GLSurfaceView.  We don't get callbacks for pause/resume or
 * thread startup/shutdown, though we could generate messages from the Activity for most
 * of these things.  The EGLContext created on this thread must be shared with the
 * video encoder, and must be used to create a SurfaceTexture that is used by the
 * Camera.  As the creator of the SurfaceTexture, it must also be the one to call
 * updateTexImage().  The renderer thread is thus at the center of a multi-thread nexus,
 * which is a bit awkward since it's the thread we have the least control over.
 * </ol>
 * <p>
 * GLSurfaceView is fairly painful here.  Ideally we'd create the video encoder, create
 * an EGLContext for it, and pass that into GLSurfaceView to share.  The API doesn't allow
 * this, so we have to do it the other way around.  When GLSurfaceView gets torn down
 * (say, because we rotated the device), the EGLContext gets tossed, which means that when
 * it comes back we have to re-create the EGLContext used by the video encoder.  (And, no,
 * the "preserve EGLContext on pause" feature doesn't help.)
 * <p>
 * We could simplify this quite a bit by using TextureView instead of GLSurfaceView, but that
 * comes with a performance hit.  We could also have the renderer thread drive the video
 * encoder directly, allowing them to work from a single EGLContext, but it's useful to
 * decouple the operations, and it's generally unwise to perform disk I/O on the thread that
 * renders your UI.
 * <p>
 * We want to access Camera from the UI thread (setup, teardown) and the renderer thread
 * (configure SurfaceTexture, start preview), but the API says you can only access the object
 * from a single thread.  So we need to pick one thread to own it, and the other thread has to
 * access it remotely.  Some things are simpler if we let the renderer thread manage it,
 * but we'd really like to be sure that Camera is released before we leave onPause(), which
 * means we need to make a synchronous call from the UI thread into the renderer thread, which
 * we don't really have full control over.  It's less scary to have the UI thread own Camera
 * and have the renderer call back into the UI thread through the standard Handler mechanism.
 * <p>
 * (The <a href="http://developer.android.com/training/camera/cameradirect.html#TaskOpenCamera">
 * camera docs</a> recommend accessing the camera from a non-UI thread to avoid bogging the
 * UI thread down.  Since the GLSurfaceView-managed renderer thread isn't a great choice,
 * we might want to create a dedicated camera thread.  Not doing that here.)
 * <p>
 * With three threads working simultaneously (plus Camera causing periodic events as frames
 * arrive) we have to be very careful when communicating state changes.  In general we want
 * to send a message to the thread, rather than directly accessing state in the object.
 * <p>
 * &nbsp;
 * <p>
 * To exercise the API a bit, the video encoder is required to survive Activity restarts.  In the
 * current implementation it stops recording but doesn't stop time from advancing, so you'll
 * see a pause in the video.  (We could adjust the timer to make it seamless, or output a
 * "paused" message and hold on that in the recording, or leave the Camera running so it
 * continues to generate preview frames while the Activity is paused.)  The video encoder object
 * is managed as a static property of the Activity.
 */
public class CameraCaptureActivity extends Activity
        implements SurfaceTexture.OnFrameAvailableListener, OnItemSelectedListener, SurfaceTextureReceiver
{
    private static final String TAG = CameraCaptureActivity.class.getSimpleName();

    private static final boolean VERBOSE = false;

    // Camera filters; must match up with cameraFilterNames in strings.xml
    public static final int FILTER_NONE = 0;
    public static final int FILTER_BLACK_WHITE = 1;
    public static final int FILTER_BLUR = 2;
    public static final int FILTER_SHARPEN = 3;
    public static final int FILTER_EDGE_DETECT = 4;
    public static final int FILTER_EMBOSS = 5;

    private GLSurfaceView glView;
    private CameraSurfaceRenderer cameraRenderer;
    private Camera camera;
    private CameraHandler cameraHandler;
    private boolean recordingEnabled;      // controls button state

    private int cameraPreviewWidth, cameraPreviewHeight;

    // this is static so it survives activity restarts
    private static TextureMovieEncoder sVideoEncoder = new TextureMovieEncoder();

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_capture);

        String filepath = Environment.getExternalStorageDirectory().getPath();
        File outputFile = new File(filepath + "/" + "camera-test.mp4");
        TextView fileText = (TextView) findViewById(R.id.cameraOutputFile_text);
        fileText.setText(outputFile.toString());

        Spinner spinner = (Spinner) findViewById(R.id.cameraFilter_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.cameraFilterNames, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner.
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);

        // Define a handler that receives camera-control messages from other threads.  All calls
        // to Camera must be made on the same thread.  Note we create this before the renderer
        // thread, so we know the fully-constructed object will be visible.
        cameraHandler = new CameraHandler(this);

        recordingEnabled = sVideoEncoder.isRecording();

        // Configure the GLSurfaceView.  This will start the Renderer thread, with an
        // appropriate EGL context.
        glView = (GLSurfaceView) findViewById(R.id.cameraPreview_surfaceView);
        glView.setEGLContextClientVersion(2);     // select GLES 2.0
        cameraRenderer = new CameraSurfaceRenderer(cameraHandler, sVideoEncoder, outputFile);
        glView.setRenderer(cameraRenderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        Log.d(TAG, "onCreate complete: " + this);
    }

    @Override
    protected void onResume()
    {
        Log.d(TAG, "onResume -- acquiring camera");
        super.onResume();
        updateControls();
        openCamera(1280, 720);      // updates cameraPreviewWidth/Height

        // Set the preview aspect ratio.
        AspectFrameLayout layout = (AspectFrameLayout) findViewById(R.id.cameraPreview_afl);
        layout.setAspectRatio((double) cameraPreviewWidth / cameraPreviewHeight);

        glView.onResume();
        glView.queueEvent(new Runnable()
        {
            @Override
            public void run()
            {
                cameraRenderer.setCameraPreviewSize(cameraPreviewWidth, cameraPreviewHeight);
            }
        });
        Log.d(TAG, "onResume complete: " + this);
    }

    @Override
    protected void onPause()
    {
        Log.d(TAG, "onPause -- releasing camera");
        super.onPause();
        releaseCamera();
        glView.queueEvent(new Runnable()
        {
            @Override
            public void run()
            {
                // Tell the renderer that it's about to be paused so it can clean up.
                cameraRenderer.notifyPausing();
            }
        });
        glView.onPause();
        Log.d(TAG, "onPause complete");
    }

    @Override
    protected void onDestroy()
    {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        cameraHandler.invalidateHandler();     // paranoia
    }

    // spinner selected
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id)
    {
        Spinner spinner = (Spinner) parent;
        final int filterNum = spinner.getSelectedItemPosition();

        Log.d(TAG, "onItemSelected: " + filterNum);
        glView.queueEvent(new Runnable()
        {
            @Override
            public void run()
            {
                // notify the renderer that we want to change the encoder's state
                cameraRenderer.changeFilterMode(filterNum);
            }
        });
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {}

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     * <p>
     * Sets cameraPreviewWidth and cameraPreviewHeight to the actual width/height of the preview.
     */
    private void openCamera(int desiredWidth, int desiredHeight)
    {
        if (camera != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                camera = Camera.open(i);
                break;
            }
        }
        if (camera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
            camera = Camera.open();    // opens first back-facing camera
        }
        if (camera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = camera.getParameters();

        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(true);

        // leave the frame rate set to default
        camera.setParameters(parms);

        int[] fpsRange = new int[2];
        Camera.Size mCameraPreviewSize = parms.getPreviewSize();
        parms.getPreviewFpsRange(fpsRange);
        String previewFacts = mCameraPreviewSize.width + "x" + mCameraPreviewSize.height;
        if (fpsRange[0] == fpsRange[1]) {
            previewFacts += " @" + (fpsRange[0] / 1000.0) + "fps";
        } else {
            previewFacts += " @[" + (fpsRange[0] / 1000.0) +
                    " - " + (fpsRange[1] / 1000.0) + "] fps";
        }
        TextView text = (TextView) findViewById(R.id.cameraParams_text);
        text.setText(previewFacts);

        cameraPreviewWidth = mCameraPreviewSize.width;
        cameraPreviewHeight = mCameraPreviewSize.height;
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private void releaseCamera()
    {
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    /**
     * onClick handler for "record" button.
     */
    public void clickToggleRecording(@SuppressWarnings("unused") View unused)
    {
        recordingEnabled = !recordingEnabled;
        glView.queueEvent(new Runnable()
        {
            @Override
            public void run()
            {
                // notify the renderer that we want to change the encoder's state
                cameraRenderer.changeRecordingState(recordingEnabled);
            }
        });
        updateControls();
    }

//    /**
//     * onClick handler for "rebind" checkbox.
//     */
//    public void clickRebindCheckbox(View unused) {
//        CheckBox cb = (CheckBox) findViewById(R.id.rebindHack_checkbox);
//        TextureRender.sWorkAroundContextProblem = cb.isChecked();
//    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     */
    private void updateControls()
    {
        Button toggleRelease = (Button) findViewById(R.id.toggleRecording_button);
        int id = recordingEnabled ?
                R.string.toggleRecordingOff : R.string.toggleRecordingOn;
        toggleRelease.setText(id);

        //CheckBox cb = (CheckBox) findViewById(R.id.rebindHack_checkbox);
        //cb.setChecked(TextureRender.sWorkAroundContextProblem);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st)
    {
        // The SurfaceTexture uses this to signal the availability of a new frame.  The
        // thread that "owns" the external texture associated with the SurfaceTexture (which,
        // by virtue of the context being shared, *should* be either one) needs to call
        // updateTexImage() to latch the buffer.
        //
        // Once the buffer is latched, the GLSurfaceView thread can signal the encoder thread.
        // This feels backward -- we want recording to be prioritized over rendering -- but
        // since recording is only enabled some of the time it's easier to do it this way.
        //
        // Since GLSurfaceView doesn't establish a Looper, this will *probably* execute on
        // the main UI thread.  Fortunately, requestRender() can be called from any thread,
        // so it doesn't really matter.
        if (VERBOSE) Log.d(TAG, "ST onFrameAvailable");
        glView.requestRender();
    }


    /**
     * Connects the SurfaceTexture to the Camera preview output, and starts the preview.
     */
    @Override
    public void onSetSurfaceTexture(SurfaceTexture surfaceTexture)
    {
        surfaceTexture.setOnFrameAvailableListener(this);
        try {
            camera.setPreviewTexture(surfaceTexture);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        camera.startPreview();
    }
}


