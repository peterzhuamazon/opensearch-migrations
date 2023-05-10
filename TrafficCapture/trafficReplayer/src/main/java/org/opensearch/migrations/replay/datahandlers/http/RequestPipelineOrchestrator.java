package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;
import org.opensearch.migrations.transform.JsonTransformer;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * This class is meant to be the single place for all pipeline manipulations for HttpRequests.
 * Comments are strewn through pipeline additions that show the types of messages that are
 * expected as input and what may be possibly output.  In many cases a message may be transformed
 * while still maintaining its type.  For example, HttpContent might have been compressed going
 * into a decompressor handler and uncompressed coming out.  Both input and outputs will be
 * packaged as HttpContent objects, though there contents will be different.
 *
 * Each handler where the content may undergo changes will show an incremented value for a nonce
 * for that type.  e.g. &quot;HttpContent(1)&quot; vs &quot;HttpContent(2)&quot;.
 */
@Slf4j
public class RequestPipelineOrchestrator {
    public static final String COMPRESS_HANDLER_NAME = "compressionHandler";
    public static final String AFTER_COMPRESS_CHUNKED_HANDLER_NAME = "postCompressChunkedHandler";
    public static final String COMPRESSED_CHUNK_HEADER_MERGER_HANDLER_NAME = "compressedChunkHeaderMerger";
    public static final String CONTENT_STREAMER_HANDLER_NAME = "contentStreamerHandler";

    private final List<List<Integer>> chunkSizes;
    private final IPacketToHttpHandler packetReceiver;

    public RequestPipelineOrchestrator(List<List<Integer>> chunkSizes, IPacketToHttpHandler packetReceiver) {
        this.chunkSizes = chunkSizes;
        this.packetReceiver = packetReceiver;
    }

    static void removeThisAndPreviousHandlers(ChannelPipeline pipeline, ChannelHandler beforeHandler) {
        do {
            var h = pipeline.removeFirst();
            if (h == beforeHandler) {
                break;
            }
        } while (true);
    }

    void addContentRepackingHandlers(ChannelPipeline pipeline) {
        addContentParsingHandlers(pipeline, false);
    }

    void addJsonParsingHandlers(ChannelPipeline pipeline) {
        addContentParsingHandlers(pipeline, true);
    }

    void addInitialHandlers(ChannelPipeline pipeline,
                            JsonTransformer transformer,
                            Consumer<HTTP_CONSUMPTION_STATUS> statusWatcher) {
        pipeline.addFirst(new HttpRequestDecoder());
        // IN:  Netty HttpRequest(1) + HttpContent(1) blocks (which may be compressed)
        // OUT: Netty HttpRequest(1) + HttpJsonMessage(1) with only headers PLUS + HttpContent(1) blocks
        // Note1: original Netty headers are preserved so that HttpContentDecompressor can work appropriately.
        //        HttpJsonMessage is used so that we can capture the headers exactly as they were and to
        //        observe packet sizes.
        // Note2: This handler may remove itself and all other handlers and replace the pipeline ONLY with the
        //        "baseline" handlers.  In that case, the pipeline will be processing only ByteBufs, hence the
        //        reason that there's some branching in the types that different handlers consume..
        pipeline.addLast(new NettyDecodedHttpRequestHandler(transformer, chunkSizes, packetReceiver, statusWatcher));
    }

    void addContentParsingHandlers(ChannelPipeline pipeline, boolean addFullJsonTransformer) {
        log.warn("Adding handlers to pipeline");
        //  IN: Netty HttpRequest(1) + HttpJsonMessage(1) with headers + HttpContent(1) blocks (which may be compressed)
        // OUT: Netty HttpRequest(2) + HttpJsonMessage(1) with headers + HttpContent(2) uncompressed blocks
        pipeline.addLast(new HttpContentDecompressor());
        if (addFullJsonTransformer) {
            log.warn("Adding JSON handlers to pipeline");
            //  IN: Netty HttpRequest(2) + HttpJsonMessage(1) with headers + HttpContent(2) blocks
            // OUT: Netty HttpRequest(2) + HttpJsonMessage(2) with headers AND payload
            pipeline.addLast(new NettyJsonBodyAccumulateHandler());
            //  IN: Netty HttpRequest(2) + HttpJsonMessage(2) with headers AND payload
            // OUT: Netty HttpRequest(2) + HttpJsonMessage(3) with headers AND payload (transformed)
            pipeline.addLast(new NettyJsonBodyConvertHandler());
            // IN:  Netty HttpRequest(2) + HttpJsonMessage(3) with headers AND payload
            // OUT: Netty HttpRequest(2) + HttpJsonMessage(3) with headers only + HttpContent(3) blocks
            pipeline.addLast(new NettyJsonBodySerializeHandler());
        }
        // IN:  Netty HttpRequest(2) + HttpJsonMessage(3) with headers only + HttpContent(3) blocks
        // OUT: Netty HttpRequest(3) + HttpJsonMessage(4) with headers only + HttpContent(4) blocks
        pipeline.addLast(new LoggingHandler(LogLevel.ERROR, ByteBufFormat.HEX_DUMP));
        pipeline.addLast(COMPRESS_HANDLER_NAME, new NettyJsonContentCompressor());
        pipeline.addLast(new LoggingHandler(LogLevel.WARN, ByteBufFormat.HEX_DUMP));
        pipeline.addLast(CONTENT_STREAMER_HANDLER_NAME, new NettyJsonContentStreamToByteBufHandler());
        addBaselineHandlers(pipeline);
    }

    /**
     * When the NettyJsonContentCompressor performs compression, these handlers will be added to the pipeline.
     * @param pipeline
     */
//    public static void addCompressedHelpers(ChannelPipeline pipeline) {
//        // IN:  Netty HttpRequest(2) + HttpJsonMessage(3) with headers only + HttpContent(3) blocks
//        // OUT: Netty HttpRequest(3) + HttpJsonMessage(3) with headers only + HttpContent(4) blocks
//        pipeline.addAfter(COMPRESS_HANDLER_NAME, AFTER_COMPRESS_CHUNKED_HANDLER_NAME, new ChunkedWriteHandler());
//        // IN:  Netty HttpRequest(3) + HttpJsonMessage(3) with headers only + HttpContent(4) blocks
//        // OUT:                        HttpJsonMessage(4) with headers only + HttpContent(4) blocks
//        pipeline.addAfter(AFTER_COMPRESS_CHUNKED_HANDLER_NAME, COMPRESSED_CHUNK_HEADER_MERGER_HANDLER_NAME,
//                new NettyJsonHeaderReunificationHandler());
//
//    }

    void addBaselineHandlers(ChannelPipeline pipeline) {
        //  IN: ByteBufs(2) + HttpJsonMessage(4) with headers only + HttpContent(1) (if the repackaging handlers were skipped)
        // OUT: ByteBufs(3) which are sized similarly to how they were received
        pipeline.addLast(new NettyJsonToByteBufHandler(Collections.unmodifiableList(chunkSizes)));
        pipeline.addLast(new LoggingHandler(LogLevel.INFO, ByteBufFormat.HEX_DUMP));
        // IN:  ByteBufs(3)
        // OUT: nothing - terminal!  ByteBufs are routed to the packet handler!
        pipeline.addLast(new NettySendByteBufsToPacketHandlerHandler(packetReceiver));
    }
}
