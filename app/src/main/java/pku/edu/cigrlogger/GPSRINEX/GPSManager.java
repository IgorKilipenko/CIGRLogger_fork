package pku.edu.cigrlogger.GPSRINEX;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.location.Criteria;
import android.content.pm.PackageManager;
import android.location.GnssClock;
import android.location.GnssMeasurementsEvent;
import static android.location.GnssMeasurementsEvent.Callback;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.HandlerThread;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import pku.edu.cigrlogger.GPSRINEX.Rinex;
import android.support.v4.app.ActivityCompat;

import pku.edu.cigrlogger.GPSRINEX.GpsTime;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Calendar;

public class GPSManager {
    public static final String TAG = "GPSManager";
    private LocationManager mLocationManager;
    private HandlerThread mGPSThread;
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static final int REQUEST_ACCESS_FINE_LOCATION = 2;
    private GpsTime gpsTime = new GpsTime(0.0);
    private GpsGalileoBdsGlonassQzssConstellation sumConstellation;
    private BufferedWriter mDataWriter = null;
    private Rinex rinex;
    private volatile boolean mRecordingGPS = false;
    private int[] numberofepochs = {0};
    private Location mLocation;//获得一次位置
    //private volatile boolean GPS_available = false;

    //
    //private  Activity mactivity;
    public GPSManager(Activity activity){
        mLocationManager  = (LocationManager)activity.getSystemService(Context.LOCATION_SERVICE);
        sumConstellation = new GpsGalileoBdsGlonassQzssConstellation();
        rinex = new Rinex(activity);
        //mactivity = activity;
    }
    private Callback gnssMeasurementsEvent = new GnssMeasurementsEvent.Callback(){
        @Override
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent eventArgs){
            super.onGnssMeasurementsReceived(eventArgs);
            GnssClock clock = eventArgs.getClock();
            gpsTime =new GpsTime(clock);

            sumConstellation.updateMeasurements(eventArgs);
            EpochMeasurement epochMeasurement = sumConstellation.getEpochMeasurement();
            if(mRecordingGPS){
                if(epochMeasurement!=null)
                    rinex.writeBody(epochMeasurement);
            }

            /*
            if(mRecordingGPS) {
                rinex.testwrite(clock);
            }//GPS_available = true;
            */
        }
        @Override
        public void onStatusChanged(int status) {
            super.onStatusChanged(status);
        }
    };
    @SuppressLint("MissingPermission")
    public void register(Activity activity){
        mGPSThread = new HandlerThread("GPS thread", Process.THREAD_PRIORITY_MORE_FAVORABLE);
        mGPSThread.start();
        Handler GPSHandler =new Handler(mGPSThread.getLooper());
        if (ActivityCompat.checkSelfPermission(activity,
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_ACCESS_FINE_LOCATION);
        }
        String bestProvider = mLocationManager.getBestProvider(getCriteria(), true);
        mLocation = mLocationManager.getLastKnownLocation(bestProvider);
        mLocationManager.registerGnssMeasurementsCallback(gnssMeasurementsEvent,GPSHandler);
        //mLocationManager.registerGnssMeasurementsCallback(gnssMeasurementsEvent);
    }
    public void unregister() {
        mLocationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsEvent);
        mGPSThread.quitSafely();
    }
    public void startRecording(String outputDir) {
        rinex.createFile(outputDir);
        rinex.writeHeader(new RinexHeader());
        writelocation(mLocation,outputDir);
        writeYMD(outputDir);
        getboottime(outputDir);
        //rinex.testwritehead();
        mRecordingGPS = true;
    }
    public void stopRecording() {
        if (mRecordingGPS) {
            mRecordingGPS = false;
            rinex.closeFile();
        }
    }
    private void getboottime(String outputDir){
        long boottime=System.currentTimeMillis()*1000000-SystemClock.elapsedRealtimeNanos();
        String outputfile = outputDir + File.separator + "boottime_in_ns.txt";//建立时间文件夹
        FileWriter out = null;
        try {
            //File file = new File(rootFile, fileName);
            File file = new File(outputfile);
            out = new FileWriter(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Long.toString(boottime));
        try {
            out.write(sb.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private void writeYMD(String outputDir){
        String outputfile = outputDir + File.separator + "YMD.txt";//建立年月日文件夹
        FileWriter out = null;
        try {
            //File file = new File(rootFile, fileName);
            File file = new File(outputfile);
            out = new FileWriter(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
        StringBuilder sb = new StringBuilder();
        Calendar rightNow = Calendar.getInstance();
        String year = String.format("%d", rightNow.get(Calendar.YEAR));
        String month = String.format("%d", rightNow.get(Calendar.MONTH)+1);
        String day = String.format("%d", rightNow.get(Calendar.DATE));
        String delimiter = ",";

        sb.append(year+delimiter);
        sb.append(month+delimiter);
        sb.append(day);
        try {
            out.write(sb.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private void writelocation(Location mLocation,String outputDir){
        String outputfile = outputDir + File.separator + "Coarselocation.txt";//建立粗略位置文件夹
        FileWriter out = null;
        try {
            //File file = new File(rootFile, fileName);
            File file = new File(outputfile);
            out = new FileWriter(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
        StringBuilder sb = new StringBuilder();
        if(mLocation!=null){
            String lati = String.format("%.5f", mLocation.getLatitude());
            String longi = String.format("%.5f", mLocation.getLongitude());
            String alti = String.format("%.3f", mLocation.getAltitude());
            String delimiter = ",";

            sb.append(lati+delimiter);
            sb.append(longi+delimiter);
            sb.append(alti);

        }
        else{
            sb.append("设备未取得粗略位置，请检查设备设置或保证在室外打开app");
        }
        try {
            out.write(sb.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private static Criteria getCriteria() {
        Criteria criteria = new Criteria();
        // 设置定位精确度 Criteria.ACCURACY_COARSE比较粗略，Criteria.ACCURACY_FINE则比较精细
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        // 设置是否要求速度
        criteria.setSpeedRequired(true);
        // 设置是否允许运营商收费
        criteria.setCostAllowed(false);
        // 设置是否需要方位信息
        criteria.setBearingRequired(true);
        // 设置是否需要海拔信息
        criteria.setAltitudeRequired(true);
        // 设置对电源的需求
        criteria.setPowerRequirement(Criteria.POWER_HIGH);
        return criteria;
    }
}
