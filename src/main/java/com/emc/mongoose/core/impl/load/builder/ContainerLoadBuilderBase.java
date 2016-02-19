package com.emc.mongoose.core.impl.load.builder;
//
import com.emc.mongoose.common.conf.AppConfig;
import static com.emc.mongoose.common.conf.AppConfig.ItemNamingType;
import com.emc.mongoose.common.log.LogUtil;
//
import com.emc.mongoose.core.api.item.container.Container;
import com.emc.mongoose.core.api.item.container.Directory;
import com.emc.mongoose.core.api.item.data.DataItem;
import com.emc.mongoose.core.api.item.base.ItemSrc;
import com.emc.mongoose.core.api.io.task.IOTask;
import com.emc.mongoose.core.api.load.builder.ContainerLoadBuilder;
import com.emc.mongoose.core.api.load.executor.ContainerLoadExecutor;
import com.emc.mongoose.core.impl.item.base.BasicItemNameGenerator;
//
import com.emc.mongoose.core.impl.item.base.ItemCSVFileSrc;
import com.emc.mongoose.core.impl.item.data.NewContainerSrc;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.util.Arrays;
/**
 * Created by gusakk on 21.10.15.
 */
public abstract class ContainerLoadBuilderBase<
	T extends DataItem,
	C extends Container<T>,
	U extends ContainerLoadExecutor<T, C>
>
extends LoadBuilderBase<C, U>
implements ContainerLoadBuilder<T, C, U>{
	//
	private static final Logger LOG = LogManager.getLogger();
	//
	protected boolean flagUseContainerItemSrc;
	//
	public ContainerLoadBuilderBase(final AppConfig appConfig)
	throws RemoteException {
		super(appConfig);
	}
	//
	@Override
	public ContainerLoadBuilderBase<T, C, U> setAppConfig(final AppConfig appConfig)
	throws RemoteException {
		super.setAppConfig(appConfig);
		//
		final String listFilePathStr = appConfig.getItemSrcFile();
		if(itemsFileExists(listFilePathStr)) {
			try {
				setItemSrc(
					new ItemCSVFileSrc<>(
						Paths.get(listFilePathStr), (Class<C>) ioConfig.getContainerClass(),
						ioConfig.getContentSource()
					)
				);
			} catch(final IOException | NoSuchMethodException e) {
				LogUtil.exception(LOG, Level.ERROR, e, "Failed to use CSV file input");
			}
		}
		return this;
	}
	//
	@SuppressWarnings("unchecked")
	private ItemSrc getNewItemSrc()
	throws NoSuchMethodException {
		//
		ItemNamingType namingType = appConfig.getItemNamingType();
		final Class<C> containerClass = (Class<C>) ioConfig.getContainerClass();
		return new NewContainerSrc<>(
			containerClass,
			new BasicItemNameGenerator(
				namingType,
				Directory.class.isAssignableFrom(containerClass) ?
					null : appConfig.getItemNamingPrefix(),
				appConfig.getItemNamingLength(), appConfig.getItemNamingRadix(),
				appConfig.getItemNamingOffset()
			)
		);
	}
	//
	@SuppressWarnings("unchecked")
	protected ItemSrc getDefaultItemSource() {
		try {
			if(flagUseNoneItemSrc) {
				return null;
			} else if(flagUseContainerItemSrc && flagUseNewItemSrc) {
				if(IOTask.Type.WRITE.equals(ioConfig.getLoadType())) {
					getNewItemSrc();
				}
			} else if(flagUseNewItemSrc) {
				return getNewItemSrc();
			}
		} catch(final NoSuchMethodException e) {
			LogUtil.exception(LOG, Level.ERROR, e, "Failed to build the new data items source");
		}
		return null;
	}
	//
	@Override
	public ContainerLoadBuilderBase<T, C, U> clone()
	throws CloneNotSupportedException {
		return (ContainerLoadBuilderBase<T, C, U>) super.clone();
	}
}
