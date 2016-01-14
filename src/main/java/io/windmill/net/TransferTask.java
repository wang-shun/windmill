package io.windmill.net;

import java.nio.channels.ClosedChannelException;

import io.windmill.core.Future;

import io.netty.buffer.ByteBuf;

public abstract class TransferTask<I, O> implements AutoCloseable
{
    protected final ByteBuf buffer;
    protected final Future<O> onComplete;

    public TransferTask(ByteBuf buffer, Future<O> onComplete)
    {
        this.buffer = buffer;
        this.onComplete = onComplete;
    }

    public abstract boolean compute(I channel);

    public Future<O> getFuture()
    {
        return onComplete;
    }

    @Override
    public void close()
    {
        buffer.release();
        onComplete.setFailure(new ClosedChannelException());
    }
}
