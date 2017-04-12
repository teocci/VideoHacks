package com.github.teocci.videohacks.camera;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import com.github.teocci.videohacks.encoder.TextureMovieEncoder;
import com.github.teocci.videohacks.gles.FullFrameRect;
import com.github.teocci.videohacks.gles.Texture2dProgram;
import com.github.teocci.videohacks.CameraHandler;
import com.github.teocci.videohacks.ui.CameraCaptureActivity;
import com.github.teocci.videohacks.ui.MainActivity;

import java.io.File;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Created by teocci.
 *
 * @author teocci@yandex.com on 2017/Apr/12
 *
 * Renderer object for our GLSurfaceView.
 * <p>
 * Do not call any methods here directly from another thread -- use the
 * GLSurfaceView#queueEvent() call.
 */

public class CameraSurfaceRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = false;

    private static final int RECORDING_OFF = 0;
    private static final int RECORDING_ON = 1;
    private static final int RECORDING_RESUMED = 2;

    private CameraHandler cameraHandler;
    private TextureMovieEncoder videoEncoder;
    private File outputFile;

    private FullFrameRect fullFrameScreen;

    private final float[] stMatrix = new float[16];
    private int textureId;

    private SurfaceTexture surfaceTexture;
    private boolean recordingEnabled;
    private int recordingStatus;
    private int frameCount;

    // width/height of the incoming camera preview frames
    private boolean incomingSizeUpdated;
    private int incomingWidth;
    private int incomingHeight;

    private int currentFilter;
    private int newFilter;


    /**
     * Constructs CameraSurfaceRenderer.
     * <p>
     * @param cameraHandler Handler for communicating with UI thread
     * @param movieEncoder video encoder object
     * @param outputFile output file for encoded video; forwarded to movieEncoder
     */
    public CameraSurfaceRenderer(CameraHandler cameraHandler,
                                 TextureMovieEncoder movieEncoder, File outputFile) {
        this.cameraHandler = cameraHandler;
        videoEncoder = movieEncoder;
        this.outputFile = outputFile;

        textureId = -1;

        recordingStatus = -1;
        recordingEnabled = false;
        frameCount = -1;

        incomingSizeUpdated = false;
        incomingWidth = incomingHeight = -1;

        // We could preserve the old filter mode, but currently not bothering.
        currentFilter = -1;
        newFilter = CameraCaptureActivity.FILTER_NONE;
    }

    /**
     * Notifies the renderer thread that the activity is pausing.
     * <p>
     * For best results, call this *after* disabling Camera preview.
     */
    public void notifyPausing() {
        if (surfaceTexture != null) {
            Log.d(TAG, "renderer pausing -- releasing SurfaceTexture");
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (fullFrameScreen != null) {
            fullFrameScreen.release(false);     // assume the GLSurfaceView EGL context is about
            fullFrameScreen = null;             //  to be destroyed
        }
        incomingWidth = incomingHeight = -1;
    }

    /**
     * Notifies the renderer that we want to stop or start recording.
     */
    public void changeRecordingState(boolean isRecording) {
        Log.d(TAG, "changeRecordingState: was " + recordingEnabled + " now " + isRecording);
        recordingEnabled = isRecording;
    }

    /**
     * Changes the filter that we're applying to the camera preview.
     */
    public void changeFilterMode(int filter) {
        newFilter = filter;
    }

    /**
     * Updates the filter program.
     */
    public void updateFilter() {
        Texture2dProgram.ProgramType programType;
        float[] kernel = null;
        float colorAdj = 0.0f;

        Log.d(TAG, "Updating filter to " + newFilter);
        switch (newFilter) {
            case CameraCaptureActivity.FILTER_NONE:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT;
                break;
            case CameraCaptureActivity.FILTER_BLACK_WHITE:
                // (In a previous version the TEXTURE_EXT_BW variant was enabled by a flag called
                // ROSE_COLORED_GLASSES, because the shader set the red channel to the B&W color
                // and green/blue to zero.)
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_BW;
                break;
            case CameraCaptureActivity.FILTER_BLUR:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                kernel = new float[] {
                        1f/16f, 2f/16f, 1f/16f,
                        2f/16f, 4f/16f, 2f/16f,
                        1f/16f, 2f/16f, 1f/16f };
                break;
            case CameraCaptureActivity.FILTER_SHARPEN:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                kernel = new float[] {
                        0f, -1f, 0f,
                        -1f, 5f, -1f,
                        0f, -1f, 0f };
                break;
            case CameraCaptureActivity.FILTER_EDGE_DETECT:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                kernel = new float[] {
                        -1f, -1f, -1f,
                        -1f, 8f, -1f,
                        -1f, -1f, -1f };
                break;
            case CameraCaptureActivity.FILTER_EMBOSS:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                kernel = new float[] {
                        2f, 0f, 0f,
                        0f, -1f, 0f,
                        0f, 0f, -1f };
                colorAdj = 0.5f;
                break;
            default:
                throw new RuntimeException("Unknown filter mode " + newFilter);
        }

        // Do we need a whole new program?  (We want to avoid doing this if we don't have
        // too -- compiling a program could be expensive.)
        if (programType != fullFrameScreen.getProgram().getProgramType()) {
            fullFrameScreen.changeProgram(new Texture2dProgram(programType));
            // If we created a new program, we need to initialize the texture width/height.
            incomingSizeUpdated = true;
        }

        // Update the filter kernel (if any).
        if (kernel != null) {
            fullFrameScreen.getProgram().setKernel(kernel, colorAdj);
        }

        currentFilter = newFilter;
    }

    /**
     * Records the size of the incoming camera preview frames.
     * <p>
     * It's not clear whether this is guaranteed to execute before or after onSurfaceCreated(),
     * so we assume it could go either way.  (Fortunately they both run on the same thread,
     * so we at least know that they won't execute concurrently.)
     */
    public void setCameraPreviewSize(int width, int height) {
        Log.d(TAG, "setCameraPreviewSize");
        incomingWidth = width;
        incomingHeight = height;
        incomingSizeUpdated = true;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated");

        // We're starting up or coming back.  Either way we've got a new EGLContext that will
        // need to be shared with the video encoder, so figure out if a recording is already
        // in progress.
        recordingEnabled = videoEncoder.isRecording();
        if (recordingEnabled) {
            recordingStatus = RECORDING_RESUMED;
        } else {
            recordingStatus = RECORDING_OFF;
        }

        // Set up the texture blitter that will be used for on-screen display.  This
        // is *not* applied to the recording, because that uses a separate shader.
        fullFrameScreen = new FullFrameRect(
                new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));

        textureId = fullFrameScreen.createTextureObject();

        // Create a SurfaceTexture, with an external texture, in this EGL context.  We don't
        // have a Looper in this thread -- GLSurfaceView doesn't create one -- so the frame
        // available messages will arrive on the main thread.
        surfaceTexture = new SurfaceTexture(textureId);

        // Tell the UI thread to enable the camera preview.
        cameraHandler.sendMessage(cameraHandler.obtainMessage(
                CameraHandler.MSG_SET_SURFACE_TEXTURE, surfaceTexture));
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        Log.d(TAG, "onSurfaceChanged " + width + "x" + height);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (VERBOSE) Log.d(TAG, "onDrawFrame tex=" + textureId);
        boolean showBox = false;

        // Latch the latest frame.  If there isn't anything new, we'll just re-use whatever
        // was there before.
        surfaceTexture.updateTexImage();

        // If the recording state is changing, take care of it here.  Ideally we wouldn't
        // be doing all this in onDrawFrame(), but the EGLContext sharing with GLSurfaceView
        // makes it hard to do elsewhere.
        if (recordingEnabled) {
            switch (recordingStatus) {
                case RECORDING_OFF:
                    Log.d(TAG, "START recording");
                    // start recording
                    videoEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
                            outputFile, 640, 480, 1000000, EGL14.eglGetCurrentContext()));
                    recordingStatus = RECORDING_ON;
                    break;
                case RECORDING_RESUMED:
                    Log.d(TAG, "RESUME recording");
                    videoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
                    recordingStatus = RECORDING_ON;
                    break;
                case RECORDING_ON:
                    // yay
                    break;
                default:
                    throw new RuntimeException("unknown status " + recordingStatus);
            }
        } else {
            switch (recordingStatus) {
                case RECORDING_ON:
                case RECORDING_RESUMED:
                    // stop recording
                    Log.d(TAG, "STOP recording");
                    videoEncoder.stopRecording();
                    recordingStatus = RECORDING_OFF;
                    break;
                case RECORDING_OFF:
                    // yay
                    break;
                default:
                    throw new RuntimeException("unknown status " + recordingStatus);
            }
        }

        // Set the video encoder's texture name.  We only need to do this once, but in the
        // current implementation it has to happen after the video encoder is started, so
        // we just do it here.
        //
        // TODO: be less lame.
        videoEncoder.setTextureId(textureId);

        // Tell the video encoder thread that a new frame is available.
        // This will be ignored if we're not actually recording.
        videoEncoder.frameAvailable(surfaceTexture);

        if (incomingWidth <= 0 || incomingHeight <= 0) {
            // Texture size isn't set yet.  This is only used for the filters, but to be
            // safe we can just skip drawing while we wait for the various races to resolve.
            // (This seems to happen if you toggle the screen off/on with power button.)
            Log.i(TAG, "Drawing before incoming texture size set; skipping");
            return;
        }
        // Update the filter, if necessary.
        if (currentFilter != newFilter) {
            updateFilter();
        }
        if (incomingSizeUpdated) {
            fullFrameScreen.getProgram().setTexSize(incomingWidth, incomingHeight);
            incomingSizeUpdated = false;
        }

        // Draw the video frame.
        surfaceTexture.getTransformMatrix(stMatrix);
        fullFrameScreen.drawFrame(textureId, stMatrix);

        // Draw a flashing box if we're recording.  This only appears on screen.
        showBox = (recordingStatus == RECORDING_ON);
        if (showBox && (++frameCount & 0x04) == 0) {
            drawBox();
        }
    }

    /**
     * Draws a red box in the corner.
     */
    private void drawBox() {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(0, 0, 100, 100);
        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }
}