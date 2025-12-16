package plugin.javafxtools.util;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 时间工具类 - 提供统一的时间格式化和管理功能
 */
public class TimeUtils {

    // 私有构造方法防止实例化
    private TimeUtils() {
    }

    // 常用的时间格式常量
    public static final String DEFAULT_DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final String DEFAULT_TIME_FORMAT = "HH:mm:ss.SSS";
    public static final String COMPACT_DATETIME_FORMAT = "yyyyMMddHHmmss";

    /**
     * 获取当前时间字符串（默认格式）
     *
     * @return 格式化后的当前时间字符串
     */
    public static String getCurrentDateTime() {
        return formatDateTime(new Date(), DEFAULT_DATETIME_FORMAT);
    }

    /**
     * 格式化时间为字符串（使用默认格式）
     *
     * @param date 要格式化的日期对象
     * @return 格式化后的时间字符串
     */
    public static String formatDateTime(Date date) {
        return formatDateTime(date, DEFAULT_DATETIME_FORMAT);
    }

    /**
     * 格式化时间为字符串
     *
     * @param date    要格式化的日期对象
     * @param pattern 时间格式模式
     * @return 格式化后的时间字符串
     */
    public static String formatDateTime(Date date, String pattern) {
        if (date == null || pattern == null || pattern.isEmpty()) {
            return "";
        }
        return new SimpleDateFormat(pattern).format(date);
    }

    /**
     * 解析时间字符串为Date对象
     *
     * @param dateString 时间字符串
     * @param pattern    时间格式模式
     * @return 解析后的Date对象，解析失败返回null
     */
    public static Date parseDateTime(String dateString, String pattern) {
        try {
            return new SimpleDateFormat(pattern).parse(dateString);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取当前时间戳（毫秒）
     *
     * @return 当前时间戳
     */
    public static long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    /**
     * 计算两个时间点之间的时间差（毫秒）
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 时间差（毫秒）
     */
    public static long calculateDuration(Date start, Date end) {
        if (start == null || end == null) {
            return 0;
        }
        return end.getTime() - start.getTime();
    }

    /**
     * 格式化时间差（毫秒）为可读字符串
     *
     * @param durationMillis 时间差（毫秒）
     * @return 格式化后的字符串，如 "1天 2小时 3分钟 4秒 567毫秒"
     */
    public static String formatDuration(long durationMillis) {
        long milliseconds = durationMillis % 1000;
        long seconds = (durationMillis / 1000) % 60;
        long minutes = (durationMillis / (1000 * 60)) % 60;
        long hours = (durationMillis / (1000 * 60 * 60)) % 24;
        long days = durationMillis / (1000 * 60 * 60 * 24);

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("天 ");
        if (hours > 0) sb.append(hours).append("小时 ");
        if (minutes > 0) sb.append(minutes).append("分钟 ");
        if (seconds > 0) sb.append(seconds).append("秒 ");
        if (milliseconds > 0 || sb.length() == 0) sb.append(milliseconds).append("毫秒");

        return sb.toString().trim();
    }
}