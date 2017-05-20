package com.victorsg.actioncam;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.prefs.Preferences;

public class Camera2VideoImage extends AppCompatActivity implements MediaRecorder.OnInfoListener{

    //Needed for user settings
    private int PREF_RECORD_LIMIT;
    private int PREF_MAX_FILES;

    //Needed for permission checks
    private static final int REQUEST_PERMISSION_CAMERA_CODE = 0;
    private static final int REQUEST_PERMISSION_WRITE_EXTERNAL_STORAGE_CODE = 0;
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

    //File and storage related setup
    private File mVideoFolder;
    private String mVideoFileName;

    //Record image button and settings button, make it change on click
    private ImageButton mRecordImageButton;
    private ImageButton mSettingsImageButton;
    private boolean mIsRecording = false;

    //We create a camera device here
    private CameraDevice mCameraDevice;
    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            //Return camera device
            mCameraDevice = camera;
            //The if statement is for if it's the first installation of the file
            if(mIsRecording){
                setIsRecording();
            } else {
                startPreview();
            }

            //Toast.makeText(getApplicationContext(), "Camera connection made!", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            //Clean up camera device resources
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            //Clean up camera device resources
            camera.close();
            mCameraDevice = null;
        }
    };//end mCameraDevice.StateCallback

    //Background thread so that it does not take resources from the UI task
    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;

    //Start and stop the background threads
    private void startBackgroundThread() {
        mBackgroundHandlerThread = new HandlerThread("Camera2VideoImage");
        mBackgroundHandlerThread.start();
        //Set up background thread, we can push camera2 tasks onto this handler
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }//end startBackgroundThread

    private void stopBackgroundThread() {
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
    //Media recorder size
    private Size mVideoSize;
    //Our media recorder
    private MediaRecorder mMediaRecorder;
    //Chronometer to see our record time
    private Chronometer mChronometerRecordTime;
    //Need to get the total rotation for the media size
    private int mTotalRotation;
    //Show preview through capture request
    private CaptureRequest.Builder mCaptureRequestBuilder;

    //Make camera so that it adjust to the orientation of the screen rotation
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation) {

        int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        //convert to a real world orientation
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        return (sensorOrientation + deviceOrientation + 360) % 360;
    }

    //Helper class comparisons of different resolutions for preview
    private static class CompareSizeByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() /
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera2_video_image);
        loadpreferences();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_PERMISSION_WRITE_EXTERNAL_STORAGE_CODE);
        }
        createVideoFolder();
        checkPrefFolderOverload();

        mMediaRecorder = new MediaRecorder();
        mChronometerRecordTime = (Chronometer) findViewById(R.id.chronometer_recordTime);

        mTextureView = (TextureView) findViewById(R.id.textureView);
        mRecordImageButton = (ImageButton) findViewById(R.id.btnVideoOnline);
        mSettingsImageButton = (ImageButton) findViewById(R.id.btnAppSettings);

        //When record image button is clicked we will change the button to busy
        mRecordImageButton.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v){
                if(mIsRecording){
                    int numberOfFiles = getNumberOfFiles();
                    if (numberOfFiles > PREF_MAX_FILES) deleteOldestFile();
                    mChronometerRecordTime.stop();
                    mChronometerRecordTime.setVisibility(View.INVISIBLE);
                    mIsRecording = false;
                    mRecordImageButton.setImageResource(R.mipmap.btn_video_online);
                    mMediaRecorder.stop();
                    mMediaRecorder.reset();
                    startPreview();
                } else{
                    checkWriteStoragePermission();
                }
            }
        });//end mRecordImageButton.setOnClickListener

        //When settings is clicked stop any recordings and go to settings
        mSettingsImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mIsRecording){
                    int numberOfFiles = getNumberOfFiles();
                    if (numberOfFiles < PREF_MAX_FILES) deleteOldestFile();
                    mChronometerRecordTime.stop();
                    mChronometerRecordTime.setVisibility(View.INVISIBLE);
                    mIsRecording = false;
                    mRecordImageButton.setImageResource(R.mipmap.btn_video_online);
                    mMediaRecorder.stop();
                    mMediaRecorder.reset();
                    startPreview();
                }
                Intent intent = new Intent(getApplicationContext(), AppPreferences.class);
                startActivity(intent);
            }
        });//end mSettingsImageButton.setOnClickListener

    }//end onCreate


    //We expect texture view is unavailable when app first start
    @Override
    protected void onResume() {
        super.onResume();

        //Start the thread
        startBackgroundThread();

        if (mTextureView.isAvailable()) {
            //Set up camera here as well, since texture view is ready we can get w and h
            setupCamera(mTextureView.getWidth(), mTextureView.getHeight());
            //Connect camera once it has been setup
            connectCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }//end onResume

    //Determine what happens on permission grant result
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        //Camera permission
        if (requestCode == REQUEST_PERMISSION_CAMERA_CODE) {
            //grant results will hold info whether if permission has been granted or not
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(getApplicationContext(), "Application needs the camera to function!",
                        Toast.LENGTH_SHORT).show();
                if(ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)){
                    ActivityCompat.requestPermissions(this, new String[] {android.Manifest.permission.CAMERA},
                            REQUEST_PERMISSION_CAMERA_CODE);
                }
            }// end if
        }//end if camera permission

        //Write external storage permission
        else if (requestCode == REQUEST_PERMISSION_WRITE_EXTERNAL_STORAGE_CODE){
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED){
                Toast.makeText(this,
                        "Application needs to be able to save videos to work", Toast.LENGTH_SHORT).show();
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQUEST_PERMISSION_WRITE_EXTERNAL_STORAGE_CODE);
                } else {
                    Toast.makeText(this,
                            "Permission for some features were not granted, app may not work properly!", Toast.LENGTH_SHORT).show();
                }

            }
        }//end if external storage permission
    }//end onRequestPermissionsResult

    //free up camera when we switch app
    @Override
    protected void onPause() {
        closeCamera();

        //Stop the thread
        stopBackgroundThread();
        super.onPause();

    }//end onPause

    //This method will make the app immersive fullscreen...
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();
        if (hasFocus) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE                       //Transition to be stable
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY          //Have it sticky
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN         //remove artifacts
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION    //remove artifacts
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );
        }
    }//end onWindowFocusChanged

    //This will create our camera
    private void setupCamera(int width, int height) {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        //We can get a list of camera ids we want to get the rear camera

        //Try catch is needed cause we are making a call into the camera device
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                //Skip the front camera and get only the rear
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                //CameraCharacteristics will also will have the map of all the different resolutions
                StreamConfigurationMap map =
                        cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                //Call the orientation to identify portrait vs landscape mode
                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                mTotalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation);
                boolean swapRotation = mTotalRotation == 90 || mTotalRotation == 270;
                //Original width and height swapped if swapRotation
                int rotatedWidth = width;
                int rotatedHeight = height;
                if (swapRotation) {
                    rotatedWidth = height;
                    rotatedHeight = width;
                }

                //Setup preview display and media recorder size
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth, rotatedHeight);
                mVideoSize = chooseOptimalSize(map.getOutputSizes(MediaRecorder.class), rotatedWidth, rotatedHeight);

                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }//end setupCamera

    private void connectCamera() {
        //need instance of camera object, from camera manager
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        //Need permissions to be granted to use the camera
        try {

            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    //If permissions for camera was not granted
                    Toast.makeText(this, "This app needs access to camera", Toast.LENGTH_SHORT).show();
                    requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQUEST_PERMISSION_CAMERA_CODE);
                    return;
                }
                cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
            } else{
                Toast.makeText(this, "Please update your device to Marshmellow or later", Toast.LENGTH_SHORT).show();
                cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }//end connectCamera

    private void startRecord(){
        try {
            setupMediaRecorder();
            //The block of code below is currently exprimental
            //http://stackoverflow.com/questions/3227122/mediarecorder-setmaxdurationint-timer-what-happens-when-timer-expires
            //mMediaRecorder.setMaxDuration(MAX_RECORDING_TIME);
            //mMediaRecorder.setOnInfoListener(this);


            //Same as start preview, user needs to see what is being recorded
            SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(),mPreviewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);
            Surface recordSurface = mMediaRecorder.getSurface();

            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            mCaptureRequestBuilder.addTarget(previewSurface);
            mCaptureRequestBuilder.addTarget(recordSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, recordSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                session.setRepeatingRequest(
                                        mCaptureRequestBuilder.build(), null, null );
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {

                        }
                    }, null);

        } catch (Exception e) {
            e.printStackTrace();
        }

    }//end startRecord


    private void startPreview(){
        //Provide surface with texture view, by converting the texture view to surface view
        SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        //preview size should have been already setup through setup camera
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(),mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        //Initialize the camera capture request builder using a template preview
        try {
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            //Repeat regularly as a video, we are also not doing anything with this process yet
                            try {
                                session.setRepeatingRequest(mCaptureRequestBuilder.build(), null,
                                        mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(getApplicationContext(), "Camera preview setup failed",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }//end startPreview

    //free up camera resources
    private void closeCamera(){
        if(mCameraDevice != null){
            mCameraDevice.close();
            mCameraDevice = null;
        }
    };//end closeCamera


    private void setIsRecording(){
    /*
    *This begins the recording process:
    * Sets mIsRecording is true, turn the record button to busy, setup file names, start media recorder
    * and chronometer
    */
        mIsRecording = true;
        mRecordImageButton.setImageResource(R.mipmap.btn_video_busy);
        try {
            createVideoFileName();
        } catch (IOException e) {
            e.printStackTrace();
        }
        //Based on user input
        mMediaRecorder.setMaxDuration(PREF_RECORD_LIMIT);
        mMediaRecorder.setOnInfoListener(this);
        startRecord();
        mMediaRecorder.start();
        mChronometerRecordTime.setBase(SystemClock.elapsedRealtime());
        mChronometerRecordTime.setVisibility(View.VISIBLE);
        mChronometerRecordTime.start();
    }//end setIsRecording

    private void endIsRecording(){
    /*
    *This ends the recording process:
    *
    */
        mChronometerRecordTime.stop();
        mChronometerRecordTime.setVisibility(View.INVISIBLE);
        mIsRecording = false;
        mRecordImageButton.setImageResource(R.mipmap.btn_video_online);
        mMediaRecorder.stop();
        mMediaRecorder.reset();
        startPreview();
    }//end endIsRecording


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

    //Create Folders
    private void createVideoFolder(){
        //We will store it in public movies directory inside our own app folder
        File movieFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        mVideoFolder = new File(movieFile, "ActionCamVideos");
        //Check just if this folder exists
        if(!mVideoFolder.exists()){
            mVideoFolder.mkdirs();      //mkdirs also create parent folders if they aren't already
        }
    }

    //Create file names
    private File createVideoFileName() throws IOException{
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prepend = "VIDEO_" + timestamp + "_";
        File videoFile = File.createTempFile(prepend, ".mp4", mVideoFolder);
        mVideoFileName = videoFile.getAbsolutePath();
        return videoFile;
    }

    //Permissions for writing to external file, and support lower ver than marshmellow?
    private void checkWriteStoragePermission(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED){
                //Permission is granted, do what needs to be done
                setIsRecording();
            } else{
                //First time and no permission granted
                Toast.makeText(this, "App needs permission to save videos", Toast.LENGTH_SHORT).show();
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_PERMISSION_WRITE_EXTERNAL_STORAGE_CODE);
            }
        } else{
            //will always run if the version is less than marshmellow
            setIsRecording();
        }
    }//end checkWriteStoragePermission

    //Set up the media recorder
    private void setupMediaRecorder() throws IOException{
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setOutputFile(mVideoFileName);
        mMediaRecorder.setVideoEncodingBitRate(1000000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setOrientationHint(mTotalRotation);
        mMediaRecorder.prepare();
    }

    //Logic for the dash cam all falls in here
    @Override
    public void onInfo(MediaRecorder mr, int what, int extra) {
        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
            //Once max duration we check number of videos in folder
            int numberOfFiles = getNumberOfFiles();
            if (numberOfFiles < PREF_MAX_FILES){
                keepRecording();
            } else {
                deleteOldestFile();
                keepRecording();
            }

            /*
            mChronometerRecordTime.stop();
            mChronometerRecordTime.setVisibility(View.INVISIBLE);
            mIsRecording = false;
            mRecordImageButton.setImageResource(R.mipmap.btn_video_online);
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            startPreview();
             */

        }
    }//end onInfo

    private int getNumberOfFiles(){
        File dir = mVideoFolder;
        int numOfFiles = mVideoFolder.listFiles().length;
        return numOfFiles;
    }

    //Logic for deleting oldest file
    private void deleteOldestFile(){
        File dir = mVideoFolder;
        File [] files = dir.listFiles();
        if (files == null || files.length == 0){
            return;
        }
        File oldestModifiedFile = files[0];
        //If modified date is greater then we now that it's older
        for (int i = 1; i < files.length; i++) {
            if (oldestModifiedFile.lastModified() > files[i].lastModified()) {
                oldestModifiedFile = files[i];
            }
        }

        oldestModifiedFile.delete();
    }//end deleteOldestFile

    //Logic for keep recording
    private void keepRecording(){
        //Stop the current recording, and restart but don't need to start the preview
        mChronometerRecordTime.stop();
        mMediaRecorder.stop();
        mMediaRecorder.reset();

        try {
            createVideoFileName();
        } catch (IOException e) {
            e.printStackTrace();
        }
        //Based on user input
        mMediaRecorder.setMaxDuration(PREF_RECORD_LIMIT);
        mMediaRecorder.setOnInfoListener(this);
        startRecord();
        mMediaRecorder.start();
        mChronometerRecordTime.setBase(SystemClock.elapsedRealtime());
        mChronometerRecordTime.start();
    }

    //This is where we get the settings!
    private void loadpreferences(){
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        PREF_RECORD_LIMIT = Integer.parseInt(sharedPreferences.getString("record_time","180"));
        PREF_RECORD_LIMIT *= 1000;

        PREF_MAX_FILES = Integer.parseInt(sharedPreferences.getString("max_files", "10"));

    }//end loadpreferences

    private void checkPrefFolderOverload(){
        File dir = mVideoFolder;
        if (PREF_MAX_FILES < dir.listFiles().length){
            //Delete all files in folder
            File [] files = dir.listFiles();
            if (files != null || files.length != 0){
                for (int i = 1; i < files.length; i++) {
                    files[i].delete();
                }//for
            }
        }//if
    }//end checkPrefFolderOverload

}
