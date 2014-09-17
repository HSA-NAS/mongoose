package com.emc.mongoose.data;
//
import com.emc.mongoose.conf.RunTimeConfig;
import com.emc.mongoose.logging.Markers;
//
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.BitSet;
import java.util.concurrent.ThreadLocalRandom;
/**
 Created by kurila on 15.09.14.
 */
public class DataRanges
extends UniformData {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	private final static String
		FMT_META_INFO = "%s" + RunTimeConfig.LIST_SEP + "%s",
		FMT_MSG_MASK = "Ranges mask is not correct hexadecimal value: %s",
		FMT_MSG_WRONG_RANGE_COUNT = "Range count should be more than 0 and less than the object size = %s";
	////////////////////////////////////////////////////////////////////////////////////////////////
	protected final BitSet
		maskRangesHistory = new BitSet(),
		maskRangesPending = new BitSet();
	private int countRangesTotal, rangeSize;
	////////////////////////////////////////////////////////////////////////////////////////////////
	private static int calcTotalCount(long size)
		throws IllegalArgumentException {
		if(size > 0) {
			return Long.SIZE - Long.numberOfLeadingZeros(size) - 1 + (int) Math.sqrt(Math.sqrt(size));
		} else {
			throw new IllegalArgumentException(
				String.format("The size should be more than zero, but got %d", size)
			);
		}
	}
	//
	private void initRanges() {
		countRangesTotal = calcTotalCount(size);
		rangeSize = (int) (size / countRangesTotal);
	}
	////////////////////////////////////////////////////////////////////////////////////////////////
	public DataRanges() {
		super(); // ranges remain uninitialized
	}
	//
	public DataRanges(final String metaInfo) {
		fromString(metaInfo); // invokes ranges initialization
	}
	//
	public DataRanges(final long size) {
		super(size);
		initRanges();
	}
	//
	public DataRanges(final long size, final UniformDataSource dataSrc) {
		super(size, dataSrc);
		initRanges();
	}
	//
	public DataRanges(final long offset, final long size) {
		super(offset, size);
		initRanges();
	}
	//
	public DataRanges(final long offset, final long size, final UniformDataSource dataSrc) {
		super(offset, size, dataSrc);
		initRanges();
	}
	////////////////////////////////////////////////////////////////////////////////////////////////
	// Human readable "serialization" implementation ///////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////
	@Override
	public String toString() {
		return String.format(
			FMT_META_INFO, super.toString(), Hex.encodeHexString(maskRangesHistory.toByteArray())
		);
	}
	//
	public void fromString(final String v)
	throws IllegalArgumentException, NullPointerException {
		final int lastCommaPos = v.lastIndexOf(RunTimeConfig.LIST_SEP);
		final String baseItemInfo, rangesInfo;
		if(lastCommaPos > 0) {
			baseItemInfo = v.substring(0, lastCommaPos);
			super.fromString(baseItemInfo);
			rangesInfo = v.substring(lastCommaPos + 1, v.length());
			try {
				initRanges();
				maskRangesHistory.or(BitSet.valueOf(Hex.decodeHex(rangesInfo.toCharArray())));
			} catch(final DecoderException e) {
				throw new IllegalArgumentException(String.format(FMT_MSG_MASK, rangesInfo));
			}
		} else {
			throw new IllegalArgumentException(String.format(FMT_MSG_INVALID_RECORD, v));
		}
	}
	////////////////////////////////////////////////////////////////////////////////////////////////
	@Override
	public int hashCode() {
		return super.hashCode() ^ maskRangesHistory.hashCode() ^ maskRangesPending.hashCode();
	}
	////////////////////////////////////////////////////////////////////////////////////////////////
	// Binary serialization implementation /////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////
	@Override
	public void writeExternal(final ObjectOutput out)
	throws IOException {
		super.writeExternal(out);
		out.writeObject(maskRangesHistory);
		out.writeObject(maskRangesPending);
	}
	//
	@Override
	public void readExternal(final ObjectInput in)
	throws IOException, ClassNotFoundException {
		super.readExternal(in);
		maskRangesHistory.or(BitSet.class.cast(in.readObject()));
		maskRangesPending.or(BitSet.class.cast(in.readObject()));
	}
	////////////////////////////////////////////////////////////////////////////////////////////////
	public final boolean compareWith(final InputStream in) {
		boolean contentEquals = true;
		final int tailSize = (int) (size - countRangesTotal * rangeSize);
		long rangeOffset;
		UniformData updatedRange;
		for(int i=0; i<countRangesTotal; i++) {
			rangeOffset = i * rangeSize;
			if(maskRangesHistory.get(i)) { // range have been modified
				if(LOG.isTraceEnabled(Markers.MSG)) {
					LOG.trace(
						Markers.MSG, "Range #{} [{}-{}] was modified",
						i, rangeOffset, rangeOffset + rangeSize - 1
					);
				}
				updatedRange = new UniformData(
					offset + rangeOffset, rangeSize, UniformDataSource.DATA_SRC_UPDATE
				);
				contentEquals = updatedRange.compareWith(in, 0, rangeSize);
			} else {
				contentEquals = compareWith(in, rangeOffset, rangeSize);
			}
			if(!contentEquals) {
				if(LOG.isTraceEnabled()) {
					LOG.trace(
						Markers.MSG, "Range #{}(offset {}) corrupted?", i, rangeOffset
					);
				}
				break;
			}
		}
		if(contentEquals && tailSize > 0) {
			contentEquals = compareWith(in, countRangesTotal * rangeSize, tailSize);
		}
		return contentEquals;
	}
	//
	public final boolean isRangeUpdatePending(final int i) {
		return maskRangesPending.get(i);
	}
	//
	public final void updateRandomRange()
		throws IllegalStateException {
		final int startCellPos = ThreadLocalRandom.current().nextInt(countRangesTotal);
		int nextCellPos;
		for(int i=startCellPos; i<startCellPos+countRangesTotal; i++) {
			nextCellPos = i % countRangesTotal;
			if(!maskRangesHistory.get(nextCellPos) && !maskRangesPending.get(nextCellPos)) {
				maskRangesPending.set(nextCellPos);
				if(LOG.isTraceEnabled(Markers.MSG)) {
					LOG.trace(
						Markers.MSG, "Update cell at position: {}, offset: {}, new mask: {}",
						nextCellPos, nextCellPos * rangeSize,
						Hex.encodeHexString(maskRangesPending.toByteArray())
					);
				}
				return;
			}
		}
		throw new IllegalStateException("Looks like there's no free range to update");
	}
	//
	public final void updateRandomRanges(final int count)
		throws IllegalArgumentException, IllegalStateException {
		if(count < 1 || count > countRangesTotal) {
			throw new IllegalArgumentException(
				String.format(FMT_MSG_WRONG_RANGE_COUNT, RunTimeConfig.formatSize(countRangesTotal))
			);
		}
		for(int i = 0; i < count; i++) {
			updateRandomRange();
		}
	}
	//
	public final int getPendingUpdatesCount() {
		int count = 0;
		for(int i=0; i<countRangesTotal; i++) {
			if(maskRangesPending.get(i)) {
				count ++;
			}
		}
		return count;
	}
	/*
	public final InputStream getPendingUpdatesContent() {
		InputStream updatesContent = null;
		UniformData nextRangeData;
		long rangeOffset;
		for(int i=0; i<countRangesTotal; i++) {
			if(maskRangesPending.get(i)) {
				rangeOffset = i * rangeSize;
				LOG.trace(
					Markers.MSG, "Append range with offset {} and size {} to content for update",
					offset + rangeOffset, rangeSize
				);
				nextRangeData = new UniformData(
					offset + rangeOffset, rangeSize, UniformDataSource.DATA_SRC_UPDATE
				);
				if(updatesContent==null) {
					updatesContent = nextRangeData;
				} else {
					updatesContent = new SequenceInputStream(updatesContent, nextRangeData);
				}
			}
		}
		return updatesContent;
	}*/
	//
	public final synchronized void movePendingUpdatesToHistory() {
		if(LOG.isTraceEnabled(Markers.MSG)) {
			LOG.trace(
				Markers.MSG, "Move pending ranges \"{}\" to history \"{}\"",
				Hex.encodeHexString(maskRangesPending.toByteArray()),
				Hex.encodeHexString(maskRangesHistory.toByteArray())
			);
		}
		maskRangesHistory.or(maskRangesPending);
		maskRangesPending.clear();
	}
	//
	public final int getCountRangesTotal() {
		return countRangesTotal;
	}
	//
	public final int getRangeSize() {
		return rangeSize;
	}
	//
}
