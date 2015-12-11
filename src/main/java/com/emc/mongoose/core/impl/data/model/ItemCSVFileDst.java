package com.emc.mongoose.core.impl.data.model;
//
import com.emc.mongoose.core.api.Item;
import com.emc.mongoose.core.api.data.content.ContentSource;
import com.emc.mongoose.core.api.data.model.ItemFileDst;
//
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
/**
 Created by kurila on 30.06.15.
 */
public class ItemCSVFileDst<T extends Item>
extends ItemCSVDst<T>
implements ItemFileDst<T> {
	//
	protected Path itemsFilePath;
	//
	public
	ItemCSVFileDst(
		final Path itemsFilePath, final Class<? extends T> itemCls, final ContentSource contentSrc
	) throws IOException {
		super(
			Files.newOutputStream(itemsFilePath, StandardOpenOption.WRITE),
			itemCls, contentSrc
		);
		this.itemsFilePath = itemsFilePath;
	}
	//
	public
	ItemCSVFileDst(final Class<? extends T> itemCls, final ContentSource contentSrc)
	throws IOException {
		this(Files.createTempFile(null, ".csv"), itemCls, contentSrc);
		this.itemsFilePath.toFile().deleteOnExit();
	}
	//
	@Override
	public
	ItemCSVFileSrc<T> getItemSrc()
	throws IOException {
		try {
			return new ItemCSVFileSrc<>(itemsFilePath, itemCls, contentSrc);
		} catch(final NoSuchMethodException e) {
			throw new IOException(e);
		}
	}
	//
	@Override
	public String toString() {
		return "csvFileItemOutput<" + itemsFilePath.getFileName() + ">";
	}
	//
	@Override
	public final Path getFilePath() {
		return itemsFilePath;
	}
	//
	@Override
	public final void delete()
	throws IOException {
		Files.delete(itemsFilePath);
	}
}