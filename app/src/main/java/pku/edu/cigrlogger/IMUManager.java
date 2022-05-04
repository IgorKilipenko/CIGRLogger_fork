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

public class IMUManager implements SensorEventListener {
    private static final String TAG = "IMUManager";
    // if the accelerometer data has a timestamp within the
    // [t-x, t+x] of the gyro data at t, then the original acceleration data
    // is used instead of linear interpolation
    private final long mInterpolationTimeResolution = 500; // nanoseconds
    private final int mSensorRate = SensorManager.SENSOR_DELAY_GAME;
    //private final int mSensorRate = SensorManager.SENSOR_DELAY_FASTEST;
    private class SensorPacket {
        long timestamp;
        float[] values;

        SensorPacket(long time, float[] vals) {
            timestamp = time;
            values = vals;
        }
    }

    // Sensor listeners
    private SensorManager mSensorManager;
    private Sensor mAccel;
    private Sensor mGyro;
    private Sensor mMagne;
    private int linear_acc; // accuracy
    private int angular_acc;

    private volatile boolean mRecordingInertialData = false;
    private BufferedWriter mDataWriter = null;
    private HandlerThread mSensorThread;

    private Deque<SensorPacket> mGyroData = new ArrayDeque<>();
    private Deque<SensorPacket> mAccelData = new ArrayDeque<>();
    private Deque<SensorPacket> mMagneData = new ArrayDeque<>();

    public IMUManager(Activity activity) {
        mSensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        mAccel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mGyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        mMagne = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
    }

    public void startRecording(String captureResultFile) {
        try {
            mDataWriter = new BufferedWriter(
                    new FileWriter(captureResultFile, false));
            String header = "Timestamp[nanosec], gx[rad/s], gy[rad/s], gz[rad/s]," +
                    " ax[m/s^2], ay[m/s^2], az[m/s^2],"+" mx[uT], my[uT], mz[uT]\n";

            mDataWriter.write(header);
            mRecordingInertialData = true;
        } catch (IOException err) {
            System.err.println("IOException in opening inertial data writer at "
                    + captureResultFile + ": " + err.getMessage());
        }
    }

    public void stopRecording() {
        if (mRecordingInertialData) {
            mRecordingInertialData = false;
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

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            linear_acc = accuracy;
        } else if (sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            angular_acc = accuracy;
        }
    }

    // sync inertial data by interpolating linear acceleration for each gyro data
    // Because the sensor events are delivered to the handler thread in order,
    // no need for synchronization here
    private SensorPacket syncInertialData() {
        if (mGyroData.size() >= 1 && mAccelData.size() >= 2 && mMagneData.size()>=2) {
            SensorPacket oldestGyro = mGyroData.peekFirst();
            SensorPacket oldestAccel = mAccelData.peekFirst();
            SensorPacket latestAccel = mAccelData.peekLast();
            SensorPacket oldestMagne = mMagneData.peekFirst();
            SensorPacket latestMagne = mMagneData.peekLast();
            // 保证最早加速度早于最早陀螺仪
            //保证最早陀螺仪同时位于最早加速度计和最早地磁之后
            if (oldestGyro.timestamp < oldestAccel.timestamp || oldestGyro.timestamp < oldestMagne.timestamp) {// 最早陀螺仪比最早加速度还早，肯定没办法插值，把最早陀螺仪删去
                Log.w(TAG, "throwing one gyro data");
                mGyroData.removeFirst();
            }
            // 只保留最晚加速度和最晚地磁
            else if (oldestGyro.timestamp > latestAccel.timestamp) {// 最早陀螺仪比最晚加速度还晚，只保留最晚加速度，其它加速度都删去
                Log.w(TAG, "throwing #accel data " + (mAccelData.size() - 1));
                mAccelData.clear();
                mAccelData.add(latestAccel);
            }
            else if(oldestGyro.timestamp > latestMagne.timestamp){
                Log.w(TAG, "throwing #magne data " + (mMagneData.size() - 1));
                mMagneData.clear();
                mMagneData.add(latestMagne);
            }
            else { // linearly interpolate the accel data at the gyro timestamp
                float[] gyro_accel_magne = new float[9];
                SensorPacket sp = new SensorPacket(oldestGyro.timestamp, gyro_accel_magne);//这一步似乎是把sp的成员和gyro_accel绑定在一起
                //后面的值改变，sp的成员自动改变。
                gyro_accel_magne[0] = oldestGyro.values[0];
                gyro_accel_magne[1] = oldestGyro.values[1];
                gyro_accel_magne[2] = oldestGyro.values[2];

                SensorPacket leftAccel = null;
                SensorPacket rightAccel = null;
                Iterator<SensorPacket> itr = mAccelData.iterator();
                while (itr.hasNext()) {//找到两组accel夹住一组陀螺仪，再跳出
                    SensorPacket packet = itr.next();
                    if (packet.timestamp <= oldestGyro.timestamp) {
                        leftAccel = packet;
                    } else if (packet.timestamp >= oldestGyro.timestamp) {
                        rightAccel = packet;
                        break;
                    }
                }

                SensorPacket leftMagne = null;
                SensorPacket rightMagne = null;
                Iterator<SensorPacket> itr_magne = mMagneData.iterator();
                while (itr_magne.hasNext()) {//找到两组magne夹住一组陀螺仪，再跳出
                    SensorPacket packet = itr_magne.next();
                    if (packet.timestamp <= oldestGyro.timestamp) {
                        leftMagne = packet;
                    } else if (packet.timestamp >= oldestGyro.timestamp) {
                        rightMagne = packet;
                        break;
                    }
                }

                if (oldestGyro.timestamp - leftAccel.timestamp <= //左加速度距离当前插值在阈值内，直接用左加速度当成陀螺仪时刻的加速度
                        mInterpolationTimeResolution) {
                    gyro_accel_magne[3] = leftAccel.values[0];
                    gyro_accel_magne[4] = leftAccel.values[1];
                    gyro_accel_magne[5] = leftAccel.values[2];
                } else if (rightAccel.timestamp - oldestGyro.timestamp <= //右加速度距离当前插值在阈值内，直接用右加速度当成陀螺仪时刻的加速度
                        mInterpolationTimeResolution) {
                    gyro_accel_magne[3] = rightAccel.values[0];
                    gyro_accel_magne[4] = rightAccel.values[1];
                    gyro_accel_magne[5] = rightAccel.values[2];
                } else {// 否则就要做插值
                    float ratio = (oldestGyro.timestamp - leftAccel.timestamp) /
                            (rightAccel.timestamp - leftAccel.timestamp);
                    gyro_accel_magne[3] = leftAccel.values[0] +
                            (rightAccel.values[0] - leftAccel.values[0]) * ratio;
                    gyro_accel_magne[4] = leftAccel.values[1] +
                            (rightAccel.values[1] - leftAccel.values[1]) * ratio;
                    gyro_accel_magne[5] = leftAccel.values[2] +
                            (rightAccel.values[2] - leftAccel.values[2]) * ratio;
                }



                if (oldestGyro.timestamp - leftMagne.timestamp <= //左地磁距离当前插值在阈值内，直接用左地磁当成陀螺仪时刻的地磁
                        mInterpolationTimeResolution) {
                    gyro_accel_magne[6] = leftMagne.values[0];
                    gyro_accel_magne[7] = leftMagne.values[1];
                    gyro_accel_magne[8] = leftMagne.values[2];
                } else if (rightMagne.timestamp - oldestGyro.timestamp <= //右地磁距离当前插值在阈值内，直接用右地磁当成陀螺仪时刻的地磁
                        mInterpolationTimeResolution) {
                    gyro_accel_magne[6] = rightMagne.values[0];
                    gyro_accel_magne[7] = rightMagne.values[1];
                    gyro_accel_magne[8] = rightMagne.values[2];
                } else {// 否则就要做插值
                    //mag 的时间是10ms输出一次，但实际上我发现往往相邻时间间隔的mag的值是一样的，
                    // 看了时间戳确实不一样。
                    //应该和mag传感器本身的性质有关系吧。
                    //所以这个插值实际上效果不大。
                    float ratio = (float)(oldestGyro.timestamp - leftMagne.timestamp) /
                            (float)(rightMagne.timestamp - leftMagne.timestamp);
                    gyro_accel_magne[6] = leftMagne.values[0] +
                            (rightMagne.values[0] - leftMagne.values[0]) * ratio;
                    gyro_accel_magne[7] = leftMagne.values[1] +
                            (rightMagne.values[1] - leftMagne.values[1]) * ratio;
                    gyro_accel_magne[8] = leftMagne.values[2] +
                            (rightMagne.values[2] - leftMagne.values[2]) * ratio;
                }

                mGyroData.removeFirst();//插值完之后，移除最早陀螺仪数据，已经插过值了。
                for (Iterator<SensorPacket> iterator = mAccelData.iterator();
                     iterator.hasNext(); ) {
                    SensorPacket packet = iterator.next();
                    if (packet.timestamp < leftAccel.timestamp) {// 移除
                        // Remove the current element from the iterator and the list.
                        iterator.remove();
                    } else {
                        break;
                    }
                }
                for (Iterator<SensorPacket> iterator = mMagneData.iterator();
                     iterator.hasNext(); ) {
                    SensorPacket packet = iterator.next();
                    if (packet.timestamp < leftMagne.timestamp) {// 移除
                        // Remove the current element from the iterator and the list.
                        iterator.remove();
                    } else {
                        break;
                    }
                }

                return sp;
            }
        }
        return null;
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            SensorPacket sp = new SensorPacket(event.timestamp, event.values);
            mAccelData.add(sp);
        }
        else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            SensorPacket sp = new SensorPacket(event.timestamp, event.values);
            mMagneData.add(sp);
        }else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            SensorPacket sp = new SensorPacket(event.timestamp, event.values);
            mGyroData.add(sp);
            SensorPacket syncedData = syncInertialData();
            if (syncedData != null && mRecordingInertialData) {
                String delimiter = ",";
                StringBuilder sb = new StringBuilder();
                sb.append(syncedData.timestamp);
                for (int index = 0; index < 9; ++index) {
                    sb.append(delimiter + syncedData.values[index]);
                }
                try {
                    mDataWriter.write(sb.toString() + "\n");
                } catch (IOException ioe) {
                    System.err.println("IOException: " + ioe.getMessage());
                }
            }
        }
    }

    /**
     * This will register all IMU listeners
     * https://stackoverflow.com/questions/3286815/sensoreventlistener-in-separate-thread
     */
    public void register() {//在一开始register一次，随后对sensor的检测都靠这个新申请的handlerthread进行
        // 应该没有和UI交互？因为onsensorchanged 是 当监测到sensor值改变的时候会自动被调用，在那个函数中
        // 把变化的值打印到文件中。
        mSensorThread = new HandlerThread("Sensor thread",
                Process.THREAD_PRIORITY_MORE_FAVORABLE);
        mSensorThread.start();
        // Blocks until looper is prepared, which is fairly quick
        Handler sensorHandler = new Handler(mSensorThread.getLooper());
        mSensorManager.registerListener(
                this, mAccel, mSensorRate, sensorHandler);
        mSensorManager.registerListener(
                this, mGyro, mSensorRate, sensorHandler);
        mSensorManager.registerListener(
                this, mMagne, mSensorRate, sensorHandler);
    }

    /**
     * This will unregister all IMU listeners
     */
    public void unregister() {
        mSensorManager.unregisterListener(this, mAccel);
        mSensorManager.unregisterListener(this, mGyro);
        mSensorManager.unregisterListener(this);
        mSensorThread.quitSafely();
        stopRecording();
    }
}
