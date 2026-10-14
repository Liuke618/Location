package com.yybb.location.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * PositionPublisher 的 JVM 单测（不触碰任何 Android API）。
 * 被测语义来自 ServiceGo：模拟位置的三个轴（经/纬/海拔）由主线程写
 * （onStartCommand 的 intent extra、ServiceGoBinder.setPosition）、HandlerThread 读
 * （setLocationGPS / setLocationNetwork 每 tick 各读一次）。三个独立的 double 字段
 * 之间存在两种串样：一次读跨越两次写（新经度配旧纬度），以及 double 写本身不保证原子。
 * 二者都等于"把用户没选过的坐标瞬移过去"，故发布必须是一次性替换一个自洽快照。
 */
public class PositionPublisherTest {

    @Test
    public void currentBeforeAnyPublish_returnsBuiltInDefaults() {
        // 默认点即原 ServiceGo 的 DEFAULT_*（进程被回收后重启、intent 无 extra 时的供给位置）
        PositionPublisher.Snapshot current = new PositionPublisher().current();

        assertEquals(PositionPublisher.DEFAULT_LNG, current.lng, 0.0);
        assertEquals(PositionPublisher.DEFAULT_LAT, current.lat, 0.0);
        assertEquals(PositionPublisher.DEFAULT_ALT, current.alt, 0.0);
    }

    @Test
    public void publish_replacesAllThreeAxes() {
        PositionPublisher publisher = new PositionPublisher();

        publisher.publish(116.397428, 39.90923, 45.0);

        PositionPublisher.Snapshot current = publisher.current();
        assertEquals(116.397428, current.lng, 0.0);
        assertEquals(39.90923, current.lat, 0.0);
        assertEquals(45.0, current.alt, 0.0);
    }

    @Test
    public void publishFromAnotherThread_isVisibleToReader() throws Exception {
        // 服务侧真实线程模型：主线程写、供给线程读，写必须对读线程可见
        //（缺可见性保证时，停止/传送标记可能永不可见，服务一直在供给旧坐标）
        final PositionPublisher publisher = new PositionPublisher();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> publisher.publish(121.4737, 31.2304, 4.0)).get();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        PositionPublisher.Snapshot current = publisher.current();
        assertEquals(121.4737, current.lng, 0.0);
        assertEquals(31.2304, current.lat, 0.0);
        assertEquals(4.0, current.alt, 0.0);
    }

    @Test(timeout = 30_000)
    public void concurrentPublish_neverExposesMixedAxes() throws Exception {
        // 每个写入方发布的三元组都自洽（三轴同值）；三轴不同且不是内置默认点才是串样。
        // 串样例留到断言消息里，便于区分"新经度配旧纬度"与 double 位撕裂。
        final int writerCount = 2;
        final int publishesPerWriter = 50_000;
        final PositionPublisher publisher = new PositionPublisher();
        final AtomicInteger readCount = new AtomicInteger();
        final AtomicReference<String> mixedObservation = new AtomicReference<>();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(writerCount);
        ExecutorService pool = Executors.newFixedThreadPool(writerCount + 1);
        try {
            for (int writer = 0; writer < writerCount; writer++) {
                final double base = writer * 1_000_000.0;
                pool.execute(() -> {
                    awaitQuietly(startGate);
                    for (int i = 1; i <= publishesPerWriter; i++) {
                        double value = base + i;
                        publisher.publish(value, value, value);
                    }
                    writersDone.countDown();
                });
            }

            pool.execute(() -> {
                awaitQuietly(startGate);
                while (writersDone.getCount() > 0 && mixedObservation.get() == null) {
                    PositionPublisher.Snapshot snapshot = publisher.current();
                    readCount.incrementAndGet();
                    if (snapshot.lat != snapshot.lng || snapshot.lat != snapshot.alt) {
                        // 内置默认点三轴本就互不相同，但它是不可变快照、自洽而非串样；
                        // 读线程抢在首次 publish 前到达时观察到的就是它，不能误报
                        boolean builtInDefault = snapshot.lng == PositionPublisher.DEFAULT_LNG
                                && snapshot.lat == PositionPublisher.DEFAULT_LAT
                                && snapshot.alt == PositionPublisher.DEFAULT_ALT;
                        if (!builtInDefault) {
                            mixedObservation.compareAndSet(null, describe(snapshot));
                            return;
                        }
                    }
                }
            });

            startGate.countDown();
            assertTrue("写入方超时", writersDone.await(25, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertTrue("读线程未观察到任何快照，测试空转", readCount.get() > 0);
        String mixed = mixedObservation.get();
        assertTrue("检测到三轴串样的位置快照：" + mixed + "（读取 " + readCount.get() + " 次）", mixed == null);
    }

    private static String describe(PositionPublisher.Snapshot snapshot) {
        return "lng=" + snapshot.lng + " lat=" + snapshot.lat + " alt=" + snapshot.alt;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
