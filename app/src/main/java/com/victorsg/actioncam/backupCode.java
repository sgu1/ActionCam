package com.victorsg.actioncam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class backupCode extends AppCompatActivity {

    //Needed for permission check
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 0;
    //Get the texture view
    //Texture view is a bit slow so use listener
    private TextureView mTextureView;
    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //When texture view is ready - width and height is provided by phone

            //Set up the camera with the width and height
            setupCamera(width, height);
            //Connect camera once setup
            connectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };//end TextureView.SurfaceTextureListener

    //We create a camera device here
    private CameraDevice mCameraDevice;
    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            //Return camera device
            mCameraDevice = camera;
            //For testing purposes
            Toast.makeText(getApplicationContext(), "Camera connection made!", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            //Clean up camera device resources
            camera.close();
            mCameraDevice=null;
        }

        @Override
        public void onError(CameraDevice camera,  int error) {
            //Clean up camera device resources
            camera.close();
            mCameraDevice=null;
        }
    };//end mCameraDevice.StateCallback

    //Background thread so that it does not take resources from the UI task
    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;
    //Start and stop the background threads
    private void startBackgroundThread(){
        mBackgroundHandlerThread = new HandlerThread("Camera2VideoImage");
        mBackgroundHandlerThread.start();
        //Set up background thread, we can push camera2 tasks onto this handler
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }//end startBackgroundThread
    private void stopBackgroundThread(){
        mBackgroundHandlerThread.quitSafely();
        //Stop anything else from interrupting it
        try {
            mBackgroundHandlerThread.join();
            mBackgroundHandlerThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }//end stopBackgroundThread

    //Camera ID identifies which camera app is using ex.front vs rear
    private String mCameraId;
    //Preview Size member will identify the size for preview
    private Size mPreviewSize;

    //Make camera so that it adjust to the orientation of the screen rotation
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }
    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation){

        int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        //convert to a real world orientation
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        return (sensorOrientation + deviceOrientation + 360) %360;
    }

    //Helper class comparisons of different resolutions for preview
    private static class CompareSizeByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs){
            return Long.signum((long)lhs.getWidth() * lhs.getHeight() /
                    (long)rhs.getWidth() * rhs.getHeight());
        }
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera2_video_image);

        mTextureView = (TextureView) findViewById(R.id.textureView);
    }//end onCreate

    //We expect texture view is unavailable when app first start
    @Override
    protected void onResume(){
        super.onResume();

        //Start the thread
        startBackgroundThread();

        if(mTextureView.isAvailable()){
            //Set up camera here as well, since texture view is ready we can get w and h
            setupCamera(mTextureView.getWidth(), mTextureView.getHeight());
            //Connect camera once it has been setup
            connectCamera();
        }else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }//end onResume

    //Determine what happens on permission result for camera
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == CAMERA_PERMISSION_REQUEST_CODE){
            //grant results will hold info whether if permission has been granted or not, grant[0] for first permission
            if(grantResults[0] != PackageManager.PERMISSION_GRANTED){
                Toast.makeText(getApplicationContext(),"Application needs the camera to function!",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }//end onRequestPermissionsResult

    //free up camera when we switch app
    @Override
    protected void  onPause(){
        closeCamera();

        //Stop the thread
        stopBackgroundThread();
        super.onPause();

    }//end onPause

    //This method will make the app immersive fullscreen...
    @Override
    public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();
        if(hasFocus){
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE               //Transition to be stable
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY          //Have it sticky
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN         //remove artifacts
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION    //remove artifacts
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );
        }
    }//end onWindowFocusChanged

    //This will create our camera
    private void setupCamera(int width, int height){
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        //We can get a list of camera ids we want to get the rear camera

        //Try catch is needed cause we are making a call into the camera device
        try {
            for (String cameraId : cameraManager.getCameraIdList()){
                //Skip the front camera and get only the rear
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT){
                    continue;
                }
                //CameraCharacteristics will also will have the map of all the different resolutions
                StreamConfigurationMap map =
                        cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                //Call the orientation to identify portrait vs landscape mode
                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                int totalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation);
                boolean swapRotation = totalRotation == 90 || totalRotation == 270;
                //Original width and height swapped if swapRotation
                int rotatedWidth = width;
                int rotatedHeight = height;
                if (swapRotation) {
                    rotatedWidth = height;
                    rotatedHeight = width;
                }

                //Setup preview display size
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth, rotatedHeight);

                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }//end setupCamera

    private void connectCamera(){
        //need instance of camera object, from camera manager
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            //Need permissions check for marshmellow or later
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
                if(ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED) {
                    //If permission has already been granted. First time on marshmellow or later
                    cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
                } else{
                    //If permission hasn't been granted.
                    if(shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)){
                        Toast.makeText(this, "This app needs access to camera", Toast.LENGTH_SHORT).show();
                        requestPermissions(new String[] {android.Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
                    }
                }
            } else{
                cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }//end connectCamera

    //free up camera resources
    private void closeCamera(){
        if(mCameraDevice != null){
            mCameraDevice.close();
            mCameraDevice = null;
        }
    };//end closeCamera

    private static Size chooseOptimalSize(Size[] choices, int width, int height){
        //List for our acceptable values, is the resolution big enought for our display?
        List<Size>bigEnough = new ArrayList<Size>();
        //Traverse through the list supplied by the sensor that contains the preview resolutions
        for (Size option : choices){
            //Check for aspect ratio is correct,
            // and the value from preview sensor is big enough for texture view

            //Hopefully we have matching ratios here
            if(option.getHeight() == option.getWidth() * height / width &&
                    option.getWidth() >= width && option.getHeight() >= height){
                bigEnough.add(option);
            }
        }//for
        if (bigEnough.size() > 0){
            return Collections.min(bigEnough, new CompareSizeByArea());
        } else {
            return choices[0];
        }

    }//end chooseOptimalSize

}
