package pku.edu.cigrlogger.GPSRINEX;



import android.os.Build;

import pku.edu.cigrlogger.GPSRINEX.GpsTime;

public class RinexHeader {
    private String markName;
    private String markType;
    private String observerName;
    private String observerAgencyName;
    private String receiverNumber;
    private String receiverType;
    private String receiverVersion;
    private String antennaNumber;
    private String antennaType;
    private double antennaEccentricityEast;
    private double antennaEccentricityNorth;
    private double antennaHeight;
    private String cartesianX;
    private String cartesianY;
    private String cartesianZ;
    //private GpsTime gpsTime;

    public RinexHeader() {
        markName = "CIGR_Record";
        markType = "GEODETIC";
        observerName = "CIGR_Record";
        observerAgencyName = "CIGR_Record";
        receiverNumber = Build.SERIAL;
        receiverType = Build.MANUFACTURER;
        receiverVersion = Build.MODEL;
        antennaNumber = Build.SERIAL;
        antennaType = Build.MODEL;
        antennaEccentricityEast = 0.0;
        antennaEccentricityNorth = 0.0;
        antennaHeight = 0.0;
        cartesianX = "0.0000";
        cartesianY = "0.0000";
        cartesianZ = "0.0000";
        //gpsTime = gpsT;
    }
    public String getMarkName() {
        return markName;
    }

    public String getMarkType() {
        return markType;
    }

    public String getObserverName() {
        return observerName;
    }

    public String getObserverAgencyName() {
        return observerAgencyName;
    }

    public String getReceiverNumber() {
        return receiverNumber;
    }

    public String getReceiverType() {
        return receiverType;
    }

    public String getReceiverVersion() {
        return receiverVersion;
    }

    public String getAntennaNumber() {
        return antennaNumber;
    }

    public String getAntennaType() {
        return antennaType;
    }

    public double getAntennaEccentricityEast() {
        return antennaEccentricityEast;
    }

    public double getAntennaEccentricityNorth() {
        return antennaEccentricityNorth;
    }

    public double getAntennaHeight() {
        return antennaHeight;
    }

    public String getCartesianX() {
        return cartesianX;
    }

    public String getCartesianY() {
        return cartesianY;
    }

    public String getCartesianZ() {
        return cartesianZ;
    }
/*
    public GpsTime getGpsTime() {
        return gpsTime;
    }
*/
}

