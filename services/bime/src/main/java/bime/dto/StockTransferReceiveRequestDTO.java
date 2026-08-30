package bime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Records goods arriving at the destination for a transfer that is in transit")
public class StockTransferReceiveRequestDTO {
    @Schema(description = "Per-line received quantities. Lines omitted here are left untouched (still in transit)")
    private List<StockTransferReceiveLineDTO> lines;
    @Schema(description = "When true, after applying the quantities above, any quantity still in transit is written off as a shortage " +
            "and the transfer is marked COMPLETED. When false, the transfer stays PARTIALLY_RECEIVED until everything is accounted for")
    private boolean closeShort;
}
