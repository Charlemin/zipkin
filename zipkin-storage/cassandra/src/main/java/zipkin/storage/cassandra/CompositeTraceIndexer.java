/**
 * Copyright 2015-2016 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.storage.cassandra;

import com.datastax.driver.core.Session;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import zipkin.Span;
import zipkin.internal.Nullable;
import zipkin.internal.Pair;

final class CompositeTraceIndexer {
  private final Set<TraceIndexer> indexers;
  // Shared across all threads as updates can come from any thread.
  // Shared for all indexes to make data management easier (ex. maximumSize)
  private final ConcurrentMap<PartitionKeyToTraceId, Pair<Long>> sharedState;

  CompositeTraceIndexer(Session session, int bucketCount, @Nullable Integer indexTtl) {
    TimestampCodec timestampCodec = new TimestampCodec(session);
    this.sharedState = CacheBuilder.newBuilder()
        // assume trace duration is less than 5m most often
        .expireAfterWrite(5, TimeUnit.MINUTES)
        // corresponds to the count of rows in the configured indexes inserted between now and the
        // expiration interval.
        //
        // bounded so that collectors that get large trace volume don't run out of memory before
        // the expiration interval
        //
        // it is hard to estimate precisely how many is the right number, particularly as binary
        // annotation values are included in partition keys (meaning they vary in size considerably)
        .maximumSize(500_000)
        .<PartitionKeyToTraceId, Pair<Long>>build().asMap();
    TraceIndexer.Factory factory =
        new TraceIndexer.Factory(session, timestampCodec, sharedState, indexTtl);
    this.indexers = ImmutableSet.of(
        factory.create(new InsertTraceIdByServiceName(bucketCount)),
        factory.create(new InsertTraceIdBySpanName()),
        factory.create(new InsertTraceIdByAnnotation(bucketCount))
    );
  }

  ImmutableSet<ListenableFuture<?>> index(List<Span> spans) {
    ImmutableSet.Builder<ListenableFuture<?>> result = ImmutableSet.builder();
    for (TraceIndexer optimizer : indexers) {
      result.addAll(optimizer.index(spans));
    }
    return result.build();
  }

  public void clear() {
    sharedState.clear();
  }
}
