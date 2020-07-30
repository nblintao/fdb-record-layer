/*
 * RecordQueryFetchFromPartialRecordPlan.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2020 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.query.plan.plans;

import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.ExecuteProperties;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.provider.common.StoreTimer;
import com.apple.foundationdb.record.provider.foundationdb.FDBQueriedRecord;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStoreBase;
import com.apple.foundationdb.record.provider.foundationdb.FDBStoreTimer;
import com.apple.foundationdb.record.provider.foundationdb.IndexOrphanBehavior;
import com.apple.foundationdb.record.query.plan.temp.ExpressionRef;
import com.apple.foundationdb.record.query.plan.temp.GroupExpressionRef;
import com.apple.foundationdb.record.query.plan.temp.Quantifier;
import com.apple.foundationdb.record.query.plan.temp.RelationalExpression;
import com.apple.foundationdb.record.query.plan.temp.explain.NodeInfo;
import com.apple.foundationdb.record.query.plan.temp.explain.PlannerGraph;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * A query plan that transforms a stream of partial records (derived from index entries, as in the {@link RecordQueryCoveringIndexPlan})
 * into full records by fetching the records by primary key.
 */
public class RecordQueryFetchFromPartialRecordPlan implements RecordQueryPlanWithChild {
    @Nonnull
    private final ExpressionRef<RecordQueryPlan> inner;

    public RecordQueryFetchFromPartialRecordPlan(@Nonnull RecordQueryPlan inner) {
        this(GroupExpressionRef.of(inner));
    }

    public RecordQueryFetchFromPartialRecordPlan(@Nonnull final ExpressionRef<RecordQueryPlan> inner) {
        this.inner = inner;
    }

    @Nonnull
    @Override
    public <M extends Message> RecordCursor<FDBQueriedRecord<M>> execute(@Nonnull final FDBRecordStoreBase<M> store,
                                                                         @Nonnull final EvaluationContext context,
                                                                         @Nullable final byte[] continuation,
                                                                         @Nonnull final ExecuteProperties executeProperties) {
        // Plan return exactly one (full) record for each (partial) record from inner, so we can preserve all limits.
        return store.fetchIndexRecords(getChild().execute(store, context, continuation, executeProperties)
                        .map(FDBQueriedRecord::getIndexEntry), IndexOrphanBehavior.ERROR, executeProperties.getState())
                .map(store::queriedRecord);
    }

    @Nonnull
    @Override
    public RecordQueryPlan getChild() {
        return inner.get();
    }

    @Nonnull
    @Override
    public List<? extends Quantifier> getQuantifiers() {
        return ImmutableList.of(Quantifier.physical(inner));
    }

    @Override
    public boolean isReverse() {
        return getChild().isReverse();
    }

    @Override
    public void logPlanStructure(final StoreTimer timer) {
        timer.increment(FDBStoreTimer.Counts.PLAN_FETCH);
    }

    @Override
    public int getComplexity() {
        return 1 + getChild().getComplexity();
    }

    @Override
    public boolean equalsWithoutChildren(@Nonnull final RelationalExpression otherExpression) {
        return otherExpression instanceof RecordQueryFetchFromPartialRecordPlan;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RecordQueryFetchFromPartialRecordPlan that = (RecordQueryFetchFromPartialRecordPlan)o;
        return getChild().equals(that.getChild());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getChild());
    }

    @Override
    public int planHash() {
        return 13 + 7 * getChild().planHash();
    }

    @Override
    public String toString() {
        return "Fetch(" + getChild().toString() + ")";
    }

    @Nonnull
    @Override
    public PlannerGraph rewritePlannerGraph(@Nonnull final List<? extends PlannerGraph> childGraphs) {
        return PlannerGraph.fromNodeAndChildGraphs(
                new PlannerGraph.OperatorNodeWithInfo(this, NodeInfo.FETCH_OPERATOR),
                childGraphs);
    }
}
