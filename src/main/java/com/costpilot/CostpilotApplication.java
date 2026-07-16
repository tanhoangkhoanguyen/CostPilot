package com.costpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CostpilotApplication {

	public static void main(String[] args) {
		SpringApplication.run(CostpilotApplication.class, args);
	}

}
