package com.costpilot.cost;

public class PriceNotFoundException extends RuntimeException {

	public PriceNotFoundException(String provider, String model) {
		super("no price configured for provider=" + provider + " model=" + model);
	}
}
