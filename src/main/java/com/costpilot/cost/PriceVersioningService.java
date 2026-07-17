package com.costpilot.cost;

import java.math.BigDecimal;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.costpilot.domain.ModelPrice;
import com.costpilot.domain.ModelPriceRepository;

// Prices change without redeploy and without rewriting history: the live version
// is closed (effective_to = now) and a new version row starts at the same instant.
@Service
public class PriceVersioningService {

	private static final Logger log = LoggerFactory.getLogger(PriceVersioningService.class);

	private final ModelPriceRepository repository;

	public PriceVersioningService(ModelPriceRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public ModelPrice changePrice(String provider, String model,
			BigDecimal inputPricePer1k, BigDecimal outputPricePer1k) {
		Instant now = Instant.now();
		int nextVersion = repository.findActiveAt(provider, model, now)
				.map(current -> {
					current.closeAt(now);
					return current.getVersion() + 1;
				})
				.orElse(1);
		ModelPrice next = new ModelPrice(provider, model, inputPricePer1k, outputPricePer1k)
				.asVersion(nextVersion, now);
		ModelPrice saved = repository.save(next);
		log.info("price change provider={} model={} version={} in={} out={}",
				provider, model, nextVersion, inputPricePer1k.toPlainString(), outputPricePer1k.toPlainString());
		return saved;
	}
}
