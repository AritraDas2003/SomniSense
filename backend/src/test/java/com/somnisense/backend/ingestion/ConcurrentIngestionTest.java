package com.somnisense.backend.ingestion;

import com.somnisense.backend.ingestion.dto.IngestReadingItem;
import com.somnisense.backend.ingestion.dto.IngestReadingsRequest;
import com.somnisense.backend.ingestion.dto.IngestionResult;
import com.somnisense.backend.ingestion.service.IngestionService;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gateway retries a batch while the first attempt is still being processed: both copies arrive at the same
 * moment. Every reading must be stored exactly once (ON CONFLICT DO NOTHING on the V4 unique index).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrentIngestionTest extends IngestionTestBase {

    private static final int SIZE = 50;

    @Autowired
    private IngestionService ingestionService;

    @RepeatedTest(5)
    void sameBatchTwiceAtOnceStoresEachReadingOnce() throws Exception {
        IngestReadingsRequest request = new IngestReadingsRequest();
        request.setDeviceCode("DEV-ING1");
        List<IngestReadingItem> items = new ArrayList<>();
        Instant t = Instant.now().minusSeconds(SIZE + 5);
        for (int i = 0; i < SIZE; i++) {
            IngestReadingItem item = new IngestReadingItem();
            item.setReadingKey("race-" + i);
            item.setMeasuredAt(t.plusSeconds(i));
            item.setSpo2(new BigDecimal("96"));
            item.setHeartRate(new BigDecimal("67"));
            items.add(item);
        }
        request.setReadings(items);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IngestionResult> a = pool.submit(() -> { start.await(); return ingestionService.ingest(request); });
            Future<IngestionResult> b = pool.submit(() -> { start.await(); return ingestionService.ingest(request); });
            start.countDown();
            IngestionResult ra = a.get(30, TimeUnit.SECONDS);
            IngestionResult rb = b.get(30, TimeUnit.SECONDS);

            assertThat(ra.getAccepted() + rb.getAccepted()).isEqualTo(SIZE);
            assertThat(ra.getDuplicates() + rb.getDuplicates()).isEqualTo(SIZE);
            assertThat(storedReadings()).isEqualTo(SIZE);
        } finally {
            pool.shutdownNow();
        }
    }
}
