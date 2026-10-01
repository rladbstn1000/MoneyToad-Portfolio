package com.potg.don.auth.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("demo")
public class DemoReadinessController {
    private final DemoReadinessService readiness;

    public DemoReadinessController(DemoReadinessService readiness) { this.readiness = readiness; }

    @GetMapping(value = "/auth/demo/ready", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<State> ready() {
        boolean ready = readiness.ready();
        return ResponseEntity.status(ready ? 200 : 503).cacheControl(CacheControl.noStore()).body(new State(ready));
    }

    public record State(boolean ready) { }
}
