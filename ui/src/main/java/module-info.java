module com.emc.mongoose.ui {

	requires com.emc.mongoose.api.common;
	requires com.emc.mongoose.api.model;
	requires com.github.akurilov.commons;
	requires com.github.akurilov.concurrent;
	requires commons.lang;
	requires log4j.api;
	requires log4j.core;
	requires log4j.iostreams;
	requires log4j.jul;
	requires com.fasterxml.jackson.annotation;
	requires com.fasterxml.jackson.core;
	requires com.fasterxml.jackson.databind;
	requires java.base;

	exports com.emc.mongoose.ui.cli;
	exports com.emc.mongoose.ui.config;
	exports com.emc.mongoose.ui.config.item;
	exports com.emc.mongoose.ui.config.item.data;
	exports com.emc.mongoose.ui.config.item.data.input;
	exports com.emc.mongoose.ui.config.item.data.input.layer;
	exports com.emc.mongoose.ui.config.item.data.ranges;
	exports com.emc.mongoose.ui.config.item.input;
	exports com.emc.mongoose.ui.config.item.naming;
	exports com.emc.mongoose.ui.config.item.output;
	exports com.emc.mongoose.ui.config.load;
	exports com.emc.mongoose.ui.config.load.batch;
	exports com.emc.mongoose.ui.config.load.generator;
	exports com.emc.mongoose.ui.config.load.generator.recycle;
	exports com.emc.mongoose.ui.config.load.rate;
	exports com.emc.mongoose.ui.config.load.service;
	exports com.emc.mongoose.ui.config.output;
	exports com.emc.mongoose.ui.config.output.metrics;
	exports com.emc.mongoose.ui.config.output.metrics.average;
	exports com.emc.mongoose.ui.config.output.metrics.average.table;
	exports com.emc.mongoose.ui.config.output.metrics.average.table.header;
	exports com.emc.mongoose.ui.config.output.metrics.summary;
	exports com.emc.mongoose.ui.config.output.metrics.trace;
	exports com.emc.mongoose.ui.config.storage;
	exports com.emc.mongoose.ui.config.storage.auth;
	exports com.emc.mongoose.ui.config.storage.driver;
	exports com.emc.mongoose.ui.config.storage.driver.queue;
	exports com.emc.mongoose.ui.config.storage.net;
	exports com.emc.mongoose.ui.config.storage.net.http;
	exports com.emc.mongoose.ui.config.storage.net.node;
	exports com.emc.mongoose.ui.config.test;
	exports com.emc.mongoose.ui.config.test.scenario;
	exports com.emc.mongoose.ui.config.test.step;
	exports com.emc.mongoose.ui.config.test.step.limit;
	exports com.emc.mongoose.ui.config.test.step.limit.fail;
	exports com.emc.mongoose.ui.config.test.step.node;
	exports com.emc.mongoose.ui.log;
}
