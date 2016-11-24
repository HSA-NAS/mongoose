package com.emc.mongoose.storage.driver.net.http.s3;

import com.emc.mongoose.common.exception.UserShootHisFootException;
import com.emc.mongoose.common.io.AsyncCurrentDateInput;
import com.emc.mongoose.model.io.task.IoTask;
import static com.emc.mongoose.model.io.task.IoTask.IoResult;
import com.emc.mongoose.model.item.Item;
import com.emc.mongoose.storage.driver.net.http.base.BasicClientHandler;
import com.emc.mongoose.storage.driver.net.http.base.EmcConstants;
import com.emc.mongoose.storage.driver.net.http.base.HttpStorageDriverBase;
import static com.emc.mongoose.storage.driver.net.http.base.EmcConstants.KEY_X_EMC_NAMESPACE;
import static com.emc.mongoose.storage.driver.net.http.base.EmcConstants.PREFIX_KEY_X_EMC;
import static com.emc.mongoose.storage.driver.net.http.s3.S3Constants.AUTH_PREFIX;
import static com.emc.mongoose.storage.driver.net.http.s3.S3Constants.HEADERS_CANONICAL;
import static com.emc.mongoose.storage.driver.net.http.s3.S3Constants.KEY_X_AMZ_COPY_SOURCE;
import static com.emc.mongoose.storage.driver.net.http.s3.S3Constants.PREFIX_KEY_X_AMZ;
import static com.emc.mongoose.storage.driver.net.http.s3.S3Constants.SIGN_METHOD;
import static com.emc.mongoose.storage.driver.net.http.s3.S3Constants.URL_ARG_VERSIONING;
import static com.emc.mongoose.storage.driver.net.http.s3.S3Constants.VERSIONING_ENABLE_CONTENT;
import static com.emc.mongoose.storage.driver.net.http.s3.S3Constants.VERSIONING_DISABLE_CONTENT;
import static com.emc.mongoose.ui.config.Config.LoadConfig;
import static com.emc.mongoose.ui.config.Config.SocketConfig;
import static com.emc.mongoose.ui.config.Config.StorageConfig;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Markers;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 Created by kurila on 01.08.16.
 */
public final class S3StorageDriver<I extends Item, O extends IoTask<I, R>, R extends IoResult>
extends HttpStorageDriverBase<I, O, R> {
	
	private static final Logger LOG = LogManager.getLogger();
	private static final ThreadLocal<StringBuilder>
		BUFF_CANONICAL = new ThreadLocal<StringBuilder>() {
			@Override
			protected final StringBuilder initialValue() {
				return new StringBuilder();
			}
		};
	private static final ThreadLocal<Mac> THREAD_LOCAL_MAC = new ThreadLocal<>();
	private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();

	private final SecretKeySpec secretKey;
	
	public S3StorageDriver(
		final String jobName, final LoadConfig loadConfig,
		final StorageConfig storageConfig, final boolean verifyFlag, final SocketConfig socketConfig
	) throws UserShootHisFootException {
		super(jobName, loadConfig, storageConfig, verifyFlag, socketConfig);
		SecretKeySpec tmpKey = null;
		if(secret != null) {
			try {
				tmpKey = new SecretKeySpec(secret.getBytes(UTF_8.name()), SIGN_METHOD);
			} catch(final UnsupportedEncodingException e) {
				LogUtil.exception(LOG, Level.WARN, e, "Failure");
			}
		}
		secretKey = tmpKey;
	}
	
	@Override
	public final boolean createPath(final String path)
	throws RemoteException {

		// check the destination bucket if it exists w/ HEAD request
		final String nodeAddr = storageNodeAddrs[0];
		final HttpHeaders reqHeaders = new DefaultHttpHeaders();
		reqHeaders.set(HttpHeaderNames.HOST, nodeAddr);
		reqHeaders.set(HttpHeaderNames.CONTENT_LENGTH, 0);
		reqHeaders.set(HttpHeaderNames.DATE, AsyncCurrentDateInput.INSTANCE.get());
		applyDynamicHeaders(reqHeaders);
		applySharedHeaders(reqHeaders);
		applyAuthHeaders(HttpMethod.HEAD, path, reqHeaders);

		final FullHttpRequest checkBucketReq = new DefaultFullHttpRequest(
			HttpVersion.HTTP_1_1, HttpMethod.HEAD, path, Unpooled.EMPTY_BUFFER, reqHeaders,
			EmptyHttpHeaders.INSTANCE
		);
		final FullHttpResponse checkBucketResp;
		try {
			checkBucketResp = executeHttpRequest(checkBucketReq);
		} catch(final InterruptedException e) {
			return false;
		}
		boolean bucketExistedBefore = true;
		if(checkBucketResp != null) {
			if(HttpResponseStatus.NOT_FOUND.equals(checkBucketResp.status())) {
				bucketExistedBefore = false;
			} else if(!HttpStatusClass.SUCCESS.equals(checkBucketResp.status().codeClass())) {
				LOG.warn(
					Markers.ERR, "The bucket checking response is: {}", checkBucketResp.toString()
				);
			} else {
				LOG.info(Markers.MSG, "Bucket \"{}\" already exists", path);
			}
			checkBucketResp.release();
		}

		// create the destination bucket if it doesn't exists
		if(!bucketExistedBefore) {
			if(fsAccess) {
				reqHeaders.add(
					EmcConstants.KEY_X_EMC_FILESYSTEM_ACCESS_ENABLED, Boolean.toString(true)
				);
			}
			applyMetaDataHeaders(reqHeaders);
			applyAuthHeaders(HttpMethod.PUT, path, reqHeaders);
			final FullHttpRequest putBucketReq = new DefaultFullHttpRequest(
				HttpVersion.HTTP_1_1, HttpMethod.PUT, path, Unpooled.EMPTY_BUFFER, reqHeaders,
				EmptyHttpHeaders.INSTANCE
			);
			final FullHttpResponse putBucketResp;
			try {
				putBucketResp = executeHttpRequest(putBucketReq);
			} catch(final InterruptedException e) {
				return false;
			}
			if(!HttpStatusClass.SUCCESS.equals(putBucketResp.status().codeClass())) {
				LOG.warn(
					Markers.ERR, "The bucket creating response is: {}",
					putBucketResp.toString()
				);
				return false;
			} else {
				LOG.info(Markers.MSG, "Bucket \"{}\" created", path);
			}
			putBucketResp.release();
			if(fsAccess) {
				reqHeaders.remove(EmcConstants.KEY_X_EMC_FILESYSTEM_ACCESS_ENABLED);
			}
		}

		// check the bucket versioning state
		final String bucketVersioningReqUri = path.endsWith("/") ?
			path + "?" + URL_ARG_VERSIONING : path + "/?" + URL_ARG_VERSIONING;
		applyAuthHeaders(HttpMethod.GET, path, reqHeaders);
		final FullHttpRequest getBucketVersioningReq = new DefaultFullHttpRequest(
			HttpVersion.HTTP_1_1, HttpMethod.GET, bucketVersioningReqUri, Unpooled.EMPTY_BUFFER,
			reqHeaders, EmptyHttpHeaders.INSTANCE
		);
		final FullHttpResponse getBucketVersioningResp;
		try {
			getBucketVersioningResp = executeHttpRequest(getBucketVersioningReq);
		} catch(final InterruptedException e) {
			return false;
		}
		final boolean versioningEnabled;
		if(!HttpStatusClass.SUCCESS.equals(getBucketVersioningResp.status().codeClass())) {
			LOG.warn(
				Markers.ERR, "The bucket versioning checking response is: {}",
				getBucketVersioningResp.toString()
			);
			return false;
		} else {
			final String content = getBucketVersioningResp
				.content()
				.toString(StandardCharsets.US_ASCII);
			if(content.contains("Enabled")) {
				versioningEnabled = true;
			} else {
				versioningEnabled = false;
			}
		}
		getBucketVersioningResp.release();

		final FullHttpRequest putBucketVersioningReq;
		if(!versioning && versioningEnabled) {
			// disable bucket versioning
			reqHeaders.set(HttpHeaderNames.CONTENT_LENGTH, VERSIONING_DISABLE_CONTENT.length);
			applyAuthHeaders(HttpMethod.PUT, bucketVersioningReqUri, reqHeaders);
			putBucketVersioningReq = new DefaultFullHttpRequest(
				HttpVersion.HTTP_1_1, HttpMethod.PUT, bucketVersioningReqUri,
				Unpooled.wrappedBuffer(VERSIONING_DISABLE_CONTENT).retain(), reqHeaders,
				EmptyHttpHeaders.INSTANCE
			);
			final FullHttpResponse putBucketVersioningResp;
			try {
				putBucketVersioningResp = executeHttpRequest(putBucketVersioningReq);
			} catch(final InterruptedException e) {
				return false;
			}
			if(!HttpStatusClass.SUCCESS.equals(putBucketVersioningResp.status().codeClass())) {
				LOG.warn(Markers.ERR, "The bucket versioning setting response is: {}",
					putBucketVersioningResp.toString()
				);
				putBucketVersioningResp.release();
				return false;
			}
			putBucketVersioningResp.release();
		} else if(versioning && !versioningEnabled) {
			// enable bucket versioning
			reqHeaders.set(HttpHeaderNames.CONTENT_LENGTH, VERSIONING_ENABLE_CONTENT.length);
			applyAuthHeaders(HttpMethod.PUT, bucketVersioningReqUri, reqHeaders);
			putBucketVersioningReq = new DefaultFullHttpRequest(
				HttpVersion.HTTP_1_1, HttpMethod.PUT, bucketVersioningReqUri,
				Unpooled.wrappedBuffer(VERSIONING_DISABLE_CONTENT).retain(), reqHeaders,
				EmptyHttpHeaders.INSTANCE
			);
			final FullHttpResponse putBucketVersioningResp;
			try {
				putBucketVersioningResp = executeHttpRequest(putBucketVersioningReq);
			} catch(final InterruptedException e) {
				return false;
			}
			if(!HttpStatusClass.SUCCESS.equals(putBucketVersioningResp.status().codeClass())) {
				LOG.warn(Markers.ERR, "The bucket versioning setting response is: {}",
					putBucketVersioningResp.toString()
				);
				putBucketVersioningResp.release();
				return false;
			}
			putBucketVersioningResp.release();
		}

		return true;
	}

	@Override
	public final String getAuthToken()
	throws RemoteException {
		return null;
	}

	@Override
	public final void setAuthToken(final String authToken)
	throws RemoteException {
	}
	
	@Override
	protected final void appendSpecificHandlers(final ChannelPipeline pipeline) {
		super.appendSpecificHandlers(pipeline);
		pipeline.addLast(new BasicClientHandler<>(this, verifyFlag));
	}

	@Override
	protected void applyMetaDataHeaders(final HttpHeaders httpHeaders) {
		if(namespace != null && !namespace.isEmpty()) {
			httpHeaders.set(KEY_X_EMC_NAMESPACE, namespace);
		}
	}

	@Override
	public final void applyCopyHeaders(final HttpHeaders httpHeaders, final String srcPath)
	throws URISyntaxException {
		httpHeaders.set(KEY_X_AMZ_COPY_SOURCE, srcPath);
	}
	
	@Override
	protected final void applyAuthHeaders(
		final HttpMethod httpMethod, final String dstUriPath, final HttpHeaders httpHeaders
	) {
		final String signature;
		if(secretKey == null) {
			signature = null;
		} else {
			signature = getSignature(getCanonical(httpMethod, dstUriPath, httpHeaders), secretKey);
		}
		if(signature != null) {
			httpHeaders.set(
				HttpHeaderNames.AUTHORIZATION, AUTH_PREFIX + userName + ":" + signature
			);
		}
	}
	
	private String getCanonical(
		final HttpMethod httpMethod, final String dstUriPath, final HttpHeaders httpHeaders
	) {
		final StringBuilder buffCanonical = BUFF_CANONICAL.get();
		buffCanonical.setLength(0); // reset/clear
		buffCanonical.append(httpMethod.name());
		
		for(final AsciiString headerName : HEADERS_CANONICAL) {
			if(httpHeaders.contains(headerName)) {
				for(final String headerValue: httpHeaders.getAll(headerName)) {
					buffCanonical.append('\n').append(headerValue);
				}
			} else if(sharedHeaders != null && sharedHeaders.contains(headerName)) {
				buffCanonical.append('\n').append(sharedHeaders.get(headerName));
			} else {
				buffCanonical.append('\n');
			}
		}

		// x-amz-*, x-emc-*
		String headerName;
		Map<String, String> sortedHeaders = new TreeMap<>();
		if(sharedHeaders != null) {
			for(final Map.Entry<String, String> header : sharedHeaders) {
				headerName = header.getKey().toLowerCase();
				if(
					headerName.startsWith(PREFIX_KEY_X_AMZ) ||
					headerName.startsWith(PREFIX_KEY_X_EMC)
				) {
					sortedHeaders.put(headerName, header.getValue());
				}
			}
		}
		for(final Map.Entry<String, String> header : httpHeaders) {
			headerName = header.getKey().toLowerCase();
			if(headerName.startsWith(PREFIX_KEY_X_AMZ) || headerName.startsWith(PREFIX_KEY_X_EMC)) {
				sortedHeaders.put(headerName, header.getValue());
			}
		}
		for(final String k : sortedHeaders.keySet()) {
			buffCanonical.append('\n').append(k).append(':').append(sortedHeaders.get(k));
		}
		
		buffCanonical.append('\n');
		if(dstUriPath.contains("?") && !dstUriPath.endsWith("?" + URL_ARG_VERSIONING)) {
			buffCanonical.append(dstUriPath.substring(0, dstUriPath.indexOf("?")));
		} else {
			buffCanonical.append(dstUriPath);
		}
		
		if(LOG.isTraceEnabled(Markers.MSG)) {
			LOG.trace(Markers.MSG, "Canonical representation:\n{}", buffCanonical);
		}
		
		return buffCanonical.toString();
	}
	
	private String getSignature(final String canonicalForm, final SecretKeySpec secretKey) {
		
		if(secretKey == null) {
			return null;
		}
		
		final byte sigData[];
		Mac mac = THREAD_LOCAL_MAC.get();
		if(mac == null) {
			try {
				mac = Mac.getInstance(SIGN_METHOD);
				mac.init(secretKey);
			} catch(final NoSuchAlgorithmException | InvalidKeyException e) {
				throw new IllegalStateException("Failed to init MAC cypher instance");
			}
			THREAD_LOCAL_MAC.set(mac);
		}
		sigData = mac.doFinal(canonicalForm.getBytes());
		return BASE64_ENCODER.encodeToString(sigData);
	}
	
	@Override
	public final String toString() {
		return String.format(super.toString(), "s3");
	}
}
