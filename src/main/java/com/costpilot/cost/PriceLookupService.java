package com.costpilot.cost;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.costpilot.domain.ModelPrice;
import com.costpilot.domain.ModelPriceRepository;

@Service
public class PriceLookupService {

	private final ModelPriceRepository repository;

	public PriceLookupService(ModelPriceRepository repository) {
		this.repository = repository;
	}

	/**
	 * Price version active at the given instant - "what did this cost at request
	 * time", never "what does it cost now". Historical instants resolve to the
	 * version that was live back then, so old costs are reproducible forever.
	 */
	@Transactional(readOnly = true)
	public ModelPrice priceAt(String provider, String model, Instant at) {
		return repository.findActiveAt(provider, model, at)
				.orElseThrow(() -> new PriceNotFoundException(provider, model));
	}
}
