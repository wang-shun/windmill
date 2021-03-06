package io.windmill.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.windmill.core.Status.Flag;
import io.windmill.core.tasks.Task1;
import io.windmill.net.io.InputStream;
import io.windmill.net.io.OutputStream;
import io.windmill.utils.Futures;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.junit.Assert;
import org.junit.Test;

public class CPUTest extends AbstractTest
{
    @Test
    public void testLoop()
    {
        CountDownLatch latch = new CountDownLatch(10);

        CPUs.get(0).repeat((cpu) -> {
            try
            {
                return Futures.constantFuture(cpu, Status.of(latch.getCount() == 0 ? Flag.STOP : Flag.CONTINUE));
            }
            finally
            {
                latch.countDown();
            }
        });

        Futures.awaitUninterruptibly(latch);
    }

    @Test
    public void testListen() throws Exception
    {
        CPUs.get(0).listen(new InetSocketAddress("localhost", 31337), (c) -> {
            InputStream input = c.getInput();
            OutputStream output = c.getOutput();

            c.loop((cpu) -> input.readInt()
                                 .flatMap(input::read)
                                 .map((msg) -> {
                                     int sum = 0;
                                     while (msg.readableBytes() > 0)
                                         sum += msg.readInt();
                                     return output.writeInt(sum).flush();
                                 }));
        }, Throwable::printStackTrace);


        try (Socket client = new Socket("localhost", 31337))
        {
            client.setTcpNoDelay(true);

            byte[] response = new byte[4];
            for (int i = 0; i < 10; i++)
            {
                ByteBuf request = getRequest(new int[] { i, i + 1, i + 2 });
                request.readBytes(client.getOutputStream(), request.readableBytes());

                java.io.InputStream in = client.getInputStream();

                Assert.assertEquals(4, in.read(response));
                Assert.assertEquals(3 * i + 3, Unpooled.wrappedBuffer(response).readInt());
            }
        }
    }

    @Test
    public void testListenWithConsumer() throws Exception
    {
        CPUs.get(0).listen(new InetSocketAddress("localhost", 31339), (channel) -> {
            InputStream input = channel.getInput();
            OutputStream output = channel.getOutput();

            Task1<ByteBuf, Status<byte[]>> consumer = (buffer) -> {
                if (buffer.readableBytes() < 4)
                    return Status.of(Flag.CONTINUE);

                buffer.markReaderIndex();

                int length = buffer.readInt();
                if (buffer.readableBytes() < length)
                {
                    buffer.resetReaderIndex();
                    return Status.of(Flag.CONTINUE);
                }

                byte[] payload = new byte[length];
                buffer.readBytes(payload);

                return Status.of(Flag.STOP, payload);
            };

            channel.loop((cpu) -> input.read(consumer)
                                       .flatMap((payload) -> output.writeInt(payload.length)
                                                                   .writeBytes(payload)
                                                                   .flush()));
        }, Throwable::printStackTrace);

        ThreadLocalRandom random = ThreadLocalRandom.current();

        try (Socket client = new Socket("localhost", 31339))
        {
            DataInputStream input = new DataInputStream(client.getInputStream());
            DataOutputStream output = new DataOutputStream(client.getOutputStream());

            for (int i = 0; i < 20; i++)
            {
                int length = random.nextInt(1, 1024);
                byte[] request = new byte[length];
                random.nextBytes(request);

                output.writeInt(length);
                output.flush(); // flush right after size to trigger CONTINUE behavior of the consumer

                for (int j = 0; j < request.length; j++)
                {
                    output.writeByte(request[j]);

                    // let's do random flushes and slow down client to trigger random
                    // behavior on the server consumer side
                    if (j % random.nextInt(3, 10) == 0)
                    {
                        Futures.sleepUninterruptibly(100, TimeUnit.MICROSECONDS);
                        output.flush();
                    }
                }

                output.flush();

                int responseLength = input.readInt();
                byte[] response = new byte[responseLength];

                int read = input.read(response);

                Assert.assertEquals(responseLength, read);
                Assert.assertEquals(length, responseLength);
                Assert.assertArrayEquals(request, response);
            }
        }
    }

    @Test
    public void testSleep()
    {
        CPU cpu = CPUs.get(0);

        long now = System.nanoTime();
        AtomicInteger counts = new AtomicInteger(0);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < 5; i++)
        {
            long delay = random.nextInt(10, 50);
            cpu.sleep(delay, TimeUnit.MILLISECONDS, () -> {
                long n = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - now);
                Assert.assertTrue(n >= delay);
                return counts.incrementAndGet();
            });

            // also insert couple of empty tasks to make
            // sure that sleep works with other tasks around
            cpu.schedule(() -> 2 + 2);
        }

        cpu.sleep(500, TimeUnit.MILLISECONDS, counts::incrementAndGet);

        Futures.sleepUninterruptibly(250, TimeUnit.MILLISECONDS);
        Assert.assertEquals(5, counts.get());

        // sleep a bit for to get 6th count
        Futures.sleepUninterruptibly(500, TimeUnit.MILLISECONDS);
        Assert.assertEquals(6, counts.get());
    }

    @Test
    public void testSequencing() throws Throwable
    {
        List<Future<Integer>> futures = new ArrayList<Future<Integer>>()
        {{
            for (int i = 0; i < 5; i++)
            {
                int currentIndex = i;
                add(i % 2 == 0
                        ? Futures.constantFuture(CPUs.get(0), currentIndex)
                        : CPUs.get(2).schedule(() -> currentIndex));
            }
        }};

        List<Integer> result = Futures.await(CPUs.get(0).sequence(futures));

        Assert.assertEquals(5, result.size());
        Assert.assertEquals(Arrays.asList(0, 1, 2, 3, 4), result);
    }

    @Test (expected = IllegalArgumentException.class)
    public void testSequencingWithFailures() throws Throwable
    {
        List<Future<Integer>> futures = new ArrayList<Future<Integer>>()
        {{
            add(Futures.constantFuture(CPUs.get(0), 0));
            add(Futures.failedFuture(CPUs.get(2), new IllegalArgumentException()));
            add(Futures.constantFuture(CPUs.get(2), 1));
        }};

        Futures.await(CPUs.get(0).sequence(futures));
    }

    private static ByteBuf getRequest(int[] numbers)
    {
        ByteBuf request = Unpooled.buffer(4 + numbers.length * 4);

        request.writeInt(request.capacity() - 4);
        for (int n : numbers)
            request.writeInt(n);

        return request;
    }
}
