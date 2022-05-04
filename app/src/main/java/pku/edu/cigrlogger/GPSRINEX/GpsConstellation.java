package pku.edu.cigrlogger.GPSRINEX;



import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.os.Build;
import android.util.Log;

import pku.edu.cigrlogger.GPSRINEX.GNSSConstants;

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

public class GpsConstellation extends Constellation {

    private final static char satType = 'G';
    private static final String NAME = "GPS L1";
    private static final String TAG = "GpsConstellation";
    private static double L1_FREQUENCY = 1.57542e9;
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
    //private Coordinates rxPos;
    protected double tRxGPS;
    protected double weekNumberNanos;
    private List<SatelliteParameters> unusedSatellites = new ArrayList<>();

    public double getWeekNumber(){
        return weekNumberNanos;
    }

    public double gettRxGPS(){
        return tRxGPS;
    }

    private static final int constellationId = GnssStatus.CONSTELLATION_GPS;
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





    @Override
    public String getName() {
        synchronized (this) {
            return NAME;
        }
    }

    public static boolean approximateEqual(double a, double b, double eps){
        return Math.abs(a-b)<eps;// 为了确定频率
    }

    @Override
    public void updateMeasurements(GnssMeasurementsEvent event) {

        synchronized (this) {
            visibleButNotUsed = 0;
            observedSatellites.clear();
            unusedSatellites.clear();
            GnssClock gnssClock = event.getClock();
            long TimeNanos = gnssClock.getTimeNanos();
            //double BiasNanos = gnssClock.getBiasNanos();
            double gpsTime, pseudorange;
            // Use only the first instance of the FullBiasNanos (as done in gps-measurement-tools)
            calculate_timeparas(gnssClock);//初始和当下FBnanos 初始和当下Bnanos
            // Start computing the pseudoranges using the raw data from the phone's GNSS receiver
            for (GnssMeasurement measurement : event.getMeasurements()) {

                if (measurement.getConstellationType() != constellationId)//不处理其它卫星
                    continue;

                if (measurement.hasCarrierFrequencyHz())
                    if (!approximateEqual(measurement.getCarrierFrequencyHz(), L1_FREQUENCY, FREQUENCY_MATCH_RANGE))
                        continue;
                long ReceivedSvTimeNanos = measurement.getReceivedSvTimeNanos();
                double TimeOffsetNanos = measurement.getTimeOffsetNanos();

                // GPS Time generation (GSA White Paper - page 20)
                //gpsTime = TimeNanos - (FullBiasNanos + BiasNanos); // TODO intersystem bias?
                gpsTime = TimeNanos + (FullBiasNanos + BiasNanos);
                // Measurement time in full GPS time without taking into account weekNumberNanos(the number of
                // nanoseconds that have occurred from the beginning of GPS time to the current
                // week number)

                tRxGPS = gpsTime + TimeOffsetNanos;
                //tRxGPS = (double)gpsTimeround + TimeOffsetNanos;
                /*
                Log.d(TAG,"gpsTime: "+Double.toString(gpsTime));
                Log.d(TAG,"gpsroundtime: "+Double.toString(gpsTimeround));
                Log.d(TAG,"FullBiasNanos: "+String.valueOf(FullBiasNanos));
                Log.d(TAG,"BiasNanos: "+String.valueOf(BiasNanos));
                */
                pseudorange = calculate_pseudorange(gnssClock.getTimeNanos(),measurement);
                //pseudorange = (tRxGPS - weekNumberNanos - ReceivedSvTimeNanos) / 1.0E9 * GNSSConstants.SPEED_OF_LIGHT;


                weekNumberNanos = Math.floor((-1. * FullBiasNanos) / GNSSConstants.NUMBER_NANO_SECONDS_PER_WEEK)
                                * GNSSConstants.NUMBER_NANO_SECONDS_PER_WEEK;
                update_phase_corrections(measurement,TimeNanos);

                // TODO Check that the measurement have a valid state such that valid pseudoranges are used in the PVT algorithm

                /*

                According to https://developer.android.com/ the GnssMeasurements States required
                for GPS valid pseudoranges are:

                int STATE_CODE_LOCK         = 1      (1 << 0)
                int int STATE_TOW_DECODED   = 8      (1 << 3)

                */

                int measState = measurement.getState();//Gets per-satellite sync state.
                    // STATE_CODE_LOCK、STATE_UNKNOWN、STATE_BIT_SYNC、STATE_SUBFRAME_SYNC、STATE_TOW_DECODED
                // 子帧同步 比特同步 周内时解读 不同同步状态确定不同能精确到的时间范围。
                // Bitwise AND to identify the states
                boolean BIT_sync = (measState & GnssMeasurement.STATE_BIT_SYNC) != 0;
                boolean SYMBOL_sync = (measState & GnssMeasurement.STATE_SYMBOL_SYNC) != 0;
                boolean SUBFRAME_sync = (measState & GnssMeasurement.STATE_SUBFRAME_SYNC) != 0;
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
//                boolean towUncertainty = measurement.getReceivedSvTimeUncertaintyNanos() <  MAXTOWUNCNS;
//
                if ( BIT_sync&&SYMBOL_sync&&SUBFRAME_sync&&no_multipath&&
                        (!receivedtimes_uncertain)&&(!MSEC_AMBIGUOUS)&&codeLock && (towDecoded || towKnown)  && pseudorange < 1e8) { // && towUncertainty
                    SatelliteParameters satelliteParameters = new SatelliteParameters(new GpsTime(gnssClock),
                            measurement.getSvid(),
                            new Pseudorange(pseudorange, 0.0));// 这里会确定一个gpstime

                    satelliteParameters.setUniqueSatId("G" + satelliteParameters.getSatId() + "_L1");

                    satelliteParameters.setSignalStrength(measurement.getCn0DbHz());

                    satelliteParameters.setConstellationType(measurement.getConstellationType());

                    if(measurement.hasCarrierFrequencyHz())
                        satelliteParameters.setCarrierFrequency(measurement.getCarrierFrequencyHz());

                    /**
                     * 获取载波相位观测值
                     */

                    double ADR = measurement.getAccumulatedDeltaRangeMeters();
                    double λ = GNSSConstants.SPEED_OF_LIGHT / L1_FREQUENCY;
                    double phase = ADR / λ;
                   /*
                    if((measurement.getAccumulatedDeltaRangeState()&0x1)!=1){
                        phase = 0;
                    }
                    satelliteParameters.setPhase(phase);
                    */
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
                    double doppler = -measurement.getPseudorangeRateMetersPerSecond() / λ;// 负号
                    satelliteParameters.setDoppler(doppler);

                    observedSatellites.add(satelliteParameters);

                   // Log.d(TAG, "updateConstellations(" + measurement.getSvid() + "): " + weekNumberNanos + ", " + tRxGPS + ", " + pseudorange);
                   // Log.d(TAG, "updateConstellations: Passed with measurement state: " + measState);
                   // Log.d(TAG, "Time: " + satelliteParameters.getGpsTime().getGpsTimeString()+",phase"+satelliteParameters.getPhase()+",snr"+satelliteParameters.getSnr()+",doppler"+satelliteParameters.getDoppler());

                }
                else { // 意思是伪距不可用？所以没有添加。相应的 ADR 也没有添加，意思是可见但不可用。
                    SatelliteParameters satelliteParameters = new SatelliteParameters(new GpsTime(gnssClock),
                            measurement.getSvid(),
                            null);

                    satelliteParameters.setUniqueSatId("G" + satelliteParameters.getSatId() + "_L1");

                    satelliteParameters.setSignalStrength(measurement.getCn0DbHz());

                    satelliteParameters.setConstellationType(measurement.getConstellationType());

                    if(measurement.hasCarrierFrequencyHz())
                        satelliteParameters.setCarrierFrequency(measurement.getCarrierFrequencyHz());

                    unusedSatellites.add(satelliteParameters);
                    visibleButNotUsed++;
                    //Log.d(TAG, visibleButNotUsed+"---");
                    Log.d(TAG, "unvalid_svid："+measurement.getSvid());
                }
            }
            //
            copy_and_free();

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

    public void setObservedSatellites(List<SatelliteParameters> observedSatellites) {
        this.observedSatellites = observedSatellites;
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

    @Override
    public double getSatelliteSignalStrength(int index) {
        return observedSatellites.get(index).getSignalStrength();
    }
    private long GPStimeround(long gpsTime){//计算截位时间 伪距时间精度？
        double d = 2.0D;
        long Asecinnanos = (long)Math.pow(10.0D,d);// 1s 中的 ns数
        long decimal_secinnanos =  gpsTime%Asecinnanos;
        long interger_secinnanos = gpsTime-decimal_secinnanos;
        long roundtime = interger_secinnanos ;
        if (decimal_secinnanos >= (long)Math.pow(10.0D, (d-1)) * 5.0D)//超过半单位
            roundtime = interger_secinnanos + (long)Math.pow(10.0D, d);//加一单位
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
        //clip_addcFB_subfFBq = clip_delta_timeo;
        if(gnssClock.hasBiasNanos()) {
            cB_addcB_subfBr = BiasNanos+BiasNanos-FirstBiasNanos;
            //cB_addcB_subfBr = BiasNanos-FirstBiasNanos;
        }

    }
    long calculate_clip_delta_time(long TimeNanos, long current_FBNanos ){//计算截位时间与真实时间之差
        return GPStimeround(TimeNanos + current_FBNanos)-(TimeNanos + current_FBNanos);// this.o
    }
    double calculate_pseudorange(long TimeNanos,GnssMeasurement measurement){
        //BiasNanos = 0.0;// 似乎无论怎么读这个值都是0.0
        long ReceivedSvTimeNanos = measurement.getReceivedSvTimeNanos();
        double TimeOffsetNanos = measurement.getTimeOffsetNanos();
        //long gpsTimeround = GPStimeround(TimeNanos + FullBiasNanos);//返回最靠近的截位数
        long l1 =(TimeNanos+Math.abs(FullBiasNanos))/604800000000000L*604800000000000L+ReceivedSvTimeNanos;
        // FullBiasNanos 是负数，绝对值相当于-FullBiasNanos
        // ReceivedSvTimeNanos 是相对于每周 received GNSS satellite time 的起始时间
        // 前面一半先转成多少周，然后再乘周内ns数就获得了当前周的起始时，加上ReceivedSvTimeNanos 就是当前的发射时间
        long l3 = TimeNanos+Math.abs(FullBiasNanos);
        long paramLong = l1;
        if(l1>l3)
            paramLong = l1-604800000000000L;// 发射接收正好在两周的情况
        //long l = gpsTimeround- (long )(Math.abs(TimeNanos)+Math.abs(FullBiasNanos));
        long l =  calculate_clip_delta_time(TimeNanos,FullBiasNanos);// 这里做了对最后两位（ns级别的个位 十位）的四舍五入，取四舍五入与真实时间之差
        //Log.d(TAG,"l: "+String.valueOf(l));
        //Log.d(TAG,"l: "+String.valueOf(TimeOffsetNanos));
         double pseudorange = ((l3 - paramLong) + Math.abs(BiasNanos) + TimeOffsetNanos) * 0.299792458D;
         // l3 = TimeNanos+Math.abs(FullBiasNanos)+Math.abs(BiasNanos) 后两者都是负数，取绝对值相当于-，和白皮书一致，算接收时间
        //TimeOffsetNanos 是偏置时间
        // 以上求取都和白皮书基本一致
        pseudorange = pseudorange + l*0.299792458D+Math.abs(BiasNanos)*0.299792458D;
        //主要问题在这里，没有采用第一次捕获跟踪的Fullbiasnanos，而是做了四舍五入，取截位时间差进行补偿

        //  https://www.rokubun.cat/gnss-carrier-phase-nexus-9/
        // 另外就是还减了一次biasNanos，不过似乎这个值往往是0.
        // 这样可以保证伪距一定和GEO++ 一致
        return pseudorange;
    }
    void update_phase_corrections(GnssMeasurement measurement,long TimeNanos){
        byte b = approximateEqual(measurement.getCarrierFrequencyHz(), L1_FREQUENCY, FREQUENCY_MATCH_RANGE)? (byte)10:(byte)15;
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
            byte b = approximateEqual(measurement.getCarrierFrequencyHz(), L1_FREQUENCY, FREQUENCY_MATCH_RANGE)? (byte)10:(byte)15;
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
        Log.d(TAG,"当前频率 "+measurement.getCarrierFrequencyHz()+"当前卫星："+measurement.getSvid());
        //if((Build.MODEL.contains("Pixel 4") || Build.MODEL.contains("Pixel4") || Build.MODEL.contains("pixel 4") || Build.MODEL.contains("pixel4"))){
            //return 2.99792458E8D / measurement.getCarrierFrequencyHz();
        return 0.19029367279836487D;// 武汉大学栗广才博士建议，直接给出标称波长，不要使用getCarrierFrequencyHz计算，会造成相位漂移。
        //}
        //else// 只考虑GPS
        //{
        //    return approximateEqual(measurement.getCarrierFrequencyHz(), L1_FREQUENCY, FREQUENCY_MATCH_RANGE)? 0.19029367279836487D:0.25482804879085386D;

        //}
    }

    String calculate_LLI(GnssMeasurement measurement){
        String LLI = " ";
        Byte curr_b = approximateEqual(measurement.getCarrierFrequencyHz(), L1_FREQUENCY, FREQUENCY_MATCH_RANGE)? (byte)10:(byte)15;//获得当前频点代号




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
