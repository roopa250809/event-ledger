package com.eventledger.gateway.api;

import com.eventledger.gateway.service.EventLedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/events")
@Validated
public class EventController {
    private final EventLedgerService eventLedgerService;

    public EventController(EventLedgerService eventLedgerService) {
        this.eventLedgerService = eventLedgerService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody EventRequest request) {
        var result = eventLedgerService.submit(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.response());
    }

    @GetMapping("/{eventId}")
    public EventResponse get(@PathVariable String eventId) {
        return eventLedgerService.get(eventId);
    }

    @GetMapping
    public List<EventResponse> list(@RequestParam("account") @NotBlank String accountId) {
        return eventLedgerService.listForAccount(accountId);
    }
}
