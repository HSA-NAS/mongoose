package com.emc.mongoose.item.io.task.path;

import com.emc.mongoose.item.io.IoType;
import com.emc.mongoose.item.io.task.BasicIoTask;
import com.emc.mongoose.item.PathItem;
import com.emc.mongoose.storage.Credential;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import static java.lang.System.nanoTime;

/**
 Created by kurila on 30.01.17.
 */
public class BasicPathIoTask<I extends PathItem>
extends BasicIoTask<I>
implements PathIoTask<I> {
	
	protected volatile long countBytesDone;
	protected volatile long respDataTimeStart;
	
	public BasicPathIoTask() {
		super();
	}
	
	public BasicPathIoTask(
		final int originIndex, final IoType ioType, final I item, final Credential credential
	) {
		super(originIndex, ioType, item, null, null, credential);
		item.reset();
	}
	
	protected BasicPathIoTask(final BasicPathIoTask<I> other) {
		super(other);
		this.countBytesDone = other.countBytesDone;
		this.respDataTimeStart = other.respDataTimeStart;
	}
	
	@Override
	public BasicPathIoTask<I> result() {
		buildItemPath(item, dstPath == null ? srcPath : dstPath);
		return new BasicPathIoTask<>(this);
	}

	@Override
	public void writeExternal(final ObjectOutput out)
	throws IOException {
		super.writeExternal(out);
		out.writeLong(countBytesDone);
		out.writeLong(respDataTimeStart);
	}

	@Override
	public void readExternal(final ObjectInput in)
	throws IOException, ClassNotFoundException {
		super.readExternal(in);
		countBytesDone = in.readLong();
		respDataTimeStart = in.readLong();
	}
	
	@Override
	public long getCountBytesDone() {
		return countBytesDone;
	}
	
	@Override
	public void setCountBytesDone(final long n) {
		this.countBytesDone = n;
	}
	
	@Override
	public long getRespDataTimeStart() {
		return respDataTimeStart;
	}
	
	@Override
	public void startDataResponse() {
		respDataTimeStart = START_OFFSET_MICROS + nanoTime() / 1000;
	}
}