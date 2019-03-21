package org.reactnative.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.CamcorderProfile;
import android.media.MediaActionSound;
import android.os.Build;
import android.support.v4.content.ContextCompat;
import android.util.SparseArray;
import android.view.View;
import android.os.AsyncTask;
import com.facebook.react.bridge.*;
import com.facebook.react.uimanager.ThemedReactContext;
import com.google.android.cameraview.CameraView;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.Landmark;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import org.reactnative.camera.tasks.*;
import org.reactnative.camera.utils.ImageDimensions;
import org.reactnative.camera.utils.RNFileUtils;
import org.reactnative.facedetector.RNFaceDetector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// CANVAS ADDED BY IAN
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import java.util.Random;
import android.app.Activity;
import android.os.Handler;
import android.util.Log;
import java.lang.Math;

// MEDIAPLAYER
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.content.res.AssetFileDescriptor;
import android.content.Context;
import java.lang.reflect.Method;
import com.facebook.react.uimanager.events.RCTEventEmitter;

public class RNCameraView extends CameraView implements LifecycleEventListener, FaceDetectorAsyncTaskDelegate, PictureSavedDelegate {
  private ThemedReactContext mThemedReactContext;
  private Queue<Promise> mPictureTakenPromises = new ConcurrentLinkedQueue<>();
  private Map<Promise, ReadableMap> mPictureTakenOptions = new ConcurrentHashMap<>();
  private Map<Promise, File> mPictureTakenDirectories = new ConcurrentHashMap<>();
  private Promise mVideoRecordedPromise;
  private Boolean mPlaySoundOnCapture = false;

  private boolean mIsPaused = false;
  private boolean mIsNew = true;

  // Concurrency lock for scanners to avoid flooding the runtime
  public volatile boolean faceDetectorTaskLock = false;

  private RNFaceDetector mFaceDetector;
  private boolean mShouldDetectFaces = false;
  private int mFaceDetectorMode = RNFaceDetector.FAST_MODE;
  private int mFaceDetectionLandmarks = RNFaceDetector.NO_LANDMARKS;
  private int mFaceDetectionClassifications = RNFaceDetector.NO_CLASSIFICATIONS;

  // ADDED BY ME
  private Face mFace;
  private Paint mFacePositionPaint;
  private Paint mSensitivityPaint;
  private Paint mBoxPaint;
  private ImageDimensions dimensions;
  private ExecutorService mExecutorService = Executors.newFixedThreadPool(1);
  private static final float FACE_POSITION_RADIUS = 5;

  private int mAudioStartLatency = 0;
  private int mCounter = 0;
  private boolean isOnSettingsPage = false;
  private float scaleX = 1;
  private float scaleY = 1;
  private final long mInputDelay = 120;
  private float mEyeOpenProbability = .9f;
  private float mUserSetEyesOpenProbability = .3f;
  private long mInterval = 1000;
  private boolean eyeIsDetected = false;
  private boolean mEyesOpen = true;
  private boolean mShouldCount = true;
  private boolean mEyesClosed = false;
  private boolean mSetFaceDetectionEnable = true;
  private String mAlarmSoundName;
  Map<Double, MediaPlayer> playerPool = new HashMap<>();

  private String mEyeToDetect = "both eyes";
  private MediaPlayer mMediaPlayer; // CREATE MEDIAPLAYER BELOW

  public RNCameraView(ThemedReactContext themedReactContext) {
    super(themedReactContext, true);
    mThemedReactContext = themedReactContext;
    themedReactContext.addLifecycleEventListener(this);

    mFacePositionPaint = new Paint();
    mFacePositionPaint.setColor(Color.RED);

    mBoxPaint = new Paint();
    mBoxPaint.setColor(Color.RED);
    mBoxPaint.setStrokeWidth(4f * scaleY);
    mBoxPaint.setStyle(Paint.Style.STROKE);

    mSensitivityPaint = new Paint();
    mFacePositionPaint.setColor(Color.WHITE);
    mFacePositionPaint.setTextSize(40f * scaleY);

    try {
      AudioManager am = (AudioManager) themedReactContext.getSystemService(Context.AUDIO_SERVICE);
      Method m = am.getClass().getMethod("getOutputLatency", int.class);
      mAudioStartLatency = (Integer) m.invoke(am, AudioManager.STREAM_MUSIC);
    } catch(Exception e) {
      mAudioStartLatency = 0;
    }

    addCallback(new Callback() {
      @Override
      public void onCameraOpened(CameraView cameraView) {
        RNCameraViewHelper.emitCameraReadyEvent(cameraView);
      }

      @Override
      public void onMountError(CameraView cameraView) {
        RNCameraViewHelper.emitMountErrorEvent(cameraView, "Camera view threw an error - component could not be rendered.");
      }

      @Override
      public void onPictureTaken(CameraView cameraView, final byte[] data) {
        Promise promise = mPictureTakenPromises.poll();
        ReadableMap options = mPictureTakenOptions.remove(promise);
        if (options.hasKey("fastMode") && options.getBoolean("fastMode")) {
            promise.resolve(null);
        }
        final File cacheDirectory = mPictureTakenDirectories.remove(promise);
        if(Build.VERSION.SDK_INT >= 11/*HONEYCOMB*/) {
          new ResolveTakenPictureAsyncTask(data, promise, options, cacheDirectory, RNCameraView.this)
                  .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
          new ResolveTakenPictureAsyncTask(data, promise, options, cacheDirectory, RNCameraView.this)
                  .execute();
        }
        RNCameraViewHelper.emitPictureTakenEvent(cameraView);
      }

      @Override
      public void onVideoRecorded(CameraView cameraView, String path) {
        if (mVideoRecordedPromise != null) {
          if (path != null) {
            WritableMap result = Arguments.createMap();
            result.putString("uri", RNFileUtils.uriFromFile(new File(path)).toString());
            mVideoRecordedPromise.resolve(result);
          } else {
            mVideoRecordedPromise.reject("E_RECORDING", "Couldn't stop recording - there is none in progress");
          }
          mVideoRecordedPromise = null;
        }
      }

      @Override
      public void onFramePreview(CameraView cameraView, byte[] data, int width, int height, int rotation) {
        int correctRotation = RNCameraViewHelper.getCorrectCameraRotation(rotation, getFacing());
        boolean willCallFaceTask = mShouldDetectFaces && mSetFaceDetectionEnable && !faceDetectorTaskLock && cameraView instanceof FaceDetectorAsyncTaskDelegate;

        if (!willCallFaceTask)
          return;

        if (data.length < (1.5 * width * height))
          return;

        long executionStart = System.currentTimeMillis();
        if (willCallFaceTask) {
          faceDetectorTaskLock = true;
          FaceDetectorAsyncTaskDelegate delegate = (FaceDetectorAsyncTaskDelegate) cameraView;
          new FaceDetectorAsyncTask(delegate, mFaceDetector, data, width, height, correctRotation, executionStart).execute();
        }
      }
    });
  }

  @Override
  public void update(Face face, int sourceWidth, int sourceHeight, int sourceRotation, long executionStart) {
    if(mMediaPlayer == null)
      return;

    invalidate();
    mFace = face;
    dimensions = new ImageDimensions(sourceWidth, sourceHeight, sourceRotation, getFacing());

    scaleX = getWidth() / (dimensions.getWidth());
    scaleY = getHeight() / (dimensions.getHeight());

    if(mEyeToDetect.equals("left eye"))
      mEyeOpenProbability = mFace.getIsLeftEyeOpenProbability();
    else if(mEyeToDetect.equals("right eye"))
      mEyeOpenProbability = mFace.getIsRightEyeOpenProbability();
    else
      mEyeOpenProbability =
        mFace.getIsLeftEyeOpenProbability() < mFace.getIsRightEyeOpenProbability()
          ? mFace.getIsLeftEyeOpenProbability()
          : mFace.getIsRightEyeOpenProbability();

    mEyesClosed = false;
    eyeIsDetected = false;
    if(mEyeOpenProbability != -1.0f) {
      eyeIsDetected = true;
      mEyesClosed = mEyeOpenProbability <= mUserSetEyesOpenProbability;
    }

    Log.d("FF", "mUserSetEyesOpenProbability: " + mUserSetEyesOpenProbability);

    if(mEyesClosed)
      readyForAlarm(executionStart);
    else
      stopAlarm();
  }

  public void playSound() {
    if(mMediaPlayer.isPlaying())
      return;

    mMediaPlayer.setLooping(true);
    mMediaPlayer.seekTo(200);
    mMediaPlayer.start();
  }

  public void stopSound() {
    try {
      if(mMediaPlayer != null) {
        if(!mMediaPlayer.isPlaying())
          return;
        mMediaPlayer.setLooping(false);
        mMediaPlayer.pause();
        mMediaPlayer.seekTo(0);
      }
    } catch (Exception e) {
      mEyesOpen = true;
      mShouldCount = false;
      Log.d("stop sound", "Exception at stopsound: " + e);
    }
  }

  public void stopAlarm() {
    mEyesOpen = true;
    stopSound();
  }

  public void readyForAlarm(final long executionStart) {
    mExecutorService.execute(new Runnable() {
      public void run() {
      long executionEnd = System.currentTimeMillis();
      long asyncExecutionTime = executionEnd - executionStart;
      final long executionDelay = mInterval - (asyncExecutionTime + mAudioStartLatency + mInputDelay);
        try {
          if(mEyesOpen == false) {
            if(mShouldCount) {
              mCounter++;
              mShouldCount = false;
              WritableMap mEvent = Arguments.createMap();
              mEvent.putInt("counter", mCounter);
              mThemedReactContext.getJSModule(RCTEventEmitter.class).receiveEvent(getId(), "onFacesDetected", mEvent);
            }
          } else {
            mEyesOpen = false;
            mShouldCount = true;
            Thread.sleep(executionDelay); // WAIT FOR A SECOND
            if(mEyesClosed)
              playSound();
            Thread.sleep(mAudioStartLatency);
          }
        } catch (InterruptedException e) {
          throw new IllegalStateException(e);
        }
      }
    });
  }

  public float translateX(float x) {
    if (dimensions.getFacing() == CameraView.FACING_FRONT)
      return dimensions.getWidth() - (x * scaleX);
    else
      return x * scaleX;
  }

  public float translateY(float y) {
    return y * scaleY;
  }

  @Override
  public void draw(Canvas canvas) {
    super.draw(canvas);

    if (mFace == null)
      return;

    for(Landmark landmark : mFace.getLandmarks()) {
      int landmarkType = landmark.getType();

      if(!(landmarkType == 4 || landmarkType == 10))
        continue;

      float x = translateX(landmark.getPosition().x) - FACE_POSITION_RADIUS;
      float y = translateY(landmark.getPosition().y) - FACE_POSITION_RADIUS;

      if(mEyeToDetect.equals("left eye")) {
        if(landmarkType == 4) {
          canvas.drawCircle(x, y, (FACE_POSITION_RADIUS * scaleY), mFacePositionPaint);
          if(isOnSettingsPage) {
            canvas.drawText("" + String.format("%.2f", mEyeOpenProbability), x, y + 50, mFacePositionPaint);
          }
        }
      }
      else if(mEyeToDetect.equals("right eye")) {
        if(landmarkType == 10) {
          canvas.drawCircle(x, y, (FACE_POSITION_RADIUS * scaleY), mFacePositionPaint);
          if(isOnSettingsPage) {
             canvas.drawText("" + String.format("%.2f", mEyeOpenProbability), x, y + 50, mFacePositionPaint);
          }
        }
      }
      else {
        if(landmarkType == 4 || landmarkType == 10) {
          canvas.drawCircle(x, y, (FACE_POSITION_RADIUS * scaleY), mFacePositionPaint);
          if(isOnSettingsPage) {
             canvas.drawText("" + String.format("%.2f", mEyeOpenProbability), x, y + 50, mFacePositionPaint);
          }
        }
      }
    }

    // if(true) {
    if(eyeIsDetected) {
      float centerX = mFace.getPosition().x;
      float centerY = mFace.getPosition().y;
      float xOffset = mFace.getWidth() / 2.0f;
      float yOffset = mFace.getHeight() / 2.0f;
      float x = translateX(centerX + xOffset);
      float y = translateY(centerY + yOffset);
      float left = x - xOffset;
      float right = x + xOffset;
      float top = y - yOffset;
      float bottom = y + yOffset;
      canvas.rotate(mFace.getEulerZ(), left + xOffset/2, top + yOffset/2);
      canvas.drawRect(left, top, right, bottom, mBoxPaint);
    }
  }


  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    View preview = getView();
    if (null == preview) {
      return;
    }
    float width = right - left;
    float height = bottom - top;
    float ratio = getAspectRatio().toFloat();
    int orientation = getResources().getConfiguration().orientation;
    int correctHeight;
    int correctWidth;
    this.setBackgroundColor(Color.BLACK);
    if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
      if (ratio * height < width) {
        correctHeight = (int) (width / ratio);
        correctWidth = (int) width;
      } else {
        correctWidth = (int) (height * ratio);
        correctHeight = (int) height;
      }
    } else {
      if (ratio * width > height) {
        correctHeight = (int) (width * ratio);
        correctWidth = (int) width;
      } else {
        correctWidth = (int) (height / ratio);
        correctHeight = (int) height;
      }
    }
    int paddingX = (int) ((width - correctWidth) / 2);
    int paddingY = (int) ((height - correctHeight) / 2);
    preview.layout(paddingX, paddingY, correctWidth + paddingX, correctHeight + paddingY);
  }

  @SuppressLint("all")
  @Override
  public void requestLayout() {
    // React handles this for us, so we don't need to call super.requestLayout();
  }

  @Override
  public void onViewAdded(View child) {
    if (this.getView() == child || this.getView() == null) return;
    // remove and read view to make sure it is in the back.
    // @TODO figure out why there was a z order issue in the first place and fix accordingly.
    this.removeView(this.getView());
    this.addView(this.getView(), 0);
  }

  public void setPlaySoundOnCapture(Boolean playSoundOnCapture) {
    mPlaySoundOnCapture = playSoundOnCapture;
  }

  public void takePicture(ReadableMap options, final Promise promise, File cacheDirectory) {
    mPictureTakenPromises.add(promise);
    mPictureTakenOptions.put(promise, options);
    mPictureTakenDirectories.put(promise, cacheDirectory);
    if (mPlaySoundOnCapture) {
      MediaActionSound sound = new MediaActionSound();
      sound.play(MediaActionSound.SHUTTER_CLICK);
    }
    try {
      super.takePicture();
    } catch (Exception e) {
      mPictureTakenPromises.remove(promise);
      mPictureTakenOptions.remove(promise);
      mPictureTakenDirectories.remove(promise);
      throw e;
    }
  }

  @Override
  public void onPictureSaved(WritableMap response) {
    RNCameraViewHelper.emitPictureSavedEvent(this, response);
  }

  public void record(ReadableMap options, final Promise promise, File cacheDirectory) {
    try {
      String path = options.hasKey("path") ? options.getString("path") : RNFileUtils.getOutputFilePath(cacheDirectory, ".mp4");
      int maxDuration = options.hasKey("maxDuration") ? options.getInt("maxDuration") : -1;
      int maxFileSize = options.hasKey("maxFileSize") ? options.getInt("maxFileSize") : -1;

      CamcorderProfile profile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
      if (options.hasKey("quality")) {
        profile = RNCameraViewHelper.getCamcorderProfile(options.getInt("quality"));
      }

      boolean recordAudio = !options.hasKey("mute");

      if (super.record(path, maxDuration * 1000, maxFileSize, recordAudio, profile)) {
        mVideoRecordedPromise = promise;
      } else {
        promise.reject("E_RECORDING_FAILED", "Starting video recording failed. Another recording might be in progress.");
      }
    } catch (IOException e) {
      promise.reject("E_RECORDING_FAILED", "Starting video recording failed - could not create video file.");
    }
  }


  /**
   * Initial setup of the face detector
   */
  private void setupFaceDetector() {
    mFaceDetector = new RNFaceDetector(mThemedReactContext);
    mFaceDetector.setMode(mFaceDetectorMode);
    mFaceDetector.setLandmarkType(mFaceDetectionLandmarks);
    mFaceDetector.setClassificationType(mFaceDetectionClassifications);
    mFaceDetector.setTracking(true);
  }

  public void setFaceDetectionLandmarks(int landmarks) {
    mFaceDetectionLandmarks = landmarks;
    if (mFaceDetector != null) {
      mFaceDetector.setLandmarkType(landmarks);
    }
  }

  public void setFaceDetectionClassifications(int classifications) {
    mFaceDetectionClassifications = classifications;
    if (mFaceDetector != null) {
      mFaceDetector.setClassificationType(classifications);
    }
  }

  public void setFaceDetectionMode(int mode) {
    mFaceDetectorMode = mode;
    if (mFaceDetector != null) {
      mFaceDetector.setMode(mode);
    }
  }

  public void setAlarmVolume(float alarmVolume) {
    AudioManager audioManager = (AudioManager) mThemedReactContext.getSystemService(Context.AUDIO_SERVICE);
    int volume = Math.round(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * alarmVolume);
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
  }

  public void setAlarmCounter(int alarmCounter) {
    this.mCounter = alarmCounter;
    if(alarmCounter == -999)
      isOnSettingsPage = true;
  }

  public void setEyeSensitivity(float eyeSensitivity) {
    this.mUserSetEyesOpenProbability = eyeSensitivity;
  }

  public void setAlarmSoundName(String alarmSoundName) {
    mMediaPlayer = new MediaPlayer();

    try {
      int res = mThemedReactContext.getResources().getIdentifier(alarmSoundName, "raw", mThemedReactContext.getPackageName());
      AssetFileDescriptor afd = mThemedReactContext.getResources().openRawResourceFd(res);
      mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
      mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
      mMediaPlayer.prepareAsync();
    } catch (Exception e) {
      Log.d("RNSoundModule", "Exception", e);
    }
  }

  public void destroyInstance() {
    mMediaPlayer.reset();
    mMediaPlayer.release();
    mMediaPlayer = null;
  }

  public void setFaceDetectionEnable(boolean setFaceDetectionEnable) {
    this.mSetFaceDetectionEnable = setFaceDetectionEnable;
    if(!setFaceDetectionEnable)
      stopAlarm();
  }

  public void setInterval(long interval) {
    this.mInterval = interval;
  }

  public void setEyeToDetect(String eyeToDetect) {
    this.mEyeToDetect = eyeToDetect; // both eyes | left eye | right eye
  }

  public void setShouldDetectFaces(boolean shouldDetectFaces) {
    if (shouldDetectFaces && mFaceDetector == null) {
      setupFaceDetector();
    }
    this.mShouldDetectFaces = shouldDetectFaces;
    setScanning(mShouldDetectFaces);
  }

  public void onFacesDetected(SparseArray<Face> facesReported, int sourceWidth, int sourceHeight, int sourceRotation) {
    if (!mShouldDetectFaces)
      return;

    SparseArray<Face> facesDetected = facesReported == null ? new SparseArray<Face>() : facesReported;

    ImageDimensions dimensions = new ImageDimensions(sourceWidth, sourceHeight, sourceRotation, getFacing());
    RNCameraViewHelper.emitFacesDetectedEvent(this, facesDetected, dimensions);
  }

  public void onFaceDetectionError(RNFaceDetector faceDetector) {
    if (!mShouldDetectFaces)
      return;

    RNCameraViewHelper.emitFaceDetectionErrorEvent(this, faceDetector);
  }

  @Override
  public void onFaceDetectingTaskCompleted() {
    faceDetectorTaskLock = false;
    if(mEyeOpenProbability == -1.0f)
      stopAlarm();
  }

  @Override
  public void onHostResume() {
    if (hasCameraPermissions()) {
      if ((mIsPaused && !isCameraOpened()) || mIsNew) {
        mIsPaused = false;
        mIsNew = false;
        postInvalidate();
        start();
      }
    } else {
      RNCameraViewHelper.emitMountErrorEvent(this, "Camera permissions not granted - component could not be rendered.");
    }
  }

  @Override
  public void onHostPause() {
    stopAlarm();
    if (!mIsPaused && isCameraOpened()) {
      mIsPaused = true;
      stop();
    }
  }

  @Override
  public void onHostDestroy() {
    if(mMediaPlayer != null){
      mMediaPlayer.reset();
      mMediaPlayer.release();
    }
    if (mFaceDetector != null) {
      mFaceDetector.release();
    }
    stop();
    mThemedReactContext.removeLifecycleEventListener(this);
  }

  private boolean hasCameraPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      int result = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA);
      return result == PackageManager.PERMISSION_GRANTED;
    } else {
      return true;
    }
  }
}
