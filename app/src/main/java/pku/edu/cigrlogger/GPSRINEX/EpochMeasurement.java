package pku.edu.cigrlogger.GPSRINEX;

import pku.edu.cigrlogger.GPSRINEX.GpsTime;

import java.util.ArrayList;
import java.util.List;

/**
store data at current epoch
 */
public class EpochMeasurement {

    private List<GpsSatellite> gpsSatelliteList;
    private List<BdsSatellite> bdsSatelliteList;


    public EpochMeasurement(GpsTime gpsTime, List<GpsSatellite> gpsSatelliteList,List<BdsSatellite> bdsSatelliteList) {
        this.setEpochTime(gpsTime);
        this.setGpsSatelliteList(gpsSatelliteList);
        this.setBdsSatelliteList(bdsSatelliteList);
    }

    /**
     * 获取卫星prn号总的列表
     */
    public List<String> getSatellitePrnList() {
        List<String> satellitePrnList=new ArrayList<>();
        if(this.gpsSatelliteList.size()>0)
        {for(GpsSatellite gpsSatellite :gpsSatelliteList)
            satellitePrnList.add(gpsSatellite.getPrn());}
        if(this.bdsSatelliteList.size()>0)
        {for(BdsSatellite bdsSatellite : bdsSatelliteList)
            satellitePrnList.add(bdsSatellite.getPrn());}
        return satellitePrnList;
    }

    /**
     * 获取卫星数目，针对于rinex3.03
     */
    private int satelliteNum;

    public int getSatelliteNum() {


        return gpsSatelliteList.size()+bdsSatelliteList.size();
    }
    /**
     * 获取卫星数目，针对于rinex2.11
     */
    private int satelliteNum2;

    public int getSatelliteNum2() {
        return gpsSatelliteList.size();
    }
    /**
     * 获取卫星的prn号的列表,针对于rinex2.11
     */
    private List<String> prnlist=new ArrayList<>();

    public List<String> getPrnlist() {
        if(this.gpsSatelliteList.size()>0) {
            for (GpsSatellite gpsSatellite : this.gpsSatelliteList) {
                prnlist.add(gpsSatellite.getPrn());
            }
        }
        return this.prnlist;
    }



    /**
     * 历元时间
     */
    private GpsTime epochTime;

    private void setEpochTime(GpsTime epochTime) {
        this.epochTime = epochTime;
    }

    public GpsTime getEpochTime() {
        return epochTime;
    }

    /**
     * 存放每个历元下的gps卫星列表数据
     */
    private void setGpsSatelliteList(List<GpsSatellite> gpsSatelliteList) {
        this.gpsSatelliteList = gpsSatelliteList;
    }

    public List<GpsSatellite> getGpsSatelliteList() {
        return gpsSatelliteList;
    }
    /**
     * 存放每个历元下的bds卫星列表数据
     */
    private void setBdsSatelliteList(List<BdsSatellite> bdsSatelliteList) {
        this.bdsSatelliteList = bdsSatelliteList;
    }

    public List<BdsSatellite> getBdsSatelliteList() {
        return bdsSatelliteList;
    }

}
