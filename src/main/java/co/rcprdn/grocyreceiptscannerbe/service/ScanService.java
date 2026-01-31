package co.rcprdn.grocyreceiptscannerbe.service;

import co.rcprdn.grocyreceiptscannerbe.dto.GrocyProduct;
import co.rcprdn.grocyreceiptscannerbe.dto.ScanResult;
import co.rcprdn.grocyreceiptscannerbe.dto.ScannedItem;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ScanService {

  private final ObjectMapper jsonMapper = new ObjectMapper();
  private static final String MAPPING_FILE = "mappings.json";
  private Map<String, String> knownMappings = new HashMap<>();

  // --- REGEX COLLECTION ---

  private static final Pattern GENERIC_ITEM_PATTERN = Pattern.compile("(.*?)\\s+(-?\\d+[,.]\\d{2})\\s*[AB]?\\s*$");
  private static final Pattern GENERIC_AMOUNT_START = Pattern.compile("^(\\d+)\\s*(?:x|X|\\*|Stk)\\s+(.*)");

  private static final Pattern LIDL_ITEM_PATTERN = Pattern.compile("(.*?)\\s+(\\d+[,.]\\d{2})\\s*[xX*]\\s*(\\d+)\\s+(-?\\d+[,.]\\d{2})\\s*[AB]?\\s*$");

  private static final Pattern REWE_ONLY_QUANTITY_LINE = Pattern.compile("^(\\d+)\\s*Stk\\s*[xX]?\\s*(\\d+[,.]\\d{2})");
  private static final Pattern REWE_X01_PATTERN = Pattern.compile("(.*?)\\s*X01\\s*(-?\\d+[,.]\\d{2})");


  // --- IGNORE LIST ---
  // REMOVED "%" because it kills products like "Gouda 48%"
  private static final List<String> IGNORE_LIST = List.of(
          "Summe", "MWST", "Netto", "Brutto", "Betrag", "EUR", "Total", "Ergebnis",
          "Visa", "Karte", "Kreditkarte", "zu zahlen", "Preisvorteil", "Pfand",
          "Leergut", "B 19", "A 7", "Datum", "Uhrzeit", "Bon", "Filiale", "Händler", "Beleg",
          "Gesamtbetrag", "Geg.", "Mastercard", "Kundenbeleg", "Trace-Nr", "Terminal-ID", "UID Nr",
          "Konzessionär", "Steuer"
  );

  private enum ShopType {
    LIDL, REWE, ALDI, UNKNOWN
  }

  public ScanService() {
    loadMappings();
  }

  public ScanResult processImage(MultipartFile file, List<GrocyProduct> allProducts) {
    File tempFile = null;
    try {
      String originalName = file.getOriginalFilename();
      String ext = (originalName != null && originalName.contains("."))
              ? originalName.substring(originalName.lastIndexOf("."))
              : ".jpg";

      tempFile = File.createTempFile("upload-", ext);
      file.transferTo(tempFile);

      Tesseract tesseract = new Tesseract();
      String tessPath = getTessDataPath();

      System.out.println("Using Tesseract path: " + tessPath);
      tesseract.setDatapath(tessPath);
      tesseract.setLanguage("deu");

      System.out.println("--- START OCR ---");
      String text = tesseract.doOCR(tempFile);
      log.debug(text);
      System.out.println("--- OCR FINISHED ---");

      return parseText(text, allProducts);

    } catch (Exception e) {
      e.printStackTrace();
      return new ScanResult("Error", Collections.emptyList());
    } finally {
      if (tempFile != null && tempFile.exists()) tempFile.delete();
    }
  }

  private ScanResult parseText(String text, List<GrocyProduct> grocyProducts) {
    List<ScannedItem> items = new ArrayList<>();
    ShopType shopType = detectShop(text);
    System.out.println("Detected Shop: " + shopType);

    String[] lines = text.split("\n");
    ParsedLine pendingItem = null;

    for (String line : lines) {
      line = line.trim();
      if (line.isEmpty()) continue;

      // 1. REWE Modifier Check
      if (shopType == ShopType.REWE && pendingItem != null) {
        Matcher qtyMatcher = REWE_ONLY_QUANTITY_LINE.matcher(line);
        if (qtyMatcher.find()) {
          int amount = Integer.parseInt(qtyMatcher.group(1));
          BigDecimal unitPrice = new BigDecimal(qtyMatcher.group(2).replace(",", "."));

          System.out.println("   -> Found Modifier for '" + pendingItem.name + "': Amount " + amount + ", Price " + unitPrice);
          pendingItem = new ParsedLine(pendingItem.name, unitPrice, amount);

          addItemIfValid(pendingItem, items, grocyProducts);
          pendingItem = null;
          continue;
        }
      }

      // 2. Commit Pending Item
      if (pendingItem != null) {
        addItemIfValid(pendingItem, items, grocyProducts);
        pendingItem = null;
      }

      // 3. Parse New Line
      ParsedLine parsed = null;
      if (shopType == ShopType.LIDL) {
        parsed = parseLineLidl(line);
      } else if (shopType == ShopType.REWE) {
        parsed = parseLineRewe(line);
      } else {
        parsed = parseLineGeneric(line);
      }

      // 4. Handle Result
      if (parsed != null) {
        if (shopType == ShopType.REWE) {
          pendingItem = parsed;
        } else {
          addItemIfValid(parsed, items, grocyProducts);
        }
      }
    }

    if (pendingItem != null) {
      addItemIfValid(pendingItem, items, grocyProducts);
    }

    return new ScanResult(shopType.name(), items);
  }

  // New helper to avoid duplicate code and log rejected items
  private void addItemIfValid(ParsedLine parsed, List<ScannedItem> items, List<GrocyProduct> products) {
    if (!isJunk(parsed.name)) {
      items.add(matchProduct(parsed.name, parsed.price, parsed.amount, products));
    } else {
      System.out.println("Ignored Junk: " + parsed.name);
    }
  }

  private ParsedLine parseLineLidl(String line) {
    Matcher m = LIDL_ITEM_PATTERN.matcher(line);
    if (m.find()) {
      return new ParsedLine(m.group(1).trim(), new BigDecimal(m.group(2).replace(",", ".")), Integer.parseInt(m.group(3)));
    }
    return parseLineGeneric(line);
  }

  private ParsedLine parseLineRewe(String line) {
    Matcher m = REWE_X01_PATTERN.matcher(line);
    if (m.find()) {
      String name = m.group(1).trim();
      BigDecimal price = new BigDecimal(m.group(2).replace(",", "."));
      return new ParsedLine(name, price, 1);
    }
    return parseLineGeneric(line);
  }

  private ParsedLine parseLineGeneric(String line) {
    Matcher m = GENERIC_ITEM_PATTERN.matcher(line);
    if (m.find()) {
      String rawName = m.group(1).trim();
      BigDecimal price = new BigDecimal(m.group(2).replace(",", "."));
      int amount = 1;
      String finalName = rawName;

      Matcher amountM = GENERIC_AMOUNT_START.matcher(rawName);
      if (amountM.find()) {
        try {
          amount = Integer.parseInt(amountM.group(1));
          finalName = amountM.group(2).trim();
        } catch (Exception e) {}
      }
      return new ParsedLine(finalName, price, amount);
    }
    return null;
  }

  private ShopType detectShop(String text) {
    String upper = text.toUpperCase();
    if (upper.contains("LIDL")) return ShopType.LIDL;
    if (upper.contains("REWE")) return ShopType.REWE;
    if (upper.contains("ALDI")) return ShopType.ALDI;
    return ShopType.UNKNOWN;
  }

  private boolean isJunk(String name) {
    if (name == null || name.length() < 2) return true;
    long letterCount = name.chars().filter(Character::isLetter).count();
    long digitCount = name.chars().filter(Character::isDigit).count();
    if (digitCount > letterCount) return true;

    return IGNORE_LIST.stream().anyMatch(name::contains);
  }

  private record ParsedLine(String name, BigDecimal price, int amount) {}

  private String getTessDataPath() {
    String tessPath = System.getenv("TESSDATA_PREFIX");
    if (tessPath == null || tessPath.isEmpty()) {
      File projectTess = new File("tessdata");
      File windowsTess = new File("C:\\Program Files\\Tesseract-OCR\\tessdata");
      if (projectTess.exists() && new File(projectTess, "deu.traineddata").exists()) return projectTess.getAbsolutePath();
      else if (windowsTess.exists()) return windowsTess.getAbsolutePath();
      else return "/usr/share/tesseract-ocr/tessdata";
    }
    return tessPath;
  }

  private ScannedItem matchProduct(String ocrName, BigDecimal price, int amount, List<GrocyProduct> products) {
    if (knownMappings.containsKey(ocrName)) {
      String knownId = knownMappings.get(ocrName);
      String grocyName = products.stream()
              .filter(p -> p.id().equals(knownId))
              .findFirst().map(GrocyProduct::name).orElse("ID: " + knownId);
      return new ScannedItem(ocrName, knownId, grocyName, BigDecimal.valueOf(amount), price, "Learned");
    }

    GrocyProduct bestMatch = null;
    int lowestDistance = Integer.MAX_VALUE;
    String searchName = ocrName.toLowerCase();

    for (GrocyProduct product : products) {
      String prodName = product.name().toLowerCase();
      if (prodName.equals(searchName)) {
        lowestDistance = 0; bestMatch = product; break;
      }
      int dist = calculateLevenshteinDistance(searchName, prodName);
      if (dist < lowestDistance) {
        lowestDistance = dist; bestMatch = product;
      }
    }

    int maxAllowedEdits = Math.min(4, searchName.length() / 3);
    if (bestMatch != null && lowestDistance <= maxAllowedEdits) {
      return new ScannedItem(ocrName, bestMatch.id(), bestMatch.name(), BigDecimal.valueOf(amount), price, "Auto (" + lowestDistance + ")");
    }

    return new ScannedItem(ocrName, "", "", BigDecimal.valueOf(amount), price, "New");
  }

  public void learnAndSave(String ocrName, String grocyId) {
    if (ocrName == null || grocyId == null) return;
    if (!grocyId.isEmpty() && !grocyId.equals("ignore")) {
      System.out.println("Learning: " + ocrName + " -> " + grocyId);
      knownMappings.put(ocrName, grocyId);
      saveMappingsToFile();
    }
  }

  private void loadMappings() {
    File f = new File(MAPPING_FILE);
    if (f.exists()) {
      try {
        knownMappings = jsonMapper.readValue(f, new TypeReference<HashMap<String, String>>() {});
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  private void saveMappingsToFile() {
    try {
      jsonMapper.writeValue(new File(MAPPING_FILE), knownMappings);
    } catch (Exception e) {
      System.err.println("Could not save mappings: " + e.getMessage());
    }
  }

  private int calculateLevenshteinDistance(String x, String y) {
    int[][] dp = new int[x.length() + 1][y.length() + 1];
    for (int i = 0; i <= x.length(); i++) {
      for (int j = 0; j <= y.length(); j++) {
        if (i == 0) dp[i][j] = j;
        else if (j == 0) dp[i][j] = i;
        else dp[i][j] = min(dp[i - 1][j - 1] + (x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1), dp[i - 1][j] + 1, dp[i][j - 1] + 1);
      }
    }
    return dp[x.length()][y.length()];
  }
  private int min(int... numbers) { return Arrays.stream(numbers).min().orElse(Integer.MAX_VALUE); }
}