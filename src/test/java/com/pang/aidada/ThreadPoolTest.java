package com.pang.aidada;

import io.reactivex.Flowable;
import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class ThreadPoolTest {

    @Test
    public void test() throws InterruptedException {
        Scheduler io = Schedulers.io();
        // 有可能会造成OOM
        while (true){
            io.scheduleDirect(() -> {
                System.out.println(Thread.currentThread().getName() + " print hello");
                // 模拟耗时操作
                try {
                    Thread.sleep(100000L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });
        }

    }
}
