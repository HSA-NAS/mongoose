package com.emc.mongoose.model.item;

import com.emc.mongoose.model.data.ContentSource;

/**
 Created by kurila on 14.07.16.
 */
public class BasicMutableDataItemFactory<I extends MutableDataItem>
extends BasicDataItemFactory<I>
implements ItemFactory<I> {
	
	public BasicMutableDataItemFactory(final ContentSource contentSrc) {
		super(contentSrc);
	}
	
	@Override
	public I getItem(final String name, final long id, final long size) {
		return (I) new BasicMutableDataItem(name, id, size, contentSrc);
	}
	
	@Override
	public I getItem(final String line) {
		return (I) new BasicMutableDataItem(line, contentSrc);
	}
	
	@Override
	public Class<I> getItemClass() {
		return (Class<I>) BasicMutableDataItem.class;
	}
}