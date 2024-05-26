package com.pang.aidada;

import io.reactivex.Flowable;
import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

@SpringBootTest
public class RxJavaTest {

    @Test
    public void testRxJava() throws InterruptedException {
        // 创建一个流
        Flowable<Integer> flowable = Flowable.range(0,10)
                //  设置每秒发送一次数据
                // .interval(1, TimeUnit.SECONDS)
                .map(i -> i + 1)
                .subscribeOn(Schedulers.io());
        // 订阅 Flowable 流
        flowable.observeOn(Schedulers.io())
                // 被观察者每发送一次数据，就会触发此事件
                .doOnNext(item -> System.out.println("Received: " + item))
                // 如果发送数据过程中产生意料之外的错误，那么被观察者可以发送此事件
                .doOnError(throwable -> System.out.println("Error: " + throwable.getMessage()))
                // 如果没有发生错误，那么被观察者在最后一次调用 onNext 之后发送此事件表示完成数据传输
                .doOnComplete(() -> System.out.println("Completed"))
                .subscribe();
        // 让主线程睡眠，以便观察输出
        Thread.sleep(13000);

    }
}
