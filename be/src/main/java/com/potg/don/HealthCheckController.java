package com.potg.don;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

	@GetMapping("/test")
	public String Test() {
		return "Hello World";
	}
}
