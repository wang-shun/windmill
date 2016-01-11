package io.windmill.io;

import java.io.IOException;

@FunctionalInterface
public interface IOTask<O>
{
    O compute() throws IOException;
}