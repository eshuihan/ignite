/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.eviction;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.affinity.*;
import org.apache.ignite.cache.eviction.*;
import org.apache.ignite.cache.eviction.fifo.*;
import org.apache.ignite.cache.store.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.testframework.junits.common.*;
import org.apache.ignite.transactions.*;

import javax.cache.configuration.*;
import java.util.*;

import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.*;
import static org.apache.ignite.transactions.IgniteTxConcurrency.*;
import static org.apache.ignite.transactions.IgniteTxIsolation.*;

/**
 *
 */
public class GridCacheEvictionTouchSelfTest extends GridCommonAbstractTest {
    /** IP finder. */
    private static final TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** */
    private CacheEvictionPolicy<?, ?> plc;

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration c = super.getConfiguration(gridName);

        TransactionConfiguration txCfg = c.getTransactionsConfiguration();

        txCfg.setDefaultTxConcurrency(PESSIMISTIC);
        txCfg.setDefaultTxIsolation(REPEATABLE_READ);

        CacheConfiguration cc = defaultCacheConfiguration();

        cc.setCacheMode(REPLICATED);

        cc.setSwapEnabled(false);

        cc.setWriteSynchronizationMode(FULL_SYNC);

        cc.setEvictionPolicy(plc);

        CacheStore store = new GridCacheGenericTestStore<Object, Object>() {
            @Override public Object load(Object key) {
                return key;
            }

            @Override public Map<Object, Object> loadAll(Iterable<?> keys) {
                Map<Object, Object> loaded = new HashMap<>();

                for (Object key : keys)
                    loaded.put(key, key);

                return loaded;
            }
        };

        cc.setCacheStoreFactory(new FactoryBuilder.SingletonFactory(store));
        cc.setReadThrough(true);
        cc.setWriteThrough(true);
        cc.setLoadPreviousValue(true);

        c.setCacheConfiguration(cc);

        TcpDiscoverySpi disco = new TcpDiscoverySpi();

        disco.setIpFinder(ipFinder);

        c.setDiscoverySpi(disco);

        return c;
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        plc = null;

        super.afterTest();
    }

    /**
     * @throws Exception If failed.
     */
    public void testPolicyConsistency() throws Exception {
        plc = new CacheFifoEvictionPolicy<Object, Object>(500);

        try {
            Ignite ignite = startGrid(1);

            final GridCache<Integer, Integer> cache = ignite.cache(null);

            final Random rnd = new Random();

            try (IgniteTx tx = cache.txStart()) {
                int iterCnt = 20;
                int keyCnt = 5000;

                for (int i = 0; i < iterCnt; i++) {
                    int j = rnd.nextInt(keyCnt);

                    // Put or remove?
                    if (rnd.nextBoolean())
                        cache.putx(j, j);
                    else
                        cache.remove(j);

                    if (i != 0 && i % 1000 == 0)
                        info("Stats [iterCnt=" + i + ", size=" + cache.size() + ']');
                }

                CacheFifoEvictionPolicy<Integer, Integer> plc0 = (CacheFifoEvictionPolicy<Integer, Integer>) plc;

                if (!plc0.queue().isEmpty()) {
                    for (CacheEntry<Integer, Integer> e : plc0.queue())
                        U.warn(log, "Policy queue item: " + e);

                    fail("Test failed, see logs for details.");
                }

                tx.commit();
            }
        }
        catch (Throwable t) {
            error("Test failed.", t);

            fail("Test failed, see logs for details.");
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testEvictSingle() throws Exception {
        plc = new CacheFifoEvictionPolicy<Object, Object>(500);

        try {
            Ignite ignite = startGrid(1);

            final GridCache<Integer, Integer> cache = ignite.cache(null);

            for (int i = 0; i < 100; i++)
                cache.put(i, i);

            assertEquals(100, ((CacheFifoEvictionPolicy)plc).queue().size());

            for (int i = 0; i < 100; i++)
                cache.evict(i);

            assertEquals(0, ((CacheFifoEvictionPolicy)plc).queue().size());
            assertEquals(0, cache.size());
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testEvictAll() throws Exception {
        plc = new CacheFifoEvictionPolicy<Object, Object>(500);

        try {
            Ignite ignite = startGrid(1);

            final GridCache<Integer, Integer> cache = ignite.cache(null);

            Collection<Integer> keys = new ArrayList<>(100);

            for (int i = 0; i < 100; i++) {
                cache.put(i, i);

                keys.add(i);
            }

            assertEquals(100, ((CacheFifoEvictionPolicy)plc).queue().size());

            cache.evictAll(keys);

            assertEquals(0, ((CacheFifoEvictionPolicy)plc).queue().size());
            assertEquals(0, cache.size());
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testGroupLock() throws Exception {
        plc = new CacheFifoEvictionPolicy<>(100);

        try {
            Ignite g = startGrid(1);

            Integer affKey = 1;

            GridCache<CacheAffinityKey<Object>, Integer> cache = g.cache(null);

            IgniteTx tx = cache.txStartAffinity(affKey, PESSIMISTIC, REPEATABLE_READ, 0, 5);

            try {
                for (int i = 0; i < 5; i++)
                    cache.put(new CacheAffinityKey<Object>(i, affKey), i);

                tx.commit();
            }
            finally {
                tx.close();
            }

            assertEquals(5, ((CacheFifoEvictionPolicy)plc).queue().size());

            tx = cache.txStartAffinity(affKey, PESSIMISTIC, REPEATABLE_READ, 0, 5);

            try {
                for (int i = 0; i < 5; i++)
                    cache.remove(new CacheAffinityKey<Object>(i, affKey));

                tx.commit();
            }
            finally {
                tx.close();
            }

            assertEquals(0, ((CacheFifoEvictionPolicy)plc).queue().size());
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testPartitionGroupLock() throws Exception {
        plc = new CacheFifoEvictionPolicy<>(100);

        try {
            Ignite g = startGrid(1);

            Integer affKey = 1;

            GridCache<Object, Integer> cache = g.cache(null);

            IgniteTx tx = cache.txStartPartition(cache.affinity().partition(affKey), PESSIMISTIC, REPEATABLE_READ,
                0, 5);

            try {
                for (int i = 0; i < 5; i++)
                    cache.put(new CacheAffinityKey<Object>(i, affKey), i);

                tx.commit();
            }
            finally {
                tx.close();
            }

            assertEquals(5, ((CacheFifoEvictionPolicy)plc).queue().size());

            tx = cache.txStartPartition(cache.affinity().partition(affKey), PESSIMISTIC, REPEATABLE_READ, 0, 5);

            try {
                for (int i = 0; i < 5; i++)
                    cache.remove(new CacheAffinityKey<Object>(i, affKey));

                tx.commit();
            }
            finally {
                tx.close();
            }

            assertEquals(0, ((CacheFifoEvictionPolicy)plc).queue().size());
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testReload() throws Exception {
        plc = new CacheFifoEvictionPolicy<Object, Object>(100);

        try {
            Ignite ignite = startGrid(1);

            final GridCache<Integer, Integer> cache = ignite.cache(null);

            for (int i = 0; i < 10000; i++)
                cache.reload(i);

            assertEquals(100, cache.size());
            assertEquals(100, cache.size());
            assertEquals(100, ((CacheFifoEvictionPolicy)plc).queue().size());

            Collection<Integer> keys = new ArrayList<>(10000);

            for (int i = 0; i < 10000; i++)
                keys.add(i);

            cache.reloadAll(keys);

            assertEquals(100, cache.size());
            assertEquals(100, cache.size());
            assertEquals(100, ((CacheFifoEvictionPolicy)plc).queue().size());
        }
        finally {
            stopAllGrids();
        }
    }
}
