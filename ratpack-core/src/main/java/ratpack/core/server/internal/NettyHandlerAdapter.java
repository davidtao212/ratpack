/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.core.server.internal;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ratpack.core.handling.Handler;
import ratpack.core.handling.Handlers;
import ratpack.core.handling.internal.ChainHandler;
import ratpack.core.handling.internal.DefaultContext;
import ratpack.core.handling.internal.DescribingHandler;
import ratpack.core.handling.internal.DescribingHandlers;
import ratpack.core.http.Headers;
import ratpack.core.http.MutableHeaders;
import ratpack.core.http.Response;
import ratpack.core.http.internal.*;
import ratpack.core.render.internal.DefaultRenderController;
import ratpack.core.server.DecodingErrorLevel;
import ratpack.core.server.ServerConfig;
import ratpack.exec.ExecController;
import ratpack.func.Action;
import ratpack.exec.registry.Registry;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.CharBuffer;
import java.nio.channels.ClosedChannelException;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

@ChannelHandler.Sharable
public class NettyHandlerAdapter extends ChannelInboundHandlerAdapter {

  private static final AttributeKey<Action<Object>> CHANNEL_SUBSCRIBER_ATTRIBUTE_KEY = AttributeKey.valueOf(NettyHandlerAdapter.class, "subscriber");
  private static final AttributeKey<ResponseTransmitter> RESPONSE_TRANSMITTER_KEY = AttributeKey.valueOf(NettyHandlerAdapter.class, "responseTransmitter");
  private static final AttributeKey<RequestBodyAccumulator> BODY_ACCUMULATOR_KEY = AttributeKey.valueOf(NettyHandlerAdapter.class, "requestBody");

  private static final AttributeKey<SSLSession> CLIENT_SSL_KEY = AttributeKey.valueOf(NettyHandlerAdapter.class, "sslSession");

  private final static Logger LOGGER = LoggerFactory.getLogger(NettyHandlerAdapter.class);

  private final Handler[] handlers;

  private final DefaultContext.ApplicationConstants applicationConstants;

  private final Registry serverRegistry;
  private final boolean development;
  private final DecodingErrorLevel decodingErrorLevel;
  private final Clock clock;

  public NettyHandlerAdapter(Registry serverRegistry, Handler handler) throws Exception {
    this.handlers = ChainHandler.unpack(handler);
    this.serverRegistry = serverRegistry;
    this.applicationConstants = new DefaultContext.ApplicationConstants(this.serverRegistry, new DefaultRenderController(), serverRegistry.get(ExecController.class), Handlers.notFound());
    this.development = serverRegistry.get(ServerConfig.class).isDevelopment();
    this.decodingErrorLevel = serverRegistry.get(ServerConfig.class).getDecodingErrorLevel();
    this.clock = serverRegistry.get(Clock.class);
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    ctx.channel().closeFuture().addListener(future -> channelClosed(ctx.channel()));
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    ctx.read();
    super.channelActive(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest) {
      newRequest(ctx, (HttpRequest) msg);
    } else if (msg instanceof HttpContent) {
      ((HttpContent) msg).touch();
      RequestBodyAccumulator bodyAccumulator = ctx.channel().attr(BODY_ACCUMULATOR_KEY).get();
      if (bodyAccumulator == null) {
        ((HttpContent) msg).release();
      } else {
        bodyAccumulator.add((HttpContent) msg);
      }

      // Read for the next request proactively so that we
      // detect if the client closes the connection.
      if (msg instanceof LastHttpContent) {
        ctx.channel().read();
      }
    } else {
      Action<Object> subscriber = ctx.channel().attr(CHANNEL_SUBSCRIBER_ATTRIBUTE_KEY).get();
      if (subscriber == null) {
        super.channelRead(ctx, ReferenceCountUtil.touch(msg));
      } else {
        subscriber.execute(ReferenceCountUtil.touch(msg));
      }
    }
  }

  private void newRequest(ChannelHandlerContext ctx, HttpRequest nettyRequest) throws Exception {
    if (!nettyRequest.decoderResult().isSuccess()) {
      LOGGER.debug("Failed to decode HTTP request.", nettyRequest.decoderResult().cause());
      sendError(ctx, HttpResponseStatus.BAD_REQUEST);
      return;
    }

    Headers requestHeaders = new NettyHeadersBackedHeaders(nettyRequest.headers());

    //Find the content length we will use this as an indicator of a body
    long contentLength = HttpUtil.getContentLength(nettyRequest, -1L);
    String transferEncoding = requestHeaders.get(HttpHeaderNames.TRANSFER_ENCODING);

    //If there is a content length or transfer encoding that indicates there is a body
    boolean hasBody = contentLength > 0 || transferEncoding != null;

    RequestBody requestBody = hasBody ? new RequestBody(contentLength, nettyRequest, ctx) : null;

    Channel channel = ctx.channel();

    if (requestBody != null) {
      channel.attr(BODY_ACCUMULATOR_KEY).set(requestBody);
    }
    InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();
    InetSocketAddress socketAddress = (InetSocketAddress) channel.localAddress();

    ConnectionIdleTimeout connectionIdleTimeout = ConnectionIdleTimeout.of(channel);

    DefaultRequest request = new DefaultRequest(
      clock.instant(),
      requestHeaders,
      nettyRequest.method(),
      nettyRequest.protocolVersion(),
      nettyRequest.uri(),
      remoteAddress,
      socketAddress,
      serverRegistry.get(ServerConfig.class),
      requestBody,
      connectionIdleTimeout,
      channel.attr(CLIENT_SSL_KEY).get()
    );

    HttpHeaders nettyHeaders = new DefaultHttpHeaders();
    MutableHeaders responseHeaders = new NettyHeadersBackedMutableHeaders(nettyHeaders);
    AtomicBoolean transmitted = new AtomicBoolean(false);

    DefaultResponseTransmitter responseTransmitter = new DefaultResponseTransmitter(transmitted, channel, clock, nettyRequest, request, nettyHeaders, requestBody);

    ctx.channel().attr(RESPONSE_TRANSMITTER_KEY).set(responseTransmitter);

    Action<Action<Object>> subscribeHandler = thing -> {
      transmitted.set(true);
      ctx.channel().attr(CHANNEL_SUBSCRIBER_ATTRIBUTE_KEY).set(thing);
    };

    DefaultContext.RequestConstants requestConstants = new DefaultContext.RequestConstants(
      applicationConstants,
      request,
      channel,
      responseTransmitter,
      subscribeHandler
    );

    Response response = new DefaultResponse(responseHeaders, ctx.alloc(), responseTransmitter);
    requestConstants.response = response;

    DefaultContext.start(channel.eventLoop(), requestConstants, serverRegistry, handlers, execution -> {
      if (!transmitted.get()) {
        Handler lastHandler = requestConstants.handler;
        StringBuilder description = new StringBuilder();
        description
          .append("No response sent for ")
          .append(request.getMethod().getName())
          .append(" request to ")
          .append(request.getUri());

        if (lastHandler != null) {
          description.append(" (last handler: ");

          if (lastHandler instanceof DescribingHandler) {
            ((DescribingHandler) lastHandler).describeTo(description);
          } else {
            DescribingHandlers.describeTo(lastHandler, description);
          }
          description.append(")");
        }

        String message = description.toString();
        LOGGER.warn(message);

        response.getHeaders().clear();

        ByteBuf body;
        if (development) {
          CharBuffer charBuffer = CharBuffer.wrap(message);
          body = ByteBufUtil.encodeString(ctx.alloc(), charBuffer, CharsetUtil.UTF_8);
          response.contentType(HttpHeaderConstants.PLAIN_TEXT_UTF8);
        } else {
          body = Unpooled.EMPTY_BUFFER;
        }

        response.getHeaders().set(HttpHeaderConstants.CONTENT_LENGTH, body.readableBytes());
        execution.getController().fork()
          .eventLoop(execution.getEventLoop())
          .start(e -> responseTransmitter.transmit(HttpResponseStatus.INTERNAL_SERVER_ERROR, body));
      }
    });
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    if (!isIgnorableException(cause)) {
      if (cause instanceof DecoderException && cause.getCause() != null && decodingErrorLevel != DecodingErrorLevel.FULL) {
        String msg = cause.getCause().getMessage();
        switch (decodingErrorLevel) {
          case INFO:
            LOGGER.info(msg);
            break;
          case WARN:
            LOGGER.warn(msg);
            break;
          case ERROR:
            LOGGER.error(msg);
            break;
          default:
        }
      } else {
        LOGGER.error("", cause);
      }
      if (ctx.channel().isActive()) {
        sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
      }
    }
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt instanceof IdleStateEvent) {
      ConnectionClosureReason.setIdle(ctx.channel());
      ctx.close();
    }
    if (evt instanceof SslHandshakeCompletionEvent && ((SslHandshakeCompletionEvent) evt).isSuccess()) {
      SSLEngine engine = ctx.pipeline().get(SslHandler.class).engine();
      if (engine.getWantClientAuth() || engine.getNeedClientAuth()) {
        SSLSession sslSession = engine.getSession();
        ctx.channel().attr(CLIENT_SSL_KEY).set(sslSession);
      }
    }

    super.userEventTriggered(ctx, evt);
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    ResponseTransmitter responseTransmitter = ctx.channel().attr(RESPONSE_TRANSMITTER_KEY).get();
    if (responseTransmitter != null) {
      responseTransmitter.onWritabilityChanged();
    }

    super.channelWritabilityChanged(ctx);
  }

  private void channelClosed(Channel channel) {
    ResponseTransmitter responseTransmitter = channel.attr(RESPONSE_TRANSMITTER_KEY).get();
    if (responseTransmitter != null) {
      responseTransmitter.onConnectionClosed();
    }

    RequestBodyAccumulator bodyAccumulator = channel.attr(BODY_ACCUMULATOR_KEY).get();
    if (bodyAccumulator != null) {
      bodyAccumulator.onClose();
    }
  }

  private static boolean isIgnorableException(Throwable throwable) {
    if (throwable instanceof ClosedChannelException) {
      return true;
    } else if (throwable instanceof IOException) {
      // There really does not seem to be a better way of detecting this kind of exception
      String message = throwable.getMessage();
      if (message == null) {
        return false;
      } else {
        message = message.toLowerCase(Locale.ROOT);
        return message.endsWith("connection reset by peer")
          || message.contains("broken pipe");
      }
    } else {
      return false;
    }
  }

  private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
    FullHttpResponse response = new DefaultFullHttpResponse(
      HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer("Failure: " + status.toString() + "\r\n", CharsetUtil.UTF_8));
    response.headers().set(HttpHeaderConstants.CONTENT_TYPE, HttpHeaderConstants.PLAIN_TEXT_UTF8);

    // Close the connection as soon as the error message is sent.
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }
}
