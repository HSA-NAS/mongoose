package com.emc.mongoose.core.impl.data;
//
import com.emc.mongoose.common.conf.RunTimeConfig;
//
import com.emc.mongoose.core.api.data.FileItem;
import com.emc.mongoose.core.api.data.content.ContentSource;
/**
 Created by andrey on 22.11.15.
 */
public class BasicFileItem
extends BasicMutableDataItem
implements FileItem {
	////////////////////////////////////////////////////////////////////////////////////////////////
	public BasicFileItem() {
		super();
		name = Long.toString(offset, Character.MAX_RADIX);
	}
	//
	public BasicFileItem(final ContentSource contentSrc) {
		super(contentSrc); // ranges remain uninitialized
		name = Long.toString(offset, Character.MAX_RADIX);
	}
	//
	public BasicFileItem(final String metaInfo, final ContentSource contentSrc) {
		super(metaInfo, contentSrc);
	}
	//
	public BasicFileItem(final Long size, final ContentSource contentSrc) {
		super(size, contentSrc);
		name = Long.toString(offset, Character.MAX_RADIX);
	}
	//
	public BasicFileItem(
		final String name, final Long offset, final Long size, Integer layerNum,
		final ContentSource contentSrc
	) {
		super(name, offset, size, layerNum, contentSrc);
	}
}
