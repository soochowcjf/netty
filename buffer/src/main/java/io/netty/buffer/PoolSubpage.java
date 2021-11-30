/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    private final int memoryMapIdx;
    private final int runOffset;
    private final int pageSize;
    private final long[] bitmap;

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    int elemSize;
    private int maxNumElems;
    private int bitmapLength;
    private int nextAvail;
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        // 2048、2049 ~ 4095
        this.memoryMapIdx = memoryMapIdx;
        // 0、8k、16k ~ 2047*8k
        this.runOffset = runOffset;
        // 8k
        this.pageSize = pageSize;
        // pageSize >>> 10 = 8
        // 这里说明一下为什么long数组长度需要为8呢，
        // 因为在subpage上面分配内存时，最小的是16字节，而一个page的总大小是8k，8k/16就是总共有多少块内存片，再除以64就是需要多少的long才能标记
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            // 如果是16字节的话，那么8k/16=512
            maxNumElems = numAvail = pageSize / elemSize;
            nextAvail = 0;
            // 512/64=8
            bitmapLength = maxNumElems >>> 6;
            // 如果内存片大一些的话，那么呢，可能就只需要一个long就可以标记了
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        // 加入到head链表后
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        final int bitmapIdx = getNextAvail();
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        bitmap[q] |= 1L << r;

        if (-- numAvail == 0) {
            removeFromPool();
        }

        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            // 说明这个long所描述的64个内存段还有未分配的；
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;

        // 开始遍历bits上的64哥bit位的每个位置，从右往左
        for (int j = 0; j < 64; j ++) {
            // 用来判断该位置是否未分配
            if ((bits & 1) == 0) {
                // i=0
                // 假设baseVal=0 j=0 val=0        00000000 00000000 00000000 00000000
                // 假设baseVal=0 j=1 val=1        00000000 00000000 00000000 00000001
                // 假设baseVal=0 j=63 val=63      00000000 00000000 00000000 00111111

                // i=1
                // 假设baseVal=64 j=0 val=64      00000000 00000000 00000000 01000000
                // 假设baseVal=64 j=1 val=65      00000000 00000000 00000000 01000001
                // 假设baseVal=64 j=63 val=127    00000000 00000000 00000000 01111111

                // i=7
                // 假设baseVal=448 j=0 val=448    00000000 00000000 00000001 11000000
                // 假设baseVal=448 j=1 val=449    00000000 00000000 00000001 11000001
                // 假设baseVal=448 j=63 val=511   00000000 00000000 00000001 11111111
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            // 右移一位
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        // 0x4000000000000000L | 0 | 2048
        // 1000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000   0x4000000000000000L
        // 0000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000   0

        // 1000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000
        // 0000000 00000000 00000000 00000000 00000000 00000000 00001000 00000000   2048

        // 1000000 00000000 00000000 00000000 00000000 00000000 00001000 00000000
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return String.valueOf('(') + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
               ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        return maxNumElems;
    }

    @Override
    public int numAvailable() {
        return numAvail;
    }

    @Override
    public int elementSize() {
        return elemSize;
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
