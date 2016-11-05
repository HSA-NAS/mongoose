package com.emc.mongoose.model.storage;

import com.emc.mongoose.common.concurrent.Daemon;
import com.emc.mongoose.model.io.Output;
import com.emc.mongoose.model.io.task.IoTask;
import com.emc.mongoose.model.item.Item;
import com.emc.mongoose.model.load.LoadMonitor;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 Created on 11.07.16.
 */
public interface StorageDriver<I extends Item, O extends IoTask<I>>
extends Daemon, Output<O>, Remote {
	
	int getConcurrencyLevel()
	throws RemoteException;
	
	void setLoadMonitor(final LoadMonitor<I, O> monitor)
	throws RemoteException;

	boolean isIdle()
	throws RemoteException;

	boolean isFullThrottleEntered()
	throws RemoteException;

	boolean isFullThrottleExited()
	throws RemoteException;
}
