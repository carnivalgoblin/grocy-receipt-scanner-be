# grocy-receipt-scanner-be

## Purpose
A backend service that processes images of shopping receipts (OCR), matches identified items with products in a Grocy instance, and allows booking those items into Grocy's stock.

## Tech Stack
- **Language:** Java 25
- **Framework:** Spring Boot 4.0.2
- **Build Tool:** Maven (mvnw)
- **OCR:** Tesseract (tess4j)
- **JSON:** Jackson (using `tools.jackson` packages)
- **Other:** Lombok, java-string-similarity

## Codebase Structure
- `co.rcprdn.grocyreceiptscannerbe`
  - `GrocyReceiptScannerBeApplication`: Entry point.
  - `controller.ScanController`: REST API endpoints.
  - `service.ScanService`: OCR processing, shop detection, and item matching.
  - `service.GrocyService`: Integration with Grocy API.
  - `dto/`: Record-based DTOs for products, stores, and scanned items.
- `mappings.json`: Stores learned mappings between OCR names and Grocy IDs.
- `src/main/resources/application.properties`: Configuration for Grocy URL and API key.

## Key Features
- **OCR Scanning:** Uses Tesseract to extract text from images.
- **Shop Detection:** Specialized parsing for LIDL, REWE, and ALDI.
- **Product Matching:** Combines fuzzy search (Levenshtein) and learned mappings.
- **Grocy Integration:** Fetches products and locations; adds items to stock.
