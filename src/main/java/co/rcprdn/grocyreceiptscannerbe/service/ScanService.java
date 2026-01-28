package co.rcprdn.grocyreceiptscannerbe.service;

import co.rcprdn.grocyreceiptscannerbe.dto.GrocyProduct;
import co.rcprdn.grocyreceiptscannerbe.dto.ScanResult;
import co.rcprdn.grocyreceiptscannerbe.dto.ScannedItem;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ScanService {

  private final ObjectMapper jsonMapper = new ObjectMapper();
  private static final String MAPPING_FILE = "mappings.json";
  private Map<String, String> knownMappings = new HashMap<>();

  // --- REGEX SAMMLUNG ---

  // 1. STANDARD (Für unbekannte Läden): "2 x Cola 1,99 A" oder "Cola 1,99"
  private static final Pattern GENERIC_ITEM_PATTERN = Pattern.compile("(.*?)\\s+(-?\\d+[,.]\\d{2})\\s*[AB]?\\s*$");
  private static final Pattern GENERIC_AMOUNT_START = Pattern.compile("^(\\d+)\\s*(?:x|X|\\*|Stk)\\s+(.*)");

  // 2. LIDL SPEZIAL: "Erbsen ... 0,89 x 2   1,78 A"
  // Erkennt Zeilen, die auf eine Summe enden, aber davor "Preis x Menge" haben
  // Gruppe 1: Name (Alles davor)
  // Gruppe 2: Einzelpreis (ignoriert)
  // Gruppe 3: Menge
  // Gruppe 4: Gesamtpreis (am Ende)
  private static final Pattern LIDL_ITEM_PATTERN = Pattern.compile("(.*?)\\s+(\\d+[,.]\\d{2})\\s*[xX*]\\s*(\\d+)\\s+(-?\\d+[,.]\\d{2})\\s*[AB]?\\s*$");

  // LIDL FALLBACK (Wenn keine Menge da ist): "Milch 1,09 A"
  private static final Pattern LIDL_SIMPLE_PATTERN = Pattern.compile("(.*?)\\s+(-?\\d+[,.]\\d{2})\\s*[AB]?\\s*$");


  // --- IGNORE LISTE (Gilt für alle) ---
  private static final List<String> IGNORE_LIST = List.of(
          "Summe", "MWST", "Netto", "Brutto", "Betrag", "EUR", "Total", "Ergebnis",
          "Visa", "Karte", "Kreditkarte", "zu zahlen", "Preisvorteil", "%", "Pfand",
          "Leergut", "B 19", "A 7", "Datum", "Uhrzeit", "Bon", "Filiale", "Händler", "Beleg"
  );

  // Kleines Enum für die Läden
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
      String tessPath = getTessDataPath(); // Code ausgelagert in Hilfsmethode unten

      System.out.println("Benutze Tesseract Pfad: " + tessPath);
      tesseract.setDatapath(tessPath);
      tesseract.setLanguage("deu");

      System.out.println("--- STARTE OCR ---");
      String text = tesseract.doOCR(tempFile);
      System.out.println("--- OCR FERTIG ---");

      return parseText(text, allProducts);

    } catch (Exception e) {
      e.printStackTrace();
      return new ScanResult("", List.of());
    } finally {
      if (tempFile != null && tempFile.exists()) tempFile.delete();
    }
  }

  private ScanResult parseText(String text, List<GrocyProduct> grocyProducts) {
    List<ScannedItem> items = new ArrayList<>();

    // 1. Ladenerkennung
    ShopType shopType = detectShop(text);
    System.out.println("Erkannter Laden: " + shopType);

    String[] lines = text.split("\n");

    for (String line : lines) {
      line = line.trim();
      if (line.isEmpty()) continue;

      // Hier entscheiden wir je nach Laden, wie wir parsen
      ParsedLine parsed = null;

      if (shopType == ShopType.LIDL) {
        parsed = parseLineLidl(line);
      } else {
        // Fallback für alle anderen (oder unbekannt)
        parsed = parseLineGeneric(line);
      }

      // Wenn wir nichts Sinnvolles gefunden haben -> nächste Zeile
      if (parsed == null) continue;

      // Globale Filter (Müll entfernen)
      if (isJunk(parsed.name)) continue;

      // Match & Add
      items.add(matchProduct(parsed.name, parsed.price, parsed.amount, grocyProducts));
    }
    return new ScanResult(shopType.name(), items);
  }

  // --- PARSING LOGIK JE NACH LADEN ---

  private ParsedLine parseLineLidl(String line) {
    // Versuch 1: Lidl Spezial ("Brot 0,89 x 2  1,78 A")
    Matcher m = LIDL_ITEM_PATTERN.matcher(line);
    if (m.find()) {
      String name = m.group(1).trim();

      // ÄNDERUNG: Wir nehmen jetzt Gruppe 2 (Einzelpreis: 0,89)
      // statt Gruppe 4 (Gesamtpreis: 1,78)
      String unitPriceStr = m.group(2).replace(",", ".");

      int amount = Integer.parseInt(m.group(3));

      return new ParsedLine(name, new BigDecimal(unitPriceStr), amount);
    }

    // Versuch 2: Einfache Zeile ("Milch 1,09 A") -> Hier ist Einzelpreis = Gesamtpreis
    m = LIDL_SIMPLE_PATTERN.matcher(line);
    if (m.find()) {
      String name = m.group(1).trim();
      String priceStr = m.group(2).replace(",", ".");
      return new ParsedLine(name, new BigDecimal(priceStr), 1);
    }

    return null;
  }

  private ParsedLine parseLineGeneric(String line) {
    Matcher m = GENERIC_ITEM_PATTERN.matcher(line);
    if (m.find()) {
      String rawName = m.group(1).trim();
      String priceStr = m.group(2).replace(",", ".");

      // Menge am Anfang suchen ("2 x Cola")
      int amount = 1;
      String finalName = rawName;

      Matcher amountM = GENERIC_AMOUNT_START.matcher(rawName);
      if (amountM.find()) {
        try {
          amount = Integer.parseInt(amountM.group(1));
          finalName = amountM.group(2).trim();
        } catch (Exception e) {}
      }
      return new ParsedLine(finalName, new BigDecimal(priceStr), amount);
    }
    return null;
  }

  // --- HILFSMETHODEN ---

  private ShopType detectShop(String text) {
    String upper = text.toUpperCase();
    if (upper.contains("LIDL")) return ShopType.LIDL;
    if (upper.contains("REWE")) return ShopType.REWE;
    if (upper.contains("ALDI")) return ShopType.ALDI;
    return ShopType.UNKNOWN;
  }

  private boolean isJunk(String name) {
    if (name.length() < 2) return true;
    long letterCount = name.chars().filter(Character::isLetter).count();
    long digitCount = name.chars().filter(Character::isDigit).count();
    if (digitCount > letterCount) return true; // Mehr Zahlen als Buchstaben
    return IGNORE_LIST.stream().anyMatch(name::contains);
  }

  // Kleines Hilfsobjekt um Rückgabewerte zu bündeln
  private record ParsedLine(String name, BigDecimal price, int amount) {}

  private String getTessDataPath() {
    String tessPath = System.getenv("TESSDATA_PREFIX");
    if (tessPath == null || tessPath.isEmpty()) {
      File projectTess = new File("tessdata");
      File windowsTess = new File("C:\\Program Files\\Tesseract-OCR\\tessdata");

      if (projectTess.exists() && new File(projectTess, "deu.traineddata").exists()) {
        return projectTess.getAbsolutePath();
      } else if (windowsTess.exists()) {
        return windowsTess.getAbsolutePath();
      } else {
        return "/usr/share/tesseract-ocr/tessdata";
      }
    }
    return tessPath;
  }

  private ScannedItem matchProduct(String ocrName, BigDecimal price, int amount, List<GrocyProduct> products) {
    // 1. Gedächtnis
    if (knownMappings.containsKey(ocrName)) {
      String knownId = knownMappings.get(ocrName);
      String grocyName = products.stream()
              .filter(p -> p.id().equals(knownId))
              .findFirst().map(GrocyProduct::name).orElse("ID: " + knownId);
      return new ScannedItem(ocrName, knownId, grocyName, BigDecimal.valueOf(amount), price, "Gelernt");
    }

    // 2. Fuzzy Suche
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

    return new ScannedItem(ocrName, "", "", BigDecimal.valueOf(amount), price, "Neu");
  }

  public void learnAndSave(String ocrName, String grocyId) {
    if (ocrName == null || grocyId == null) return;
    if (!grocyId.isEmpty() && !grocyId.equals("ignore")) {
      System.out.println("Lerne: " + ocrName + " -> " + grocyId);
      knownMappings.put(ocrName, grocyId);
      saveMappingsToFile();
    }
  }

  private void loadMappings() {
    File f = new File(MAPPING_FILE);
    if (f.exists()) {
      knownMappings = jsonMapper.readValue(f, new TypeReference<HashMap<String, String>>() {});
    }
  }

  private void saveMappingsToFile() {
    jsonMapper.writeValue(new File(MAPPING_FILE), knownMappings);
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