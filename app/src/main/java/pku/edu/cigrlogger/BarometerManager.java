package pku.edu.cigrlogger;
import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public class BarometerManager implements SensorEventListener{
    private SensorManager mSensorManager;
    private final int mSensorRate = SensorManager.SENSOR_DELAY_GAME;
    private Sensor mBaro;

    private volatile boolean mRecordingBaroData = false;
    private BufferedWriter mDataWriter = null;
    private HandlerThread mSensorThread;

    public BarometerManager(Activity activity) {
        mSensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        mBaro = mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
    }



    @Override
    public final void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            long timestamp = event.timestamp;
            float[] values = event.values;
            if(values!=null && mRecordingBaroData){
                String delimiter = ",";
                StringBuilder sb = new StringBuilder();
                sb.append(timestamp);
                sb.append(delimiter + values[0]);
                try {
                    mDataWriter.write(sb.toString() + "\n");
                } catch (IOException ioe) {
                    System.err.println("IOException: " + ioe.getMessage());
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    public void startRecording(String captureResultFile) {
        try {
            mDataWriter = new BufferedWriter(
                    new FileWriter(captureResultFile, false));
            String header = "Timestamp[nanosec], Pressure[hPa]\n";
            mDataWriter.write(header);
            mRecordingBaroData = true;
        } catch (IOException err) {
            System.err.println("IOException in opening inertial data writer at "
                    + captureResultFile + ": " + err.getMessage());
        }
    }

    public void stopRecording() {
        if (mRecordingBaroData) {
            mRecordingBaroData = false;
            try {
                mDataWriter.flush();
                mDataWriter.close();
            } catch (IOException err) {
                System.err.println(
                        "IOException in closing inertial data writer: " + err.getMessage());
            }
            mDataWriter = null;
        }
    }


    public void register() {//在一开始register一次，随后对sensor的检测都靠这个新申请的handlerthread进行
        // 应该没有和UI交互？因为onsensorchanged 是 当监测到sensor值改变的时候会自动被调用，在那个函数中
        // 把变化的值打印到文件中。
        mSensorThread = new HandlerThread("Sensor thread",
                Process.THREAD_PRIORITY_MORE_FAVORABLE);
        mSensorThread.start();
        // Blocks until looper is prepared, which is fairly quick
        Handler sensorHandler = new Handler(mSensorThread.getLooper());
        mSensorManager.registerListener(
                this, mBaro, mSensorRate, sensorHandler);

    }

    /**
     * This will unregister all IMU listeners
     */
    public void unregister() {
        mSensorManager.unregisterListener(this, mBaro);
        mSensorManager.unregisterListener(this);
        mSensorThread.quitSafely();
        stopRecording();
    }


}
