package com.emc.mongoose.storage.adapter.swift;
//
//
import com.emc.mongoose.core.api.data.WSObject;
    import com.emc.mongoose.core.api.data.model.DataItemContainer;
/**
 Created by kurila on 02.03.15.
 */
public interface Container<T extends WSObject>
extends DataItemContainer<T> {}