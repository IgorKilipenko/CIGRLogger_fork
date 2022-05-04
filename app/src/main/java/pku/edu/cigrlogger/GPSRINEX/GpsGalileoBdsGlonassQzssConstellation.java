package pku.edu.cigrlogger.GPSRINEX;

import android.annotation.SuppressLint;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.util.Log;



import pku.edu.cigrlogger.GPSRINEX.GpsSatellite;


import java.util.ArrayList;
import java.util.List;

/**
 *
 * get data and transfer
 * 2020/3/16
 * Butterflying10
 */
public class GpsGalileoBdsGlonassQzssConstellation extends Constellation {


    private static double L1_FREQUENCY = 1.57542e9;
    private static double L5_FREQUENCY = 1.17645e9;
    private static double B1_FREQUENCY = 1.561098e9;


    private static double FREQUENCY_MATCH_RANGE = 0.1e9;
    private GpsConstellation gpsConstellation = new GpsConstellation();


    private GpsL5Constellation gpsL5Constellation = new GpsL5Constellation();

    private BdsConstellation bdsConstellation = new BdsConstellation();
    private static final String NAME = "Galileo + GPS + BDS + Glonass +Qzss";


    /**
     * List holding observed satellites
     */
    private List<SatelliteParameters> observedSatellites;


    @Override
    public SatelliteParameters getSatellite(int index) {
        return observedSatellites.get(index);
    }

    @Override
    public List<SatelliteParameters> getSatellites() {
        return observedSatellites;
    }

    @Override
    public int getVisibleConstellationSize() {
        return 0;
    }


    @Override
    public int getUsedConstellationSize() {
        return observedSatellites.size();
    }

    @Override
    public double getSatelliteSignalStrength(int index) {
        return observedSatellites.get(index).getSignalStrength();
    }

    @Override
    public int getConstellationId() {
        return 0;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void updateMeasurements(GnssMeasurementsEvent event) {
        synchronized (this) {
            observedSatellites = new ArrayList<>();
            gpsConstellation.updateMeasurements(event);

            gpsL5Constellation.updateMeasurements(event);
            bdsConstellation.updateMeasurements(event);

            /**
             * 把一个历元的所有的观测原始数据集合在一起  ，进行处理。
             */
            observedSatellites.addAll(gpsConstellation.getSatellites());
            observedSatellites.addAll(gpsL5Constellation.getSatellites());
            observedSatellites.addAll(bdsConstellation.getSatellites());
            long stss = System.currentTimeMillis();
            convertToEpochMessurement();
            long edss = System.currentTimeMillis();
            Log.d("转换用时", edss - stss + "ms");
            Log.d(NAME, "长度：" + observedSatellites.size());

            //转换完成

        }
    }

    /**
     * @param a
     * @param b
     * @param eps
     * @return
     */

    public static boolean approximateEqual(double a, double b, double eps) {
        return Math.abs(a - b) < eps;
    }

    /**
     * 获取一个历元的数据
     */
    private EpochMeasurement mEpochMeasurement;

    public EpochMeasurement getEpochMeasurement() {

        return mEpochMeasurement;
    }

    public void setEpochMeasurement(EpochMeasurement epochMeasurement) {
        mEpochMeasurement = epochMeasurement;
    }

    /**
     * 处理一个历元的原始数据，使其变为一个历元数据，，，，注意   这是一个历元，。。。。。。
     */

    private void convertToEpochMessurement() {

        try {

            GpsTime initalgps = observedSatellites.get(0).getGpsTime();

            /**
             * 存放历元下的gps卫星--原始数据
             */
            List<SatelliteParameters> epochgps_observedSatellites = new ArrayList<>();
            List<SatelliteParameters> epochbds_observedSatellites = new ArrayList<>();

            List<Integer> hasDoublesvid = new ArrayList<>();
            Log.d(NAME, "时间对齐前：" + observedSatellites.size());
            for (SatelliteParameters satelliteParameters : observedSatellites) {


                if (satelliteParameters.getGpsTime().getGpsTimeString().equals(initalgps.getGpsTimeString())) {

                    if (satelliteParameters.getConstellationType() == GnssStatus.CONSTELLATION_GPS) {
                        epochgps_observedSatellites.add(satelliteParameters);
                    }
                    if (satelliteParameters.getConstellationType() == GnssStatus.CONSTELLATION_BEIDOU) {
                        epochbds_observedSatellites.add(satelliteParameters);
                    }
                }
            }
            Log.d(NAME, "时间对齐后：" + epochgps_observedSatellites.size());
            {
                //initalgps = satelliteParameters.getGpsTime();


                /**
                 * 存放历元下的gps卫星数据---处理后的
                 */
                List<GpsSatellite> epoch_gpsSatelliteList = new ArrayList<>();
                List<BdsSatellite> epoch_bdsSatelliteList = new ArrayList<>();

                /**
                 * 处理gps卫星
                 */
                if (epochgps_observedSatellites.size() == 1) {
                    GpsSatellite gpsSatellite;
                    int i = 0;
                    int svid = epochgps_observedSatellites.get(0).getSatId();
                    if (approximateEqual(epochgps_observedSatellites.get(i).getCarrierFrequency(), L1_FREQUENCY, FREQUENCY_MATCH_RANGE)) {
                        gpsSatellite = gpsL1L5set(epochgps_observedSatellites.get(i), epochgps_observedSatellites.get(i), svid, "L1");
                        epoch_gpsSatelliteList.add(gpsSatellite);
                    }
                    if (approximateEqual(epochgps_observedSatellites.get(i).getCarrierFrequency(), L5_FREQUENCY, FREQUENCY_MATCH_RANGE)) {
                        gpsSatellite = gpsL1L5set(epochgps_observedSatellites.get(i), epochgps_observedSatellites.get(i), svid, "L5");
                        epoch_gpsSatelliteList.add(gpsSatellite);
                    }
                }
                if (epochgps_observedSatellites.size() > 1) {
                    for (int i = 0; i < epochgps_observedSatellites.size(); i++) {

                        int svid = epochgps_observedSatellites.get(i).getSatId();

                        if (i < epochgps_observedSatellites.size() - 1) {
                            for (int j = i + 1; j < epochgps_observedSatellites.size(); j++) {
                                GpsSatellite gpsSatellite;
                            /*
                           表明这个卫星有两个频率
                         */
                                if (epochgps_observedSatellites.get(i).getSatId() == epochgps_observedSatellites.get(j).getSatId()) {

                                    hasDoublesvid.add(epochgps_observedSatellites.get(i).getSatId());
                            /*
                            表明L1频率在前，L5频率在后
                             */
                                    if (epochgps_observedSatellites.get(i).getCarrierFrequency() > epochgps_observedSatellites.get(j).getCarrierFrequency()) {
                                        // SatelliteParameters satelliteParameters
                                        gpsSatellite = gpsL1L5set(epochgps_observedSatellites.get(i), epochgps_observedSatellites.get(j), svid, "L1L5");

                                        epoch_gpsSatelliteList.add(gpsSatellite);
                                    }
                            /*
                            表明L5频率在前，L1频率在后
                             */
                                    if (epochgps_observedSatellites.get(i).getCarrierFrequency() < epochgps_observedSatellites.get(j).getCarrierFrequency()) {

                                        gpsSatellite = gpsL1L5set(epochgps_observedSatellites.get(j), epochgps_observedSatellites.get(i), svid, "L1L5");

                                        epoch_gpsSatelliteList.add(gpsSatellite);
                                    }
                                    break;
                                }
                        /*
                        表明这个卫星只有一个频率
                         */
                                if (j == epochgps_observedSatellites.size() - 1 && !hasDoublesvid.contains(epochgps_observedSatellites.get(i).getSatId())) {
                                    if (approximateEqual(epochgps_observedSatellites.get(i).getCarrierFrequency(), L1_FREQUENCY, FREQUENCY_MATCH_RANGE)) {
                                        gpsSatellite = gpsL1L5set(epochgps_observedSatellites.get(i), epochgps_observedSatellites.get(i), svid, "L1");
                                        epoch_gpsSatelliteList.add(gpsSatellite);
                                    }
                                    if (approximateEqual(epochgps_observedSatellites.get(i).getCarrierFrequency(), L5_FREQUENCY, FREQUENCY_MATCH_RANGE)) {
                                        gpsSatellite = gpsL1L5set(epochgps_observedSatellites.get(i), epochgps_observedSatellites.get(i), svid, "L5");
                                        epoch_gpsSatelliteList.add(gpsSatellite);
                                    }
                                }
                            }
                        }
                        else if (!hasDoublesvid.contains(epochgps_observedSatellites.get(i).getSatId())) {// 调整最后一颗卫星观测量被丢弃的bug
                            GpsSatellite gpsSatellite;
                            String frq = (approximateEqual(epochgps_observedSatellites.get(i).getCarrierFrequency(), L1_FREQUENCY, FREQUENCY_MATCH_RANGE))? "1":"5";
                            String Option = "L"+frq;
                            gpsSatellite = gpsL1L5set(epochgps_observedSatellites.get(i), epochgps_observedSatellites.get(i), svid, Option);
                            epoch_gpsSatelliteList.add(gpsSatellite);
                        }
                    }
                }
                // 处理 BDS
                for (int i = 0; i < epochbds_observedSatellites.size(); i++) {
                    int svid = epochbds_observedSatellites.get(i).getSatId();

                    /**
                     * 表明带的是B1频率
                     */
                    if (approximateEqual(epochbds_observedSatellites.get(i).getCarrierFrequency(), B1_FREQUENCY, FREQUENCY_MATCH_RANGE)) {
                        BdsSatellite bdsSatellite ;

                        bdsSatellite = bdsB1set(epochbds_observedSatellites.get(i), svid);

                        epoch_bdsSatelliteList.add(bdsSatellite);


                        //    Log.d("beidou", "svid : " + bdsSatellite.getPrn() + "  C2I: " + bdsSatellite.getC2I() + "  L2I:" + bdsSatellite.getL2I() + "  D2I:" + bdsSatellite.getD2I() + "  S2I" + bdsSatellite.getS2I());

                    }

                }

                //Log.d(NAME, "提取观测量后卫星数目：" + epoch_gpsSatelliteList.size());
                //for (int i =0;i<epoch_gpsSatelliteList.size();i++){
                 ///   Log.d(NAME, "提取观测量后卫星号：" + epoch_gpsSatelliteList.get(i).getPrn());
               // }

                EpochMeasurement epochMeasurement = new EpochMeasurement(initalgps, epoch_gpsSatelliteList,epoch_bdsSatelliteList);
                this.setEpochMeasurement(epochMeasurement);
                epochgps_observedSatellites.clear();
                epochbds_observedSatellites.clear();
                hasDoublesvid.clear();
            }
        }
        catch (Exception e) {
            Log.d(NAME, "原始数据转换为历元数据出错，可能为原始数据为空");
        }
    }

    /**
     * 对于卫星的prn不足长度  补零的方法
     *
     * @param constellationLabel 系统标签   如 G   J    C   R   E
     * @param svid               messurement.getsvid()
     * @return
     */
    private String addprn(char constellationLabel, int svid) {

        @SuppressLint("DefaultLocale") String prn = String.format("%c%02d", constellationLabel, svid);
        return prn;
    }
    private GpsSatellite gpsL1L5set(SatelliteParameters satelliteParametersL1,SatelliteParameters satelliteParametersL5, int svid,String option){
        GpsSatellite gpsSatellite = new GpsSatellite();
        if (option.equals("L1L5")){

            gpsSatellite.setPrn(addprn('G', svid));
            gpsSatellite.setC1(satelliteParametersL1.getPseudorange());
            gpsSatellite.setC5(satelliteParametersL5.getPseudorange());
            gpsSatellite.setLLI1(satelliteParametersL1.getLLI());
            gpsSatellite.setL1(satelliteParametersL1.getPhase());
            gpsSatellite.setL5(satelliteParametersL5.getPhase());
            gpsSatellite.setS1(satelliteParametersL1.getSignalStrength());
            gpsSatellite.setS5(satelliteParametersL5.getSignalStrength());
            gpsSatellite.setD1(satelliteParametersL1.getDoppler());
            gpsSatellite.setD5(satelliteParametersL5.getDoppler());
            gpsSatellite.setLLI5(satelliteParametersL5.getLLI());
        }
        if(option.equals("L1")){
            gpsSatellite.setPrn(addprn('G', svid));
            gpsSatellite.setC1(satelliteParametersL1.getPseudorange());
            gpsSatellite.setL1(satelliteParametersL1.getPhase());
            gpsSatellite.setLLI1(satelliteParametersL1.getLLI());
            gpsSatellite.setS1(satelliteParametersL1.getSignalStrength());
            gpsSatellite.setD1(satelliteParametersL1.getDoppler());
        }
        if(option.equals("L5")){
            gpsSatellite.setPrn(addprn('G', svid));
            gpsSatellite.setC5(satelliteParametersL5.getPseudorange());
            gpsSatellite.setL5(satelliteParametersL5.getPhase());
            gpsSatellite.setLLI5(satelliteParametersL5.getLLI());
            gpsSatellite.setS5(satelliteParametersL5.getSignalStrength());
            gpsSatellite.setD5(satelliteParametersL5.getDoppler());
        }
        return gpsSatellite;
    }
    private BdsSatellite bdsB1set(SatelliteParameters satelliteParametersB, int svid){
        BdsSatellite bdsSatellite = new BdsSatellite();
        bdsSatellite.setPrn(addprn('C', svid));
        bdsSatellite.setC2I(satelliteParametersB.getPseudorange());
        bdsSatellite.setL2I(satelliteParametersB.getPhase());
        bdsSatellite.setLLI2I(satelliteParametersB.getLLI());
        bdsSatellite.setS2I(satelliteParametersB.getSignalStrength());
        bdsSatellite.setD2I(satelliteParametersB.getDoppler());
        return bdsSatellite;
    }

}
