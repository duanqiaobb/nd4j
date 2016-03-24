package org.nd4j.jita.memory;

import jcuda.Pointer;
import org.nd4j.jita.allocator.enums.AllocationStatus;
import org.nd4j.jita.allocator.impl.AllocationPoint;
import org.nd4j.jita.allocator.impl.AllocationShape;
import org.nd4j.jita.allocator.pointers.CudaPointer;
import org.nd4j.jita.allocator.pointers.PointersPair;
import org.nd4j.jita.allocator.utils.AllocationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 * @author raver119@gmail.com
 */
public class CudaCachingProvider extends CudaDirectProvider implements MemoryProvider {
    private static Logger log = LoggerFactory.getLogger(CudaCachingProvider.class);

    private ConcurrentHashMap<AllocationShape, CacheHolder> zeroCache = new ConcurrentHashMap<>();

    private AtomicLong cacheHit = new AtomicLong(0);
    private AtomicLong cacheMiss = new AtomicLong(0);

    private AtomicLong allocRequests = new AtomicLong(0);
    private AtomicLong cachedAmount = new AtomicLong(0);

    private Semaphore singleLock = new Semaphore(1);

    // we don't cache allocations greater then this value
    private final long MAX_SINGLE_ALLOCATION = 1000000;

    // maximum cached size of memory
    private final long MAX_CACHED_MEMORY;

    // memory chunks below this threshold will be guaranteed regardless of number of cache entries
    // that especially covers all possible variations of shapeInfoDataBuffers in all possible cases
    private final long FORCED_CACHE_THRESHOLD = 64;

    public CudaCachingProvider() {
        MAX_CACHED_MEMORY = Runtime.getRuntime().maxMemory() / 2;
    }

    @Override
    public PointersPair malloc(AllocationShape shape, AllocationPoint point, AllocationStatus location) {
        long reqMemory = AllocationUtils.getRequiredMemory(shape);

        if (location == AllocationStatus.HOST  && reqMemory < MAX_SINGLE_ALLOCATION) {
            if (allocRequests.incrementAndGet() % 100000 == 0)
                printCacheStats();

            CacheHolder cache = zeroCache.get(shape);
            if (cache != null ) {
                Pointer pointer = cache.poll();
                if (pointer != null) {
                    cacheHit.incrementAndGet();

                    // since this memory chunk is going to be used now, remove it's amount from
                    cachedAmount.addAndGet(-1 * reqMemory);

                    PointersPair pair = new PointersPair();
                    pair.setDevicePointer(new CudaPointer(pointer.getNativePointer()));
                    pair.setHostPointer(new CudaPointer(pointer.getNativePointer()));

                    point.setAllocationStatus(AllocationStatus.HOST);
                    return pair;
                } else {
                    cacheMiss.incrementAndGet();
                    return super.malloc(shape, point, location);
                }
            } else {
                cacheMiss.incrementAndGet();
                return super.malloc(shape, point, location);
            }
        } else return super.malloc(shape, point, location);
    }

    @Override
    public void free(AllocationPoint point) {
        if (point.getAllocationStatus() == AllocationStatus.DEVICE) {
            super.free(point);
        } else {
            AllocationShape shape = point.getShape();
            long reqMemory = AllocationUtils.getRequiredMemory(shape);

            // we don't cache too big objects
            if (reqMemory > MAX_SINGLE_ALLOCATION || cachedAmount.get() >= MAX_CACHED_MEMORY) {
                super.free(point);
                return;
            }


            if (!zeroCache.containsKey(shape)) {
                try {
                    singleLock.acquire();
                    if (!zeroCache.containsKey(shape)) {
                        zeroCache.put(shape, new CacheHolder());
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    singleLock.release();
                }
            }

            /*
                Now we should decide if this object can be cached or not
             */
            CacheHolder cache = zeroCache.get(shape);

            // memory chunks < threshold will be cached no matter what
            if (reqMemory <= FORCED_CACHE_THRESHOLD) {
                cache.put(new Pointer(point.getHostPointer().address()));
            } else {
                long cacheEntries = cache.size();
                long cacheHeight = zeroCache.size();

                // total memory allocated within this bucket
                long cacheDepth = cacheEntries * reqMemory;

                if (cacheDepth < MAX_CACHED_MEMORY / cacheHeight) {
                    cachedAmount.addAndGet(reqMemory);
                    cache.put(new Pointer(point.getHostPointer().address()));
                } else {
                    super.free(point);
                }
            }
        }
    }

    private float getCacheHitRatio() {
        long totalHits = cacheHit.get() + cacheMiss.get();
        float cacheRatio = cacheHit.get() * 100 / (float) totalHits;
        return cacheRatio;
    }

    public void printCacheStats() {
        float cacheRatio = getCacheHitRatio();

        log.debug("Total shapes in cache: " + zeroCache.size());
        log.debug("Current hit ratio: " + cacheRatio);
    }

    private static class CacheHolder {
        private Queue<Pointer> queue = new ConcurrentLinkedQueue<>();
        private AtomicInteger counter = new AtomicInteger(0);

        public int size() {
            return counter.get();
        }

        public Pointer poll() {
            Pointer pointer = queue.poll();
            if (pointer != null)
                counter.decrementAndGet();

            return pointer;
        }

        public void put(Pointer pointer) {
            counter.incrementAndGet();
            queue.add(pointer);
        }
    }
}
