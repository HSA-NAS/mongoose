package com.emc.mongoose.client.impl.load.builder;
import com.emc.mongoose.client.api.load.builder.FileLoadBuilderClient;
import com.emc.mongoose.client.api.load.executor.FileLoadClient;
import com.emc.mongoose.client.impl.load.executor.BasicFileLoadClient;
import com.emc.mongoose.common.conf.RunTimeConfig;
import com.emc.mongoose.common.exceptions.DuplicateSvcNameException;
import com.emc.mongoose.common.net.Service;
import com.emc.mongoose.common.net.ServiceUtil;
import com.emc.mongoose.core.api.container.Directory;
import com.emc.mongoose.core.api.data.FileItem;
import com.emc.mongoose.core.api.io.req.IOConfig;
import com.emc.mongoose.core.impl.io.req.BasicFileIOConfig;
import com.emc.mongoose.server.api.load.builder.FileLoadBuilderSvc;
import com.emc.mongoose.server.api.load.executor.FileLoadSvc;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
/**
 Created by kurila on 26.11.15.
 */
public class BasicFileLoadBuilderClient<
	T extends FileItem, W extends FileLoadSvc<T>, U extends FileLoadClient<T, W>
> extends DataLoadBuilderClientBase<T, W, U, FileLoadBuilderSvc<T, W>>
implements FileLoadBuilderClient<T, W, U> {
	//
	public BasicFileLoadBuilderClient()
	throws IOException {
		super();
	}
	//
	public BasicFileLoadBuilderClient(final RunTimeConfig rtConfig)
	throws IOException {
		super(rtConfig);
	}
	//
	@Override @SuppressWarnings("unchecked")
	protected IOConfig<T, ? extends Directory<T>> getDefaultIOConfig() {
		return new BasicFileIOConfig<>();
	}
	//
	@Override
	protected FileLoadBuilderSvc<T, W> resolve(final String serverAddr)
	throws IOException {
		FileLoadBuilderSvc<T, W> rlb;
		final Service remoteSvc = ServiceUtil.getRemoteSvc(
			"//" + serverAddr + '/'
				+ getClass().getName()
				.replace("client", "server").replace("Client", "Svc")
		);
		if(remoteSvc == null) {
			throw new IOException("No remote load builder was resolved from " + serverAddr);
		} else if(remoteSvc instanceof FileLoadBuilderSvc) {
			rlb = (FileLoadBuilderSvc<T, W>) remoteSvc;
		} else {
			throw new IOException(
				"Illegal class " + remoteSvc.getClass().getCanonicalName() +
					" of the instance resolved from " + serverAddr
			);
		}
		return rlb;
	}
	//
	@Override
	public void invokePreConditions()
	throws IllegalStateException, RemoteException {
		FileLoadBuilderSvc<T, W> nextBuilder;
		for(final String addr : keySet()) {
			nextBuilder = get(addr);
			nextBuilder.invokePreConditions();
		}
	}
	//
	@Override @SuppressWarnings("unchecked")
	protected U buildActually()
	throws RemoteException, DuplicateSvcNameException {
		final Map<String, W> remoteLoadMap = new ConcurrentHashMap<>();
		//
		FileLoadBuilderSvc<T, W> nextBuilder;
		W nextLoad;
		//
		if(itemSrc == null) {
			itemSrc = getDefaultItemSource(); // affects load service builders
		}
		//
		for(final String addr : keySet()) {
			nextBuilder = get(addr);
			nextBuilder.setIOConfig(ioConfig); // should upload req conf right before instancing
			nextLoad = (W) ServiceUtil.getRemoteSvc(
				String.format("//%s/%s", addr, nextBuilder.buildRemotely())
			);
			remoteLoadMap.put(addr, nextLoad);
		}
		//
		final String loadTypeStr = ioConfig.getLoadType().name().toLowerCase();
		//
		return (U) new BasicFileLoadClient<>(
			rtConfig, (IOConfig<T, ? extends Directory<T>>) ioConfig, storageNodeAddrs,
			rtConfig.getConnCountPerNodeFor(loadTypeStr), rtConfig.getWorkerCountFor(loadTypeStr),
			itemSrc, maxCount, remoteLoadMap
		);
	}
}