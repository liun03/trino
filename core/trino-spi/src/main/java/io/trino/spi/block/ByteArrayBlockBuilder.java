/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.spi.block;

import jakarta.annotation.Nullable;

import java.util.Arrays;

import static io.airlift.slice.SizeOf.instanceSize;
import static io.airlift.slice.SizeOf.sizeOf;
import static io.trino.spi.block.BlockUtil.calculateNewArraySize;
import static java.lang.Math.max;

public class ByteArrayBlockBuilder
        implements BlockBuilder
{
    private static final int INSTANCE_SIZE = instanceSize(ByteArrayBlockBuilder.class);
    private static final Block NULL_VALUE_BLOCK = new ByteArrayBlock(0, 1, new boolean[] {true}, new byte[1]);

    @Nullable
    private final BlockBuilderStatus blockBuilderStatus;
    private boolean initialized;
    private final int initialEntryCount;

    private int positionCount;
    private boolean hasNullValue;
    private boolean hasNonNullValue;

    // it is assumed that these arrays are the same length
    private boolean[] valueIsNull = new boolean[0];
    private byte[] values = new byte[0];

    private long retainedSizeInBytes;

    public ByteArrayBlockBuilder(@Nullable BlockBuilderStatus blockBuilderStatus, int expectedEntries)
    {
        this.blockBuilderStatus = blockBuilderStatus;
        this.initialEntryCount = max(expectedEntries, 1);

        updateRetainedSize();
    }

    public BlockBuilder writeByte(byte value)
    {
        ensureCapacity(positionCount + 1);

        values[positionCount] = value;

        hasNonNullValue = true;
        positionCount++;
        if (blockBuilderStatus != null) {
            blockBuilderStatus.addBytes(ByteArrayBlock.SIZE_IN_BYTES_PER_POSITION);
        }
        return this;
    }

    @Override
    public void append(ValueBlock block, int position)
    {
        ensureCapacity(positionCount + 1);

        ByteArrayBlock byteArrayBlock = (ByteArrayBlock) block;
        if (byteArrayBlock.isNull(position)) {
            valueIsNull[positionCount] = true;
            hasNullValue = true;
        }
        else {
            values[positionCount] = byteArrayBlock.getByte(position);
            hasNonNullValue = true;
        }
        positionCount++;

        if (blockBuilderStatus != null) {
            blockBuilderStatus.addBytes(ByteArrayBlock.SIZE_IN_BYTES_PER_POSITION);
        }
    }

    @Override
    public void appendRepeated(ValueBlock block, int position, int count)
    {
        if (count == 0) {
            return;
        }
        if (count == 1) {
            append(block, position);
            return;
        }

        ensureCapacity(positionCount + count);

        ByteArrayBlock byteArrayBlock = (ByteArrayBlock) block;

        if (byteArrayBlock.isNull(position)) {
            Arrays.fill(valueIsNull, positionCount, positionCount + count, true);
            hasNullValue = true;
        }
        else {
            byte value = byteArrayBlock.getByte(position);
            Arrays.fill(values, positionCount, positionCount + count, value);
            hasNonNullValue = true;
        }
        positionCount += count;

        if (blockBuilderStatus != null) {
            blockBuilderStatus.addBytes(count * ByteArrayBlock.SIZE_IN_BYTES_PER_POSITION);
        }
    }

    @Override
    public void appendRange(ValueBlock block, int offset, int length)
    {
        if (length == 0) {
            return;
        }
        if (length == 1) {
            append(block, offset);
            return;
        }

        ensureCapacity(positionCount + length);

        ByteArrayBlock byteArrayBlock = (ByteArrayBlock) block;
        int rawOffset = byteArrayBlock.getRawValuesOffset();

        byte[] rawValues = byteArrayBlock.getRawValues();
        System.arraycopy(rawValues, rawOffset + offset, values, positionCount, length);

        boolean[] rawValueIsNull = byteArrayBlock.getRawValueIsNull();
        if (rawValueIsNull != null) {
            for (int i = 0; i < length; i++) {
                if (rawValueIsNull[rawOffset + offset + i]) {
                    valueIsNull[positionCount + i] = true;
                    hasNullValue = true;
                }
                else {
                    hasNonNullValue = true;
                }
            }
        }
        else {
            hasNonNullValue = true;
        }
        positionCount += length;

        if (blockBuilderStatus != null) {
            blockBuilderStatus.addBytes(length * ByteArrayBlock.SIZE_IN_BYTES_PER_POSITION);
        }
    }

    @Override
    public void appendPositions(ValueBlock block, int[] positions, int offset, int length)
    {
        if (length == 0) {
            return;
        }
        if (length == 1) {
            append(block, positions[offset]);
            return;
        }

        ensureCapacity(positionCount + length);

        ByteArrayBlock byteArrayBlock = (ByteArrayBlock) block;
        int rawOffset = byteArrayBlock.getRawValuesOffset();
        byte[] rawValues = byteArrayBlock.getRawValues();
        boolean[] rawValueIsNull = byteArrayBlock.getRawValueIsNull();
        if (rawValueIsNull != null) {
            for (int i = 0; i < length; i++) {
                int rawPosition = positions[offset + i] + rawOffset;
                if (rawValueIsNull[rawPosition]) {
                    valueIsNull[positionCount + i] = true;
                    hasNullValue = true;
                }
                else {
                    values[positionCount + i] = rawValues[rawPosition];
                    hasNonNullValue = true;
                }
            }
        }
        else {
            for (int i = 0; i < length; i++) {
                int rawPosition = positions[offset + i] + rawOffset;
                values[positionCount + i] = rawValues[rawPosition];
            }
            hasNonNullValue = true;
        }
        positionCount += length;

        if (blockBuilderStatus != null) {
            blockBuilderStatus.addBytes(length * ByteArrayBlock.SIZE_IN_BYTES_PER_POSITION);
        }
    }

    @Override
    public BlockBuilder appendNull()
    {
        ensureCapacity(positionCount + 1);

        valueIsNull[positionCount] = true;

        hasNullValue = true;
        positionCount++;
        if (blockBuilderStatus != null) {
            blockBuilderStatus.addBytes(ByteArrayBlock.SIZE_IN_BYTES_PER_POSITION);
        }
        return this;
    }

    @Override
    public Block build()
    {
        if (!hasNonNullValue) {
            return RunLengthEncodedBlock.create(NULL_VALUE_BLOCK, positionCount);
        }
        return buildValueBlock();
    }

    @Override
    public ByteArrayBlock buildValueBlock()
    {
        return new ByteArrayBlock(0, positionCount, hasNullValue ? valueIsNull : null, values);
    }

    @Override
    public BlockBuilder newBlockBuilderLike(int expectedEntries, BlockBuilderStatus blockBuilderStatus)
    {
        return new ByteArrayBlockBuilder(blockBuilderStatus, expectedEntries);
    }

    private void ensureCapacity(int capacity)
    {
        if (values.length >= capacity) {
            return;
        }

        int newSize;
        if (initialized) {
            newSize = calculateNewArraySize(capacity);
        }
        else {
            newSize = initialEntryCount;
            initialized = true;
        }
        newSize = max(newSize, capacity);

        valueIsNull = Arrays.copyOf(valueIsNull, newSize);
        values = Arrays.copyOf(values, newSize);
        updateRetainedSize();
    }

    private void updateRetainedSize()
    {
        retainedSizeInBytes = INSTANCE_SIZE + sizeOf(valueIsNull) + sizeOf(values);
        if (blockBuilderStatus != null) {
            retainedSizeInBytes += BlockBuilderStatus.INSTANCE_SIZE;
        }
    }

    @Override
    public long getSizeInBytes()
    {
        return ByteArrayBlock.SIZE_IN_BYTES_PER_POSITION * (long) positionCount;
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return retainedSizeInBytes;
    }

    @Override
    public int getPositionCount()
    {
        return positionCount;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("ByteArrayBlockBuilder{");
        sb.append("positionCount=").append(getPositionCount());
        sb.append('}');
        return sb.toString();
    }
}
