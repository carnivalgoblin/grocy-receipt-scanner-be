package co.rcprdn.grocyreceiptscannerbe.service;

import co.rcprdn.grocyreceiptscannerbe.dto.GrocyProduct;
import co.rcprdn.grocyreceiptscannerbe.dto.GrocyStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GrocyService {

  @Value("${grocy.url}")
  private String grocyUrl;

  @Value("${grocy.api.key}")
  private String grocyApiKey;

  private final RestTemplate restTemplate = new RestTemplate();
  private final ObjectMapper jsonMapper = new ObjectMapper();

  private HttpHeaders getHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("GROCY-API-KEY", grocyApiKey);
    headers.set("Content-Type", "application/json");
    return headers;
  }

  // Fetch all products
  public List<GrocyProduct> getProducts() {
    try {
      HttpEntity<String> entity = new HttpEntity<>(getHeaders());

      // Call Grocy: /objects/products
      ResponseEntity<String> response = restTemplate.exchange(
              grocyUrl + "/objects/products",
              HttpMethod.GET,
              entity,
              String.class
      );

      return jsonMapper.readValue(response.getBody(), new TypeReference<List<GrocyProduct>>() {});
    } catch (Exception e) {
      System.err.println("Error loading products: " + e.getMessage());
      return Collections.emptyList();
    }
  }

  // Find store ID by name
  public String findStoreId(String shopName) {
    if (shopName == null || shopName.isEmpty()) return null;

    try {
      HttpEntity<String> entity = new HttpEntity<>(getHeaders());

      // Call Grocy: /objects/shopping_locations
      ResponseEntity<String> response = restTemplate.exchange(
              grocyUrl + "/objects/shopping_locations",
              HttpMethod.GET,
              entity,
              String.class
      );

      List<GrocyStore> stores = jsonMapper.readValue(response.getBody(), new TypeReference<List<GrocyStore>>() {});

      // Check if name matches (case insensitive)
      return stores.stream()
              .filter(s -> s.name().equalsIgnoreCase(shopName))
              .findFirst()
              .map(GrocyStore::id)
              .orElse(null);

    } catch (Exception e) {
      System.err.println("Could not find store ID: " + e.getMessage());
      return null;
    }
  }

  // Add product to stock
  public void addProductToStock(String grocyId, double amount, double price, String shopName) {
    try {
      String storeId = findStoreId(shopName);

      // Build payload for Grocy
      Map<String, Object> payload = new HashMap<>();
      payload.put("amount", amount);
      payload.put("price", price);
      payload.put("best_before_date", LocalDate.now().plusDays(7).toString()); // Default: +7 days
      payload.put("transaction_type", "purchase");

      if (storeId != null) {
        payload.put("shopping_location_id", storeId);
      }

      HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, getHeaders());

      String url = grocyUrl + "/stock/products/" + grocyId + "/add";

      restTemplate.postForObject(url, entity, String.class);

      System.out.println("✅ Booked: Product " + grocyId + " (" + amount + "x) at " + shopName);

    } catch (Exception e) {
      System.err.println("❌ Error booking: " + e.getMessage());
      e.printStackTrace();
    }
  }
}