/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.common.concurrent.logbuffer;

import uk.co.real_logic.agrona.BitUtil;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;

import static uk.co.real_logic.agrona.BitUtil.CACHE_LINE_LENGTH;
import static uk.co.real_logic.agrona.BitUtil.SIZE_OF_INT;
import static uk.co.real_logic.aeron.common.concurrent.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;

/**
 * Layout description for log buffers which contains partitions of terms with associated term meta data,
 * plus ending with overall log meta data.
 *
 * <pre>
 *  +----------------------------+
 *  |           Term 0           |
 *  +----------------------------+
 *  |           Term 1           |
 *  +----------------------------+
 *  |           Term 2           |
 *  +----------------------------+
 *  |      Term Meta Data 0      |
 *  +----------------------------+
 *  |      Term Meta Data 1      |
 *  +----------------------------+
 *  |      Term Meta Data 2      |
 *  +----------------------------+
 *  |        Log Meta Data       |
 *  +----------------------------+
 * </pre>
 */
public class LogBufferDescriptor
{
    /**
     * The number of partitions the log is divided into with pairs of term and term meta data buffers.
     */
    public static final int PARTITION_COUNT = 3;

    /**
     * Minimum buffer length for a log term
     */
    public static final int TERM_MIN_LENGTH = 64 * 1024; // TODO: make a sensible default

    // ********************************
    // *** Term Meta Data Constants ***
    // ********************************

    /**
     * A term is currently clean or in use.
     */
    public static final int CLEAN = 0;

    /**
     * A term is dirty and requires cleaning.
     */
    public static final int NEEDS_CLEANING = 1;

    /**
     * Offset within the term meta data where the tail value is stored.
     */
    public static final int TERM_TAIL_COUNTER_OFFSET;

    /**
     * Offset within the term meta data where the high water mark is stored.
     */
    public static final int TERM_HIGH_WATER_MARK_OFFSET;

    /**
     * Offset within the term meta data where current status is stored
     */
    public static final int TERM_STATUS_OFFSET;

    /**
     * Total length of the term meta data buffer in bytes.
     */
    public static final int TERM_META_DATA_LENGTH;

    static
    {
        TERM_HIGH_WATER_MARK_OFFSET = 0;
        TERM_TAIL_COUNTER_OFFSET = TERM_HIGH_WATER_MARK_OFFSET + SIZE_OF_INT;
        TERM_STATUS_OFFSET = TERM_TAIL_COUNTER_OFFSET + CACHE_LINE_LENGTH;
        TERM_META_DATA_LENGTH = CACHE_LINE_LENGTH * 2;
    }

    // *******************************
    // *** Log Meta Data Constants ***
    // *******************************

    /**
     * Offset within the log meta data where the active term id is stored.
     */
    public static final int LOG_INITIAL_TERM_ID_OFFSET = 0;

    /**
     * Offset within the log meta data where the active term id is stored.
     */
    public static final int LOG_ACTIVE_TERM_ID_OFFSET = LOG_INITIAL_TERM_ID_OFFSET + SIZE_OF_INT;

    /**
     * Total length of the log meta buffer in bytes.
     */
    public static final int LOG_META_DATA_LENGTH = CACHE_LINE_LENGTH;

    /**
     * Check that term buffer is the correct length and alignment.
     *
     * @param buffer to be checked.
     * @throws IllegalStateException if the buffer is not as expected.
     */
    public static void checkTermBuffer(final UnsafeBuffer buffer)
    {
        final int capacity = buffer.capacity();
        if (capacity < TERM_MIN_LENGTH)
        {
            final String s = String.format(
                "Term buffer capacity less than min length of %d, capacity=%d",
                TERM_MIN_LENGTH,
                capacity);
            throw new IllegalStateException(s);
        }

        if ((capacity & (FRAME_ALIGNMENT - 1)) != 0)
        {
            final String s = String.format(
                "Term buffer capacity not a multiple of %d, capacity=%d",
                FRAME_ALIGNMENT,
                capacity);
            throw new IllegalStateException(s);
        }
    }

    /**
     * Check that meta data buffer is of sufficient length.
     *
     * @param buffer to be checked.
     * @throws IllegalStateException if the buffer is not as expected.
     */
    public static void checkMetaDataBuffer(final UnsafeBuffer buffer)
    {
        final int capacity = buffer.capacity();
        if (capacity < TERM_META_DATA_LENGTH)
        {
            final String s = String.format(
                "Meta data buffer capacity less than min length of %d, capacity=%d",
                TERM_META_DATA_LENGTH,
                capacity);
            throw new IllegalStateException(s);
        }
    }

    /**
     * Get the value of the initial Term id used for this log.
     *
     * @param logMetaDataBuffer containing the meta data.
     * @return the value of the initial Term id used for this log.
     */
    public static int initialTermId(final UnsafeBuffer logMetaDataBuffer)
    {
        return logMetaDataBuffer.getInt(LOG_INITIAL_TERM_ID_OFFSET);
    }

    /**
     * Set the initial term at which this log begins. Initial should be randomised so that stream does not get
     * reused accidentally.
     *
     * @param logMetaDataBuffer containing the meta data.
     * @param initialTermId     value to be set.
     */
    public static void initialTermId(final UnsafeBuffer logMetaDataBuffer, final int initialTermId)
    {
        logMetaDataBuffer.putInt(LOG_INITIAL_TERM_ID_OFFSET, initialTermId);
    }

    /**
     * Get the value of the active Term id used by the producer of this log. Consumers may have a different active term if
     * they are running behind. The read is done with volatile semantics.
     *
     * @param logMetaDataBuffer containing the meta data.
     * @return the value of the active Term id used by the producer of this log.
     */
    public static int activeTermId(final UnsafeBuffer logMetaDataBuffer)
    {
        return logMetaDataBuffer.getIntVolatile(LOG_ACTIVE_TERM_ID_OFFSET);
    }

    /**
     * Set the value of the current active term id for the producer using memory ordered semantics.
     *
     * @param logMetaDataBuffer containing the meta data.
     * @param activeTermId      value of the active Term id used by the producer of this log.
     */
    public static void activeTermId(final UnsafeBuffer logMetaDataBuffer, final int activeTermId)
    {
        logMetaDataBuffer.putIntOrdered(LOG_ACTIVE_TERM_ID_OFFSET, activeTermId);
    }

    /**
     * Rotate to the next partition in sequence for the term id.
     *
     * @param current partition index
     * @return the next partition index
     */
    public static int nextPartitionIndex(final int current)
    {
        return BitUtil.next(current, PARTITION_COUNT);
    }

    /**
     * Rotate to the previous partition in sequence for the term id.
     *
     * @param current partition index
     * @return the previous partition index
     */
    public static int previousPartitionIndex(final int current)
    {
        return BitUtil.previous(current, PARTITION_COUNT);
    }

    /**
     * Determine the buffer index to be used given the initial term and active term ids.
     *
     * @param initialTermId at which the log buffer usage began
     * @param activeTermId  that is in current usage
     * @return the index of which buffer should be used
     */
    public static int partitionIndex(final int initialTermId, final int activeTermId)
    {
        return (activeTermId - initialTermId) % PARTITION_COUNT;
    }

    /**
     * Compute the current position in absolute number of bytes.
     *
     * @param activeTermId        active term id.
     * @param currentTail         in the term.
     * @param positionBitsToShift number of times to left shift the term count
     * @param initialTermId       the initial term id that this stream started on
     * @return the absolute position in bytes
     */
    public static long computePosition(
        final int activeTermId, final int currentTail, final int positionBitsToShift, final int initialTermId)
    {
        final long termCount = activeTermId - initialTermId; // copes with negative activeTermId on rollover

        return (termCount << positionBitsToShift) + currentTail;
    }

    /**
     * Compute the term id from a position.
     *
     * @param position            to calculate from
     * @param positionBitsToShift number of times to right shift the position
     * @param initialTermId       the initial term id that this stream started on
     * @return the term id according to the position
     */
    public static int computeTermIdFromPosition(final long position, final int positionBitsToShift, final int initialTermId)
    {
        return ((int)(position >>> positionBitsToShift) + initialTermId);
    }

    /**
     * Compute the term offset from a given position.
     *
     * @param position            to calculate from
     * @param positionBitsToShift number of times to right shift the position
     * @return the offset within the term that represents the position
     */
    public static int computeTermOffsetFromPosition(final long position, final int positionBitsToShift)
    {
        final int mask = (1 << positionBitsToShift) - 1;

        return (int)(position & mask);
    }

    /**
     * Compute the total length of a log file given the term length.
     *
     * @param termLength on which to base the calculation.
     * @return the total length of the log file.
     */
    public static long computeLogLength(final int termLength)
    {
        return (termLength * PARTITION_COUNT) +
        (TERM_META_DATA_LENGTH * PARTITION_COUNT) +
        LOG_META_DATA_LENGTH;
    }
}
