package com.emc.mongoose.storage.driver.net.base;

import com.emc.mongoose.common.concurrent.ThreadUtil;
import com.emc.mongoose.common.io.ThreadLocalByteBuffer;
import com.emc.mongoose.model.api.io.task.DataIoTask;
import com.emc.mongoose.model.api.io.task.IoTask;
import com.emc.mongoose.model.api.io.task.MutableDataIoTask;
import com.emc.mongoose.model.api.item.DataItem;
import com.emc.mongoose.model.api.item.Item;
import com.emc.mongoose.model.api.item.MutableDataItem;
import com.emc.mongoose.model.impl.data.DataCorruptionException;
import com.emc.mongoose.model.impl.data.DataSizeException;
import com.emc.mongoose.model.impl.data.DataVerificationException;
import com.emc.mongoose.ui.log.LogUtil;
import static com.emc.mongoose.model.api.io.task.IoTask.Status.FAIL_UNKNOWN;
import static com.emc.mongoose.model.api.item.MutableDataItem.getRangeOffset;

import com.emc.mongoose.ui.log.Markers;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.PrematureChannelClosureException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
/**
 Created by kurila on 04.10.16.
 */
public abstract class ClientHandlerBase<M, I extends Item, O extends IoTask<I>>
extends SimpleChannelInboundHandler<M> {
	
	private final static Logger LOG = LogManager.getLogger();

	protected final NetStorageDriverBase<I, O> driver;
	protected final boolean verifyFlag;
	
	protected ClientHandlerBase(final NetStorageDriverBase<I, O> driver, boolean verifyFlag) {
		this.driver = driver;
		this.verifyFlag = verifyFlag;
	}
	
	@Override
	protected final void channelRead0(final ChannelHandlerContext ctx, final M msg)
	throws Exception {
		final Channel channel = ctx.channel();
		final O ioTask = (O) channel.attr(NetStorageDriver.ATTR_KEY_IOTASK).get();
		handle(channel, ioTask, msg);
	}
	
	protected abstract void handle(final Channel channel, final O ioTask, final M msg)
	throws IOException;

	@Override @SuppressWarnings("unchecked")
	public final void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause)
	throws IOException {
		if(cause instanceof PrematureChannelClosureException) {
			return;
		}
		LogUtil.exception(LOG, Level.WARN, cause, "Client handler failure");
		final Channel channel = ctx.channel();
		final O ioTask = (O) channel.attr(NetStorageDriver.ATTR_KEY_IOTASK).get();
		ioTask.setStatus(FAIL_UNKNOWN);
		driver.complete(channel, ioTask);
	}
	
	protected final void verifyChunk(
		final Channel channel, final O ioTask, final ByteBuf contentChunk, final int chunkSize
	) throws InterruptedException {
		final DataIoTask dataIoTask = (DataIoTask) ioTask;
		final DataItem item = dataIoTask.getItem();
		final long countBytesDone = dataIoTask.getCountBytesDone();
		try {
			if(item instanceof MutableDataItem) {
				final MutableDataItem mdi = (MutableDataItem)item;
				if(mdi.isUpdated()) {
					verifyChunkUpdatedData(
						mdi, (MutableDataIoTask) ioTask, contentChunk, chunkSize
					);
					dataIoTask.setCountBytesDone(countBytesDone + chunkSize);
				} else {
					verifyChunkDataAndSize(mdi, countBytesDone, contentChunk, chunkSize);
					dataIoTask.setCountBytesDone(countBytesDone + chunkSize);
				}
			} else {
				verifyChunkDataAndSize(item, countBytesDone, contentChunk, chunkSize);
				dataIoTask.setCountBytesDone(countBytesDone + chunkSize);
			}
		} catch(final IOException e) {
			if(e instanceof DataVerificationException) {
				final DataVerificationException ee = (DataVerificationException)e;
				dataIoTask.setCountBytesDone(ee.getOffset());
				dataIoTask.setStatus(IoTask.Status.RESP_FAIL_CORRUPT);
				if(e instanceof DataSizeException) {
					try {
						LOG.warn(
							Markers.MSG, "{}: invalid size, expected: {}, actual: {} ",
							item.getName(), item.size(), ee.getOffset()
						);
					} catch(final IOException ignored) {
					}
				} else if(e instanceof DataCorruptionException) {
					final DataCorruptionException eee = (DataCorruptionException)ee;
					LOG.warn(
						Markers.MSG, "{}: content mismatch @ offset {}, expected: {}, actual: {} ",
						item.getName(), ee.getOffset(), String.format("\"0x%X\"", eee.expected),
						String.format("\"0x%X\"", eee.actual)
					);
				}
			}
			try {
				driver.complete(channel, ioTask);
			} catch(final IOException ee) {
				LogUtil.exception(LOG, Level.WARN, e, "Failed to release the channel");
			}
		}
	}

	private void verifyChunkDataAndSize(
		final DataItem item, final long countBytesDone, final ByteBuf chunkData,
		final int chunkSize
	) throws DataCorruptionException, IOException {
		if(chunkSize > item.size() - countBytesDone) {
			throw new DataSizeException(item.size(), countBytesDone + chunkSize);
		}
		verifyChunkData(item, chunkData, 0, chunkSize);
	}
	
	private void verifyChunkUpdatedData(
		final MutableDataItem item, final MutableDataIoTask ioTask, final ByteBuf chunkData,
		final int chunkSize
	) throws DataCorruptionException, IOException {
		
		final long countBytesDone = ioTask.getCountBytesDone();
		int chunkCountDone = 0, remainingSize;
		long nextRangeOffset;
		int currRangeIdx;
		DataItem currRange;
		
		while(chunkCountDone < chunkSize) {
			
			currRangeIdx = ioTask.getCurrRangeIdx();
			nextRangeOffset = getRangeOffset(currRangeIdx + 1);
			if(countBytesDone + chunkCountDone == nextRangeOffset) {
				if(nextRangeOffset < item.size()) {
					currRangeIdx++;
					nextRangeOffset = getRangeOffset(currRangeIdx + 1);
					ioTask.setCurrRangeIdx(currRangeIdx);
				} else {
					throw new DataSizeException(item.size(), countBytesDone + chunkSize);
				}
			}
			currRange = ioTask.getCurrRange();
			
			try {
				remainingSize = (int)Math.min(chunkSize - chunkCountDone,
					nextRangeOffset - countBytesDone - chunkCountDone
				);
				verifyChunkData(currRange, chunkData, chunkCountDone, remainingSize);
				chunkCountDone += remainingSize;
			} catch(final DataCorruptionException e) {
				throw new DataCorruptionException(
					getRangeOffset(ioTask.getCurrRangeIdx()) + e.getOffset(), e.actual,
					e.expected
				);
			}
		}
	}
	
	private void verifyChunkData(
		final DataItem item, final ByteBuf chunkData, final int chunkOffset,
		final int remainingSize
	) throws DataCorruptionException, IOException {
		
		// fill the expected data buffer to compare with a chunk
		final ByteBuffer bb = ThreadLocalByteBuffer.get(remainingSize);
		bb.limit(remainingSize);
		int n = 0;
		while(n < remainingSize) {
			n += item.read(bb);
		}
		bb.flip();
		final ByteBuf buff = Unpooled.wrappedBuffer(bb);
		
		// fast compare word by word
		boolean fastEquals = true;
		int buffPos = 0;
		int chunkPos = chunkOffset;
		
		if(
			buff.writerIndex() - remainingSize < buffPos ||
			chunkData.writerIndex() - remainingSize < chunkPos
		) {
			fastEquals = false;
		} else {
			final int longCount = remainingSize >>> 3;
			final int byteCount = remainingSize & 7;
			
			// assuming the same order (big endian)
			for(int i = longCount; i > 0; i --) {
				if(buff.getLong(buffPos) != chunkData.getLong(chunkPos)) {
					fastEquals = false;
					break;
				}
				buffPos += 8;
				chunkPos += 8;
			}
			
			if(fastEquals) {
				for(int i = byteCount; i > 0; i --) {
					if(buff.getByte(buffPos) != chunkData.getByte(chunkPos)) {
						fastEquals = false;
						break;
					}
					buffPos++;
					chunkPos++;
				}
			}
		}
		
		if(fastEquals) {
			return;
		}
		
		// slow byte by byte compare if fast one fails to find the exact mismatch position
		byte expected, actual;
		for(int i = 0; i < remainingSize; i++) {
			expected = buff.getByte(i);
			actual = chunkData.getByte(chunkOffset + i);
			if(expected != actual) {
				throw new DataCorruptionException(i, expected, actual);
			}
		}
	}
}
