package org.opensearch.migrations.reindexer.tracing;

import com.rfs.tracing.BaseRootRfsContext;
import com.rfs.tracing.RootWorkCoordinationContext;
import io.opentelemetry.api.OpenTelemetry;
import lombok.Getter;
import org.opensearch.migrations.tracing.IContextTracker;

public class RootDocumentMigrationContext
        extends BaseRootRfsContext
        implements IRootDocumentMigrationContext {
    public static final String SCOPE_NAME = "snapshotDocumentReindex";

    @Getter
    private final RootWorkCoordinationContext workCoordinationContext;
    public final DocumentMigrationContexts.DocumentReindexContext.MetricInstruments documentReindexInstruments;
    public final DocumentMigrationContexts.ShardSetupAttemptContext.MetricInstruments shardSetupMetrics;
    public final DocumentMigrationContexts.AddShardWorkItemContext.MetricInstruments addShardWorkItemMetrics;

    public RootDocumentMigrationContext(OpenTelemetry sdk, IContextTracker contextTracker,
                                        RootWorkCoordinationContext workCoordinationContext) {
        super(sdk, contextTracker);
        var meter = this.getMeterProvider().get(SCOPE_NAME);
        this.workCoordinationContext = workCoordinationContext;
        documentReindexInstruments = DocumentMigrationContexts.DocumentReindexContext.makeMetrics(meter);
        shardSetupMetrics = DocumentMigrationContexts.ShardSetupAttemptContext.makeMetrics(meter);
        addShardWorkItemMetrics = DocumentMigrationContexts.AddShardWorkItemContext.makeMetrics(meter);
    }

    @Override
    public IDocumentMigrationContexts.IShardSetupAttemptContext createDocsMigrationSetupContext() {
        return new DocumentMigrationContexts.ShardSetupAttemptContext(this);
    }

    @Override
    public IDocumentMigrationContexts.IDocumentReindexContext createReindexContext() {
        return new DocumentMigrationContexts.DocumentReindexContext(this);
    }

}
