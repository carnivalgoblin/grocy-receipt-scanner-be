package co.rcprdn.grocyreceiptscannerbe.dto;

import java.math.BigDecimal;

public record ScannedItem(
        String ocrName,
        String grocyId,
        String grocyName,
        BigDecimal amount,
        BigDecimal price,
        String matchInfo
) {
}