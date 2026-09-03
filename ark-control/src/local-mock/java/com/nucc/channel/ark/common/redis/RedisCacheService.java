package com.nucc.channel.ark.common.redis;

import com.nucc.channel.ark.common.util.ResultUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

/**
 * 使用Jedis原生连接redis哨兵
 *
 */
@Slf4j
@Service
public class RedisCacheService {

    //模拟redis是否可用。 true:redis可用；false:redis不可用。
    private static boolean redisFlag = true;

    /**
     * 用于xml注入
     */
    private JedisSentinelPool jedisSentinelPool;

    public void setJedisSentinelPool(JedisSentinelPool jedisSentinelPool) {
        this.jedisSentinelPool = jedisSentinelPool;
    }

    /**
     * 设置redis指定过期时间
     * @param key
     * @param time
     * @param value
     * @return
     */
    public String setex(String key, int time, String value) {
        try {
            Jedis jedis = jedisSentinelPool.getResource();
            String ret = jedis.setex(key,time,value);
            log.info("setex result={}", ret);
            jedis.close();
            return ResultUtil.SUCCESS_RESULT;
        } catch (Exception e) {
            log.error("setex to redis error,key={},e={} ",key,e);
        }
        return ResultUtil.FAIL_RESULT;
    }

    public String get(String key) {
        try {
            if (!redisFlag){
                log.info("mock get redis is not ok");
                return ResultUtil.FAIL_RESULT;
            }
            Jedis jedis = jedisSentinelPool.getResource();
            String ret = jedis.get(key);
            jedis.close();
            return ret;
        } catch (Exception e) {
            log.error("get from redis error,{} ", e);
        }
        return ResultUtil.FAIL_RESULT;
    }

    public String del(String key){
        try {
            Jedis jedis = jedisSentinelPool.getResource();
            jedis.del(key);
            jedis.close();
            return ResultUtil.SUCCESS_RESULT;
        } catch (Exception e) {
            log.error("del to redis error,key={},e={} ",key,e);
        }
        return ResultUtil.FAIL_RESULT;
    }

    /**
     * 设置redis 存储值+increment
     * @param key
     * @param increment
     * @return
     */
    public Long incrBy(String key, Long increment) {
        Long ret=Long.valueOf(-1L);
        try {
            Jedis jedis = jedisSentinelPool.getResource();

            if(increment==null){
                ret = jedis.incr(key);
            }else{
                ret = jedis.incrBy(key,increment);
            }
            log.info("incrBy result={}", ret);
            jedis.close();
            return ret;
        } catch (Exception e) {
            log.error("incrBy to redis error,key={},e={} ",key,e);
        }
        return ret;
    }

    /**
     * 此方法大并发时不生效，system本地时间有误差,此方法兼容redis出现故障，故障时直接返回true
     * @param key
     * @param circle 周期 单位秒
     * @param count 计数
     * @return
     */
    public boolean ratelimit(String key, Long circle,Long count) {
        Long now=System.currentTimeMillis();
        Response<Long> exsitCnt=null;
        try {
            Jedis jedis = jedisSentinelPool.getResource();
            Pipeline pipeline=jedis.pipelined();
            pipeline.zadd(key,now,String.valueOf(now));
            pipeline.zremrangeByScore(key,0,now-circle*1000);
            exsitCnt=pipeline.zcard(key);
            pipeline.expire(key,circle.intValue());
            pipeline.sync();
            log.info("exsitCnt result={}", exsitCnt.get());
            jedis.close();
            return exsitCnt==null?true:exsitCnt.get()<=count;
        } catch (Exception e) {
            log.error("ratelimit redis error,key={} ",key,e);
        }
        return true;
    }
}

