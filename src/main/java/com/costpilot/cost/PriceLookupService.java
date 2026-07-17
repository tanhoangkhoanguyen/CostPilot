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
	 * Price active at the given instant. The signature is effective-dated on
	 * purpose: 2.3 introduces price versioning, and callers must already ask
	 * "what did this cost at request time" rather than "what does it cost now".
	 * Until 2.3 lands there is exactly one live row per (provider, model).
	 */
	@Transactional(readOnly = true)
	public ModelPrice priceAt(String provider, String model, Instant at) {
		return repository.findByProviderAndModel(provider, model)
				.orElseThrow(() -> new PriceNotFoundException(provider, model));
	}
}
