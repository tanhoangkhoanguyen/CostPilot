package com.costpilot.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.costpilot.TestcontainersConfiguration;
import com.costpilot.core.model.Usage;
import com.costpilot.domain.ModelPrice;
import com.costpilot.domain.ModelPriceRepository;
import com.costpilot.domain.UsageRecord;
import com.costpilot.domain.UsageRecordRepository;

// 2.3 acceptance: a price change creates a new version, existing ledger records and
// historical lookups never mutate, new requests use the new price.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PriceVersioningIT {

	@Autowired
	private PriceVersioningService versioning;

	@Autowired
	private PriceLookupService lookup;

	@Autowired
	private CostService costService;

	@Autowired
	private UsageLedgerService ledger;

	@Autowired
	private ModelPriceRepository priceRepository;

	@Autowired
	private UsageRecordRepository usageRepository;

	@Test
	void priceChangeCreatesNewVersionAndHistoryNeverMutates() throws Exception {
		String provider = "openai";
		String model = "gpt-versioning-test";

		// v1 goes live
		ModelPrice v1 = versioning.changePrice(provider, model,
				new BigDecimal("0.001000"), new BigDecimal("0.002000"));
		assertThat(v1.getVersion()).isEqualTo(1);

		// a request happens under v1 and lands in the ledger pinned to v1
		Instant requestTime = Instant.now();
		CostService.Priced pricedV1 = costService.pricedCostFor(provider, model, new Usage(1000, 1000), requestTime);
		assertThat(pricedV1.cost().total()).isEqualByComparingTo("0.003");
		UsageRecord recorded = ledger.record(
				new LedgerContext(null, "team-a", null, null, null, "versioning-" + UUID.randomUUID()),
				provider, model, new Usage(1000, 1000), pricedV1.cost(), pricedV1.price().getId());

		Thread.sleep(50); // ensure the version boundary is strictly after requestTime

		// price changes -> v2
		ModelPrice v2 = versioning.changePrice(provider, model,
				new BigDecimal("0.005000"), new BigDecimal("0.010000"));
		assertThat(v2.getVersion()).isEqualTo(2);

		// old version row still exists, closed, rates untouched
		ModelPrice v1Reloaded = priceRepository.findById(v1.getId()).orElseThrow();
		assertThat(v1Reloaded.isClosed()).isTrue();
		assertThat(v1Reloaded.getInputPricePer1k()).isEqualByComparingTo("0.001");

		// new requests use the new price
		assertThat(costService.costFor(provider, model, new Usage(1000, 1000), Instant.now()).total())
				.isEqualByComparingTo("0.015");

		// historical lookup at the original request time still resolves v1 - the
		// recorded cost is reproducible forever
		ModelPrice atRequestTime = lookup.priceAt(provider, model, requestTime);
		assertThat(atRequestTime.getId()).isEqualTo(v1.getId());
		assertThat(costService.costFor(provider, model, new Usage(1000, 1000), requestTime).total())
				.isEqualByComparingTo("0.003");

		// the ledger row itself is untouched by the price change
		UsageRecord reloaded = usageRepository.findById(recorded.getId()).orElseThrow();
		assertThat(reloaded.getCost()).isEqualByComparingTo("0.003");
		assertThat(reloaded.getPriceId()).isEqualTo(v1.getId());
	}

	@Test
	void seededPricesActAsVersionOne() {
		ModelPrice seeded = lookup.priceAt("openai", "gpt-4o-mini", Instant.now());
		assertThat(seeded.getVersion()).isEqualTo(1);
		assertThat(seeded.isClosed()).isFalse();
	}
}
