package org.reactnative.camera.tasks;

import android.util.SparseArray;
import com.google.android.gms.vision.face.Face;
import org.reactnative.frame.RNFrame;
import org.reactnative.frame.RNFrameFactory;
import org.reactnative.facedetector.RNFaceDetector;

public class FaceDetectorAsyncTask extends android.os.AsyncTask<Void, Void, SparseArray<Face>> {
  private byte[] mImageData;
  private int mWidth;
  private int mHeight;
  private int mRotation;
  private RNFaceDetector mFaceDetector;
  private FaceDetectorAsyncTaskDelegate mDelegate;
  private long mExecutionStart;

  public FaceDetectorAsyncTask(
    FaceDetectorAsyncTaskDelegate delegate,
    RNFaceDetector faceDetector,
    byte[] imageData,
    int width,
    int height,
    int rotation,
    long executionStart
  ) {
    mDelegate = delegate;
    mFaceDetector = faceDetector;
    mImageData = imageData;
    mWidth = width;
    mHeight = height;
    mRotation = rotation;
    mExecutionStart = executionStart;
  }

  @Override
  protected SparseArray<Face> doInBackground(Void... ignored) {
    if (isCancelled() || mDelegate == null || mFaceDetector == null || !mFaceDetector.isOperational()) {
      return null;
    }

    RNFrame frame = RNFrameFactory.buildFrame(mImageData, mWidth, mHeight, mRotation);
    return mFaceDetector.detect(frame);
  }

  @Override
  protected void onPostExecute(SparseArray<Face> faces) {
    super.onPostExecute(faces);

    if (faces == null) {
      mDelegate.onFaceDetectionError(mFaceDetector);
    } else {
      if (faces.size() > 0) {
        Face face = faces.valueAt(0);
        // if(!(face.getIsLeftEyeOpenProbability() < 0 && face.getIsRightEyeOpenProbability() < 0)) {
        //   // mDelegate.onFacesDetected(faces, mWidth, mHeight, mRotation);
        // }
        mDelegate.update(face, mWidth, mHeight, mRotation, mExecutionStart);
      }
      mDelegate.onFaceDetectingTaskCompleted();
    }
  }
}
