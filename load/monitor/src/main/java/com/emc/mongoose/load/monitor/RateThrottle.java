package com.emc.mongoose.load.monitor;

import com.emc.mongoose.common.concurrent.Throttle;

import java.util.concurrent.TimeUnit;

/**
 Created by kurila on 04.04.16.
 */
public class RateThrottle<X>
implements Throttle<X> {
	//
	private final long tgtNanoTime;
	//
	public RateThrottle(final double rateLimit) {
		this.tgtNanoTime = rateLimit > 0 && Double.isFinite(rateLimit) ?
			(long) (TimeUnit.SECONDS.toNanos(1) / rateLimit) :
			0;
	}
	//
	@Override
	public final boolean waitPassFor(final X item)
	throws InterruptedException {
		if(tgtNanoTime > 0) {
			TimeUnit.NANOSECONDS.sleep(tgtNanoTime);
		}
		return true;
	}
	//
	@Override
	public final boolean waitPassFor(final X item, final int times)
	throws InterruptedException {
		if(tgtNanoTime > 0 && times > 0) {
			TimeUnit.NANOSECONDS.sleep(tgtNanoTime * times);
		}
		return true;
	}
}
