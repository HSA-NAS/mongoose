package com.emc.mongoose.storage.mock.impl.web.request;
// mongoose-common.jar
import com.emc.mongoose.common.conf.RunTimeConfig;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
import com.emc.mongoose.common.net.ServiceUtils;
// mongoose-core-api.jar
import com.emc.mongoose.core.api.data.DataObject;
import static com.emc.mongoose.core.api.io.req.conf.WSRequestConfig.VALUE_RANGE_PREFIX;
import static com.emc.mongoose.core.api.io.req.conf.WSRequestConfig.VALUE_RANGE_CONCAT;
// mongoose-core-impl.jar
import com.emc.mongoose.core.impl.data.UniformData;
// mongoose-storage-mock.jar
import com.emc.mongoose.storage.mock.api.IOStats;
import com.emc.mongoose.storage.mock.api.WSMock;
import com.emc.mongoose.storage.mock.api.WSObjectMock;
import com.emc.mongoose.storage.mock.impl.web.response.BasicWSResponseProducer;
//
import org.apache.commons.codec.binary.Hex;
//
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.ProtocolVersion;
import org.apache.http.RequestLine;
import org.apache.http.protocol.HttpContext;
//
import org.apache.http.nio.protocol.HttpAsyncExchange;
import org.apache.http.nio.protocol.HttpAsyncRequestConsumer;
import org.apache.http.nio.protocol.HttpAsyncRequestHandler;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
/**
 Created by andrey on 13.05.15.
 */
public abstract class WSRequestHandlerBase<T extends WSObjectMock>
implements HttpAsyncRequestHandler<HttpRequest> {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	protected final static String
		METHOD_PUT = "put",
		METHOD_GET = "get",
		METHOD_POST = "post",
		METHOD_HEAD = "head",
		METHOD_DELETE = "delete",
		METHOD_TRACE = "trace";
	//
	//private final static int RING_OFFSET_RADIX = RunTimeConfig.getContext().getDataRadixOffset();
	private final static AtomicLong
		LAST_OFFSET = new AtomicLong(
			Math.abs(
				Long.reverse(System.currentTimeMillis()) ^
					Long.reverseBytes(System.nanoTime()) ^
					ServiceUtils.getHostAddrCode()
			)
		);
	private final IOStats ioStats;
	private final float rateLimit;
	private final AtomicInteger lastMilliDelay = new AtomicInteger(1);
	private final WSMock<T> sharedStorage;
	//
	protected WSRequestHandlerBase(
		final RunTimeConfig runTimeConfig, final WSMock<T> sharedStorage
	) {
		this.rateLimit = runTimeConfig.getLoadLimitRate();
		this.sharedStorage = sharedStorage;
		this.ioStats = sharedStorage.getStats();
	}
	//
	private final static ThreadLocal<BasicWSRequestConsumer>
		THRLOC_REQ_CONSUMER = new ThreadLocal<>();
	@Override
	public final HttpAsyncRequestConsumer<HttpRequest> processRequest(
		final HttpRequest request, final HttpContext context
	) throws HttpException, IOException {
		try {
			BasicWSRequestConsumer reqConsumer = THRLOC_REQ_CONSUMER.get();
			if(reqConsumer == null) {
				reqConsumer = new BasicWSRequestConsumer();
				THRLOC_REQ_CONSUMER.set(reqConsumer);
			}
			return reqConsumer;
		} catch(final IllegalArgumentException | IllegalStateException e) {
			throw new MethodNotSupportedException("Request consumer instantiation failure", e);
		}
	}
	//
	private final static ThreadLocal<BasicWSResponseProducer>
		THRLOC_RESP_PRODUCER = new ThreadLocal<>();
	@Override
	public final void handle(
		final HttpRequest r, final HttpAsyncExchange httpExchange, final HttpContext httpContext
	) {
		// load rate limitation algorithm
		if(rateLimit > 0) {
			if(ioStats.getMeanRate() > rateLimit) {
				try {
					Thread.sleep(lastMilliDelay.incrementAndGet());
				} catch(final InterruptedException e) {
					return;
				}
			} else if(lastMilliDelay.get() > 0) {
				lastMilliDelay.decrementAndGet();
			}
		}
		// prepare
		final HttpRequest httpRequest = httpExchange.getRequest();
		final HttpResponse httpResponse = httpExchange.getResponse();
		final RequestLine requestLine = httpRequest.getRequestLine();
		final String method = requestLine.getMethod().toLowerCase(LogUtil.LOCALE_DEFAULT);
		// get URI components
		final String[] requestURI = requestLine.getUri().split("/");
		final String dataId = requestURI[requestURI.length - 1];
		//
		handleActually(httpRequest, httpResponse, method, requestURI, dataId);
		// done
		BasicWSResponseProducer respProducer = THRLOC_RESP_PRODUCER.get();
		if(respProducer == null) {
			respProducer = new BasicWSResponseProducer();
			THRLOC_RESP_PRODUCER.set(respProducer);
		}
		respProducer.setResponse(httpResponse);
		httpExchange.submitResponse(respProducer);
	}
	//
	protected abstract void handleActually(
		final HttpRequest httpRequest, final HttpResponse httpResponse,
		final String method, final String requestURI[], final String dataId
	);
	//
	protected static String randomString(final int len) {
		final byte buff[] = new byte[len];
		ThreadLocalRandom.current().nextBytes(buff);
		return Hex.encodeHexString(buff);
	}
	//
	protected void handleGenericDataReq(
		final HttpRequest httpRequest, final HttpResponse httpResponse, final String method,
		final String dataId
	) {
		switch(method) {
			case METHOD_POST:
				handleWrite(httpRequest, httpResponse, dataId);
				break;
			case METHOD_PUT:
				handleWrite(httpRequest, httpResponse, dataId);
				break;
			case METHOD_GET:
				handleRead(httpResponse, dataId);
				break;
			case METHOD_HEAD:
				httpResponse.setStatusCode(HttpStatus.SC_OK);
				break;
			case METHOD_DELETE:
				handleDelete(httpResponse, dataId);
				break;
		}
	}
	//
	private void handleWrite(
		final HttpRequest request, final HttpResponse response, final String dataId
	) {
		try {
			response.setStatusCode(HttpStatus.SC_OK);
			final Header rangeHeaders[] = request.getHeaders(HttpHeaders.RANGE);
			//
			if(rangeHeaders == null || rangeHeaders.length == 0) {
				// write or recreate data item
				final T dataObject = createDataObject(request, dataId);
				ioStats.markCreate(dataObject.getSize());
			} else {
				// else do append or update if data item exist
				handleRanges(
					dataId, rangeHeaders, response,
					HttpEntityEnclosingRequest.class.cast(request).getEntity().getContentLength()
				);
			}
		} catch(final ExecutionException | InterruptedException | HttpException e) {
			response.setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
			LogUtil.exception(LOG, Level.ERROR, e, "Write storage failure");
			ioStats.markCreate(-1);
		} catch(final NumberFormatException e) {
			response.setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
			LogUtil.exception(
				LOG, Level.ERROR, e,
				"Failed to decode the data id \"{}\" as ring buffer offset", dataId
			);
			ioStats.markCreate(-1);
		}
	}
	//
	private void handleRanges(
		final String dataId, final Header rangeHeaders[], final HttpResponse httpResponse,
		final long contentLength
	) throws IllegalArgumentException {
		String rangeHeaderValue, rangeValuePairs[], rangeValue[];
		long offset;
		for(final Header rangeHeader : rangeHeaders) {
			rangeHeaderValue = rangeHeader.getValue();
			if(rangeHeaderValue.startsWith(VALUE_RANGE_PREFIX)) {
				rangeHeaderValue = rangeHeaderValue.substring(
					VALUE_RANGE_PREFIX.length(), rangeHeaderValue.length()
				);
				rangeValuePairs = rangeHeaderValue.split(RunTimeConfig.LIST_SEP);
				for(final String rangeValuePair : rangeValuePairs) {
					rangeValue = rangeValuePair.split(VALUE_RANGE_CONCAT);
					try {
						if(rangeValue.length == 1) {
							sharedStorage.append(
								dataId, Long.parseLong(rangeValue[0]), contentLength
							);
						} else if(rangeValue.length == 2) {
							offset = Long.parseLong(rangeValue[0]);
							sharedStorage.update(
								dataId, offset, Long.parseLong(rangeValue[1]) - offset + 1
							);
						} else {
							LOG.warn(
								Markers.ERR, "Invalid range header value: \"{}\"", rangeHeaderValue
							);
						}
					} catch(
						final ExecutionException | InterruptedException | NumberFormatException e
					) {
						LogUtil.exception(LOG, Level.WARN, e, "Range modification failure");
						httpResponse.setStatusCode(HttpStatus.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
					}
				}
			} else {
				LOG.warn(Markers.ERR, "Invalid range header value: \"{}\"", rangeHeaderValue);
			}
		}
	}
	//
	private void handleRead(final HttpResponse response, final String dataId) {
		try {
			final T dataObject = sharedStorage.read(dataId, 0, 0);
			if(dataObject == null) {
				response.setStatusCode(HttpStatus.SC_NOT_FOUND);
				if(LOG.isTraceEnabled(Markers.MSG)) {
					LOG.trace(Markers.ERR, "No such object: {}", dataId);
				}
				ioStats.markRead(-1);
			} else {
				response.setStatusCode(HttpStatus.SC_OK);
				if(LOG.isTraceEnabled(Markers.MSG)) {
					LOG.trace(Markers.MSG, "Send data object with ID: {}", dataId);
				}
				response.setEntity(dataObject);
				ioStats.markRead(dataObject.getSize());
			}
		} catch(final InterruptedException | ExecutionException e) {
			response.setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
			LogUtil.exception(LOG, Level.ERROR, e, "Failed to read the data object: {}", dataId);
			ioStats.markRead(-1);
		}
	}
	//
	private void handleDelete(final HttpResponse response, final String dataId){
		try {
			final T dataObject = sharedStorage.delete(dataId);
			if(dataObject == null) {
				response.setStatusCode(HttpStatus.SC_NOT_FOUND);
				if(LOG.isTraceEnabled(Markers.MSG)) {
					LOG.trace(Markers.ERR, "No such object: {}", dataId);
				}
			} else {
				response.setStatusCode(HttpStatus.SC_OK);
				if(LOG.isTraceEnabled(Markers.MSG)) {
					LOG.trace(Markers.MSG, "Delete data object with ID: {}", dataId);
				}
				ioStats.markDelete();
			}
		} catch(final InterruptedException | ExecutionException e) {
			response.setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
			LogUtil.exception(LOG, Level.ERROR, e, "Failed to delete the data object: {}", dataId);
		}
	}
	//
	private T createDataObject(final HttpRequest request, final String dataId)
	throws HttpException, NumberFormatException, InterruptedException, ExecutionException {
		final HttpEntity entity = HttpEntityEnclosingRequest.class.cast(request).getEntity();
		final long size = entity.getContentLength();
		// create data object or get it for append or update
		final long offset = Long.valueOf(dataId, Character.MAX_RADIX);
		return sharedStorage.create(dataId, offset, size);
	}
	/*
	offset for mongoose versions since v0.6:
		final long offset = Long.valueOf(dataID, WSRequestConfigBase.RADIX);
	offset for mongoose v0.4x and 0.5x:
		final byte dataIdBytes[] = Base64.decodeBase64(dataID);
		final long offset  = ByteBuffer.allocate(Long.SIZE / Byte.SIZE).put(dataIdBytes).getLong(0);
	offset for mongoose versions prior to v0.4:
		final long offset = Long.valueOf(dataID, 0x10);
	@Deprecated
	private static long decodeRingBufferOffset(final String dataID)
	throws HttpException, NumberFormatException {
		long offset;
		if(RING_OFFSET_RADIX == 0x40) { // base64
			offset = ByteBuffer
				.allocate(Long.SIZE / Byte.SIZE)
				.put(Base64.decodeBase64(dataID))
				.getLong(0);
		} else if(RING_OFFSET_RADIX > 1 && RING_OFFSET_RADIX <= Character.MAX_RADIX) {
			offset = Long.valueOf(dataID, RING_OFFSET_RADIX);
		} else {
			throw new HttpException("Unsupported data ring offset radix: " + RING_OFFSET_RADIX);
		}
		return offset;
	}*/
	//
	protected static String generateId() {
		return Long.toString(UniformData.nextOffset(LAST_OFFSET), DataObject.ID_RADIX);
	}
}
