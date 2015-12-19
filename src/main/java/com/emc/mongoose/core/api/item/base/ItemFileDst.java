package com.emc.mongoose.core.api.item.base;
//
import java.io.IOException;
import java.nio.file.Path;
/**
 Created by kurila on 11.08.15.
 */
public interface ItemFileDst<T extends Item>
extends ItemDst<T> {
	//
	Path getFilePath();
	//
	void delete()
	throws IOException;
}