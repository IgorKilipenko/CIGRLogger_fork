package pku.edu.cigrlogger.GPSRINEX;

import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.util.Log;
import pku.edu.cigrlogger.GPSRINEX.GNSSConstants;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Created by Mateusz Krainski on 17/02/2018.
 * This class is for...
 * <p>
 * GPS Pseudorange computation algorithm by: Mareike Burba
 * - variable name changes and comments were added
 * to fit the description in the GSA white paper
 * by: Sebastian Ciuban
 */

public class BdsConstellation extends Constellation {

    private final static char satType = 'C';
    private static final String NAME = "BDS B1";
    private static final String TAG = "BdsConstellation";
    private static double B1_FREQUENCY = 1.561098e9;
    private static double FREQUENCY_MATCH_RANGE = 0.1e9;

    private boolean FirstfullBiasNanosisnotInitialized = true;
    private boolean FirstBiasNanosisnotInitialized = true;
    private long FullBiasNanos = 0L;
    private long FirstFullBiasNanos = 0L;
    private double BiasNanos = 0.0D;
    private double FirstBiasNanos = 0.0D;
    private long clip_addcFB_subfFBq = 0L;
    private double cB_addcB_subfBr = 0.0D;
    private long clip_delta_timeo = 0L;
    private HashMap<Integer, HashMap<Byte, Long>> phase_corrections_hash = new HashMap<>();
    private HashMap<Integer, HashSet<Byte>> pre_phase_valid_map = new HashMap<>();//协助判断LLI标志是否应该置位
    private HashMap<Integer, HashSet<Byte>> cur_phase_valid_map = new HashMap<>();
    //如果在当前时刻发现上一时刻的相位无效，但当前有效，则LLI置位为1，同时把对应值改为有效

    protected double tRxBDS;
    protected double weekNumberNanos;
    private List<SatelliteParameters> unusedSatellites = new ArrayList<>();

    public double getWeekNumber() {
        return weekNumberNanos;
    }

    public double gettRxBDS() {
        return tRxBDS;
    }

    private static final int constellationId = GnssStatus.CONSTELLATION_BEIDOU;
    private static double MASK_ELEVATION = 20; // degrees
    private static double MASK_CN0 = 10; // dB-Hz

    /**
     * Time of the measurement
     */
    //private Time timeRefMsec;

    protected int visibleButNotUsed = 0;

    // Condition for the pseudoranges that takes into account a maximum uncertainty for the TOW
    // (as done in gps-measurement-tools MATLAB code)
    private static final int MAXTOWUNCNS = 50;                                     // [nanoseconds]


    /**
     * List holding observed satellites
     */
    protected List<SatelliteParameters> observedSatellites = new ArrayList<>();

    /**
     * Corrections which are to be applied to received pseudoranges
     */


//    @Override
//    public Time getTime() {
//        synchronized (this) {
//            return timeRefMsec;
//        }
//    }
    @Override
    public String getName() {
        synchronized (this) {
            return NAME;
        }
    }

    public static boolean approximateEqual(double a, double b, double eps) {
        return Math.abs(a - b) < eps;// 为了确定频率
    }

    /**
     * @param event GNSS event
     */
    @Override
    public void updateMeasurements(GnssMeasurementsEvent event) {

        synchronized (this) {
            visibleButNotUsed = 0;
            observedSatellites.clear();
            unusedSatellites.clear();
            GnssClock gnssClock = event.getClock();
            long TimeNanos = gnssClock.getTimeNanos();
            //timeRefMsec = new Time(System.currentTimeMillis());
            //double BiasNanos = gnssClock.getBiasNanos();
            double gpsTime, pseudorange;
            calculate_timeparas(gnssClock);//初始和当下FBnanos 初始和当下Bnanos
            // Use only the first instance of the FullBiasNanos (as done in gps-measurement-tools)
//            if (!fullBiasNanosInitialized) {
            //FullBiasNanos = gnssClock.getFullBiasNanos();
//                fullBiasNanosInitialized = true;
//            }+

            // Start computing the pseudoranges using the raw data from the phone's GNSS receiver
            for (GnssMeasurement measurement : event.getMeasurements()) {

                if (measurement.getConstellationType() != constellationId)
                    continue;

                if (measurement.hasCarrierFrequencyHz())
                   if (!approximateEqual(measurement.getCarrierFrequencyHz(), B1_FREQUENCY, FREQUENCY_MATCH_RANGE))
                       continue;
                long ReceivedSvTimeNanos = measurement.getReceivedSvTimeNanos();
                double TimeOffsetNanos = measurement.getTimeOffsetNanos();
                gpsTime = TimeNanos + (FullBiasNanos + BiasNanos);
                tRxBDS = gpsTime + TimeOffsetNanos- 14e9;
                pseudorange = calculate_pseudorange(gnssClock.getTimeNanos(),measurement);
                weekNumberNanos = Math.floor((-1. * FullBiasNanos) / GNSSConstants.NUMBER_NANO_SECONDS_PER_WEEK)
                        * GNSSConstants.NUMBER_NANO_SECONDS_PER_WEEK;
                update_phase_corrections(measurement,TimeNanos);
                int measState = measurement.getState();//Gets per-satellite sync state.
                boolean BIT_sync = (measState & GnssMeasurement.STATE_BIT_SYNC) != 0;
                boolean SYMBOL_sync = (measState & GnssMeasurement.STATE_SYMBOL_SYNC) != 0;
                boolean BDS_D2_BIT_SYNC = (measState & GnssMeasurement.STATE_BDS_D2_BIT_SYNC) != 0;//BDS D2电文 GEO 播发 非GEO 播发 D1电文
                boolean SUBFRAME_sync = (measState & GnssMeasurement.STATE_SUBFRAME_SYNC) != 0;
                boolean BDS_D2_SUBFRAME_SYNC = (measState & GnssMeasurement.STATE_BDS_D2_SUBFRAME_SYNC) != 0;//BDS D2电文 GEO 播发 非GEO 播发 D1电文
                boolean codeLock = (measState & GnssMeasurement.STATE_CODE_LOCK) != 0;
                boolean towDecoded = (measState & GnssMeasurement.STATE_TOW_DECODED) != 0;
                boolean towKnown      = false;
                boolean MSEC_AMBIGUOUS = (measState & GnssMeasurement.STATE_MSEC_AMBIGUOUS) != 0;// ！=0 说明模糊。
                boolean receivedtimes_uncertain = (measurement.getReceivedSvTimeUncertaintyNanos()>10000L? true:false);
                boolean no_multipath = (measurement.getMultipathIndicator()== GnssMeasurement.MULTIPATH_INDICATOR_DETECTED ? false:true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // 判断当前执行的版本是不是大于ANdroid O
                    // 低于android o 不用改towKnown？
                    towKnown = (measState & GnssMeasurement.STATE_TOW_KNOWN) != 0;
                }
                if ( (BIT_sync||BDS_D2_BIT_SYNC)&&SYMBOL_sync&&(SUBFRAME_sync||BDS_D2_SUBFRAME_SYNC)&&
                        no_multipath&&(!receivedtimes_uncertain)&&(!MSEC_AMBIGUOUS)&&codeLock && (towDecoded || towKnown)  && pseudorange < 1e8)
                //if(true)
                {
                        //boolean towUncertainty = measurement.getReceivedSvTimeUncertaintyNanos() < MAXTOWUNCNS;
                        SatelliteParameters satelliteParameters = new SatelliteParameters(new GpsTime(gnssClock),
                                measurement.getSvid(),
                                new Pseudorange(pseudorange, 0.0));

                        satelliteParameters.setUniqueSatId("C" + satelliteParameters.getSatId() + "_B1");

                        satelliteParameters.setSignalStrength(measurement.getCn0DbHz());

                        satelliteParameters.setConstellationType(measurement.getConstellationType());

                        if (measurement.hasCarrierFrequencyHz())
                            satelliteParameters.setCarrierFrequency(measurement.getCarrierFrequencyHz());

                        /**
                         * 获取载波相位观测值
                         */

                        double ADR = measurement.getAccumulatedDeltaRangeMeters();
                        double λ = GNSSConstants.SPEED_OF_LIGHT / B1_FREQUENCY;
                        double phase = ADR / λ;
                        satelliteParameters.setPhase(phase);
                        double corr_phase = calculate_phase(measurement);
                        satelliteParameters.setPhase(corr_phase);
                        String LLI = calculate_LLI(measurement);
                        satelliteParameters.setLLI(LLI);

                        /**
                         获取SNR
                         */
                        if (measurement.hasSnrInDb()) {
                            satelliteParameters.setSnr(measurement.getSnrInDb());
                        }
                        /**
                         获取多普勒值
                         */
                        double doppler = -measurement.getPseudorangeRateMetersPerSecond() / λ;
                        satelliteParameters.setDoppler(doppler);


                        observedSatellites.add(satelliteParameters);
//                        Log.d(TAG, "updateConstellations(" + measurement.getSvid() + "): " + weekNumberNanos + ", " + tRxBDS + ", " + pseudorange);
//                        Log.d(TAG, "updateConstellations: Passed with measurement state: " + measState);
//                        Log.d(TAG, "Time: " + satelliteParameters.getGpsTime().getGpsTimeString()+",phase"+satelliteParameters.getPhase()+",snr"+satelliteParameters.getSnr()+",doppler"+satelliteParameters.getDoppler());
//

                    }
                else {// 意思是伪距不可用？所以没有添加。相应的 ADR 也没有添加，意思是可见但不可用。
                    SatelliteParameters satelliteParameters = new SatelliteParameters(new GpsTime(gnssClock),
                            measurement.getSvid(),
                            null);

                    satelliteParameters.setUniqueSatId("C" + satelliteParameters.getSatId() + "_B1");

                    satelliteParameters.setSignalStrength(measurement.getCn0DbHz());

                    satelliteParameters.setConstellationType(measurement.getConstellationType());

                    if (measurement.hasCarrierFrequencyHz())
                        satelliteParameters.setCarrierFrequency(measurement.getCarrierFrequencyHz());

                    unusedSatellites.add(satelliteParameters);
                    visibleButNotUsed++;
                }
            }
            copy_and_free();
        }
    }

    @Override
    public double getSatelliteSignalStrength(int index) {
        synchronized (this) {
            return observedSatellites.get(index).getSignalStrength();
        }
    }

    @Override
    public int getConstellationId() {
        synchronized (this) {
            return constellationId;
        }
    }

    @Override
    public SatelliteParameters getSatellite(int index) {
        synchronized (this) {
            return observedSatellites.get(index);
        }
    }

    @Override
    public List<SatelliteParameters> getSatellites() {
        synchronized (this) {
            return observedSatellites;
        }
    }

    @Override
    public int getVisibleConstellationSize() {
        synchronized (this) {
            return getUsedConstellationSize() + visibleButNotUsed;
        }
    }

    @Override
    public int getUsedConstellationSize() {
        synchronized (this) {
            return observedSatellites.size();
        }
    }
    private long GPStimeround(long gpsTime){//计算截位时间
        double d = 2.0D;
        long Asecinnanos = (long)Math.pow(10.0D,d);// 1s 中的 ns数
        long decimal_secinnanos =  gpsTime%Asecinnanos;
        long interger_secinnanos = gpsTime-decimal_secinnanos;
        long roundtime = interger_secinnanos ;
        if (decimal_secinnanos >= (long)Math.pow(10.0D, (d-1)) * 5.0D)//超过半s
            roundtime = interger_secinnanos + (long)Math.pow(10.0D, d);//加一s
        return roundtime;
    }
    void calculate_timeparas(GnssClock gnssClock){
        if (FirstfullBiasNanosisnotInitialized) {
            FirstFullBiasNanos = Math.abs(gnssClock.getFullBiasNanos());
            FirstfullBiasNanosisnotInitialized = false;
        }
        FullBiasNanos = Math.abs(gnssClock.getFullBiasNanos());
        if(gnssClock.hasBiasNanos()) {
            if (FirstBiasNanosisnotInitialized) {
                FirstBiasNanos = Math.abs(gnssClock.getBiasNanos());
                FirstBiasNanosisnotInitialized = false;
            }
            BiasNanos = Math.abs(gnssClock.getBiasNanos());
        }
        long TimeNanos = gnssClock.getTimeNanos();
        clip_delta_timeo =calculate_clip_delta_time(TimeNanos,FullBiasNanos);
        clip_addcFB_subfFBq = clip_delta_timeo+FullBiasNanos-FirstFullBiasNanos;
        if(gnssClock.hasBiasNanos()) {
            cB_addcB_subfBr = BiasNanos+BiasNanos-FirstBiasNanos;
        }

    }
    long calculate_clip_delta_time(long TimeNanos, long current_FBNanos ){//计算截位时间与真实时间之差
        return GPStimeround(TimeNanos + current_FBNanos)-(TimeNanos + current_FBNanos);// this.o
    }
    double calculate_pseudorange(long TimeNanos,GnssMeasurement measurement){
        //BiasNanos = 0.0;//似乎 无论怎么读这个值都是0
        long ReceivedSvTimeNanos = measurement.getReceivedSvTimeNanos();
        double TimeOffsetNanos = measurement.getTimeOffsetNanos();
        //long gpsTimeround = GPStimeround(TimeNanos + FullBiasNanos);//返回最靠近的截位数
        long l1 =(TimeNanos+Math.abs(FullBiasNanos))/604800000000000L*604800000000000L+ReceivedSvTimeNanos;
        long l3 = TimeNanos+Math.abs(FullBiasNanos);
        long paramLong = l1;
        if(l1>l3)
            paramLong = l1-604800000000000L;
        //long l = gpsTimeround- (long )(Math.abs(TimeNanos)+Math.abs(FullBiasNanos));
        long l =  calculate_clip_delta_time(TimeNanos,FullBiasNanos);
        //Log.d(TAG,"l: "+String.valueOf(l));
        //Log.d(TAG,"l: "+String.valueOf(TimeOffsetNanos));
        double pseudorange = ((l3 - paramLong) + Math.abs(BiasNanos) + TimeOffsetNanos) * 0.299792458D;
        pseudorange = pseudorange + l*0.299792458D+Math.abs(BiasNanos)*0.299792458D-0.299792458D*14e9;//BDS 与GPS时相差14s
        return pseudorange;
    }
    void update_phase_corrections(GnssMeasurement measurement,long TimeNanos){
        byte b = approximateEqual(measurement.getCarrierFrequencyHz(), B1_FREQUENCY, FREQUENCY_MATCH_RANGE)? (byte)10:(byte)15;
        // L1 对应子map的key值为10
        // L5 对应子map的key值为15
        if (!phase_corrections_hash.containsKey(measurement.getSvid())){//还未增加当前星的校正量
            HashMap<Byte,Long> sub_hashMap = new HashMap<>();
            long pseudorange_sub_noPeudocorrADR_c =calculate_pr_sub_noPeudocorrADR_c(measurement,TimeNanos);
            sub_hashMap.put(Byte.valueOf(b), Long.valueOf(pseudorange_sub_noPeudocorrADR_c));
            phase_corrections_hash.put(Integer.valueOf(measurement.getSvid()), sub_hashMap);
        }
        else{
            HashMap<Byte, Long> sub_hashMap = phase_corrections_hash.get(Integer.valueOf(measurement.getSvid()));
            double current_pr_m = calculate_pseudorange(TimeNanos,measurement);
            double Pseudo_corr_ADR_c = calculate_phase(measurement);
            double Pseudo_corr_ADR_m = Pseudo_corr_ADR_c*calculate_lambda(measurement);
            if (!sub_hashMap.containsKey(Byte.valueOf(b)) || Math.abs(current_pr_m - Pseudo_corr_ADR_m) > 50.0D) {
                long pseudorange_sub_noPeudocorrADR_c =calculate_pr_sub_noPeudocorrADR_c(measurement,TimeNanos);
                sub_hashMap.put(Byte.valueOf(b), Long.valueOf(pseudorange_sub_noPeudocorrADR_c));
            }
        }

    }
    long calculate_pr_sub_noPeudocorrADR_c(GnssMeasurement measurement,long TimeNanos){
        double pr_c = calculate_pseudorange(TimeNanos,measurement)/calculate_lambda(measurement);// 伪距转cycle
        double noPeudocorrADR_c =  calculate_nopseudocorrection_phase(measurement);
        return (long)(pr_c-noPeudocorrADR_c);//作为补偿整周模糊度原则出现，不能够是double？
    }
    double calculate_phase(GnssMeasurement measurement){
        if((measurement.getAccumulatedDeltaRangeState()&0x1)!=1){
            return 0;// 增加一个相位有效性判断
        }
        double clip_corr_ADRc = calculate_nopseudocorrection_phase(measurement);
        double Pseudo_corr_ADRc = clip_corr_ADRc;
        double Pseudo_corrections = 0.0D;
        if(phase_corrections_hash!=null){//不为空 就加修正值
            Pseudo_corrections = extract_Pseudo_corrections(measurement);
        }
        Pseudo_corr_ADRc+=Pseudo_corrections;
        return Pseudo_corr_ADRc;
        // 考虑L5Q 相移问题
    }
    double extract_Pseudo_corrections(GnssMeasurement measurement){//是从早已准备好的hashmap里提取校正量
        if (phase_corrections_hash.containsKey(Integer.valueOf(measurement.getSvid()))) {
            byte b = approximateEqual(measurement.getCarrierFrequencyHz(), B1_FREQUENCY, FREQUENCY_MATCH_RANGE)? (byte)10:(byte)15;
            HashMap sub_hashMap = phase_corrections_hash.get(Integer.valueOf(measurement.getSvid()));
            if (sub_hashMap.containsKey(Byte.valueOf(b)))
                return ((Long)sub_hashMap.get(Byte.valueOf(b))).longValue();
        }
        return Double.NaN;
    }

    double calculate_nopseudocorrection_phase(GnssMeasurement measurement){
        double lambda = calculate_lambda(measurement);
        double original_ADRc = measurement.getAccumulatedDeltaRangeMeters()/lambda;//原始ADR in cycles
        double clip_corr_ADRc = original_ADRc+ (clip_addcFB_subfFBq+cB_addcB_subfBr)*0.299792458D/lambda;//校正截位时间差的 ADR in cycles
        return clip_corr_ADRc;
    }
    double calculate_lambda(GnssMeasurement measurement){
        //if((Build.MODEL.contains("Pixel 4") || Build.MODEL.contains("Pixel4") || Build.MODEL.contains("pixel 4") || Build.MODEL.contains("pixel4"))){
        //return 2.99792458E8D / measurement.getCarrierFrequencyHz();
            return 0.19203948631027648D;
        //}
        //else// 只考虑GPSt
        //{
        //    return approximateEqual(measurement.getCarrierFrequencyHz(), L1_FREQUENCY, FREQUENCY_MATCH_RANGE)? 0.19029367279836487D:0.25482804879085386D;

        //}
    }

    String calculate_LLI(GnssMeasurement measurement){
        String LLI = " ";
        Byte curr_b = approximateEqual(measurement.getCarrierFrequencyHz(), B1_FREQUENCY, FREQUENCY_MATCH_RANGE)? (byte)10:(byte)15;//获得当前频点代号
        int status = measurement.getAccumulatedDeltaRangeState();
        boolean ADR_valid = (status & GnssMeasurement.ADR_STATE_VALID)!=0;
        boolean ADR_reset = (status & GnssMeasurement.ADR_STATE_RESET)!=0;
        boolean cycle_slip = (status & GnssMeasurement.ADR_STATE_CYCLE_SLIP)!=0;
        if(ADR_valid){
            if(ADR_reset || cycle_slip){//相位有效 但是 出现reset 和 cycle slip
                LLI ="1";
            }
            else {
                if(!pre_phase_valid_map.containsKey(measurement.getSvid())){//当前有效，但前一时刻的有效信号中没有当前星
                    LLI ="1";
                }
                else {
                    HashSet<Byte> curr_sub_set = pre_phase_valid_map.get(measurement.getSvid());//获得前一时刻的当前星
                    if(!curr_sub_set.contains(curr_b))// 如果包含当前星但不包含当前频点
                        LLI = "1";
                }
            }
            // 以上确定LLI
            // 以下确定当前的cur_phase_valid_map
            if(!cur_phase_valid_map.containsKey(measurement.getSvid())){
                HashSet<Byte> sub_set = new HashSet<>();// 必须相位有效才能添加
                sub_set.add(curr_b);
                cur_phase_valid_map.put(measurement.getSvid(),sub_set);//压入当前的map
            }//当前map 不包含当前星 添加当期星和频点
            else {
                HashSet<Byte> sub_set = cur_phase_valid_map.get(measurement.getSvid());//获得当前时刻的当前星
                if(!sub_set.contains(curr_b))// 如果包含当前星但不包含当前频点
                {
                    sub_set.add(curr_b);//频点添加进入
                    cur_phase_valid_map.put(measurement.getSvid(),sub_set);//压入当前的map，会取代原来的值
                }
            }
        }
        // 相位无效啥也不干，既不添加 也不置位LLI。
        return LLI;
    }
    void copy_and_free(){
        pre_phase_valid_map.clear();
        for (Map.Entry<Integer, HashSet<Byte>> entry : cur_phase_valid_map.entrySet()){
            pre_phase_valid_map.put(entry.getKey(),entry.getValue());
        }
        cur_phase_valid_map.clear();//当前时刻有效信号清空
    }
}
