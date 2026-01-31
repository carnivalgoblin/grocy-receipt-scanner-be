package co.rcprdn.grocyreceiptscannerbe.controller;

import co.rcprdn.grocyreceiptscannerbe.dto.GrocyProduct;
import co.rcprdn.grocyreceiptscannerbe.dto.ScannedItem;
import co.rcprdn.grocyreceiptscannerbe.dto.ScanResult;
import co.rcprdn.grocyreceiptscannerbe.service.GrocyService;
import co.rcprdn.grocyreceiptscannerbe.service.ScanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/scan")
public class ScanController {

  private final ScanService scanService;
  private final GrocyService grocyService;

  public ScanController(ScanService scanService, GrocyService grocyService) {
    this.scanService = scanService;
    this.grocyService = grocyService;
  }

  @GetMapping("/products")
  public ResponseEntity<List<GrocyProduct>> getProducts() {
    try {
      return ResponseEntity.ok(grocyService.getProducts());
    } catch (Exception e) {
      e.printStackTrace();
      return ResponseEntity.internalServerError().build();
    }
  }

  @PostMapping(value = "/upload", consumes = "multipart/form-data")
  public ResponseEntity<ScanResult> upload(@RequestParam("file") MultipartFile file) {
    try {
      List<GrocyProduct> products = grocyService.getProducts();
      return ResponseEntity.ok(scanService.processImage(file, products));
    } catch (Exception e) {
      e.printStackTrace();
      return ResponseEntity.internalServerError().build();
    }
  }

  @PostMapping("/book")
  public ResponseEntity<String> bookItems(@RequestBody BookingRequest request) {
    try {
      System.out.println("--- Starting booking for: " + request.shop + " ---");

      for (ScannedItem item : request.items) {
        if (item.grocyId() != null && !item.grocyId().isEmpty() && !item.grocyId().equals("ignore")) {

          grocyService.addProductToStock(
                  item.grocyId(),
                  item.amount().doubleValue(),
                  item.price().doubleValue(),
                  request.shop
          );

          scanService.learnAndSave(item.ocrName(), item.grocyId());
        }
      }
      return ResponseEntity.ok("Successfully booked");
    } catch (Exception e) {
      e.printStackTrace();
      return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
    }
  }

  static class BookingRequest {
    public String shop;
    public List<ScannedItem> items;
  }
}