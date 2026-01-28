package co.rcprdn.grocyreceiptscannerbe.dto;

import java.util.List;

public record ScanResult(
        String shop,
        List<ScannedItem> items
) {}