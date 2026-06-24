package bime.controllers;

import bime.dto.StockBalanceResponseDTO;
import bime.dto.StockMovementRequestDTO;
import bime.dto.StockMovementResponseDTO;
import bime.services.StockLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockLedgerService stockLedgerService;

    @PostMapping("/movements")
    @PreAuthorize("hasAuthority('BIME_MANAGE')")
    public Mono<StockMovementResponseDTO> recordMovement(@RequestBody StockMovementRequestDTO dto) {
        return stockLedgerService.recordMovement(dto);
    }

    @GetMapping("/movements/{id}")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Mono<StockMovementResponseDTO> getMovementById(@PathVariable UUID id) {
        return stockLedgerService.getMovementById(id);
    }

    @GetMapping("/movements")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<StockMovementResponseDTO> getMovements(
            @RequestParam(required = false) UUID variantId,
            @RequestParam(required = false) UUID locationId) {
        return stockLedgerService.getMovements(variantId, locationId);
    }

    @GetMapping("/balances")
    @PreAuthorize("hasAuthority('BIME_VIEW')")
    public Flux<StockBalanceResponseDTO> getBalances(
            @RequestParam(required = false) UUID variantId,
            @RequestParam(required = false) UUID locationId) {
        return stockLedgerService.getBalances(variantId, locationId);
    }
}
