# Project: grocy-receipt-scanner-be

## Project Overview
`grocy-receipt-scanner-be` is a backend service developed in Java 25 using Spring Boot 4.0.2. Its primary purpose is to process shopping receipt images using OCR (Tesseract), extract product information, and integrate with a Grocy instance to manage inventory.

The application accepts image uploads, performs OCR to recognize text, parses the text to identify items and prices (with specific logic for stores like Lidl, Rewe, and Aldi), matches these items to Grocy products using fuzzy search and learned mappings, and allows booking these items into Grocy's stock.

**Key Technologies:**
*   **Language:** Java 25
*   **Framework:** Spring Boot 4.0.2
*   **Build Tool:** Maven Wrapper (`mvnw`)
*   **OCR:** Tesseract (via `tess4j`)
*   **JSON Processing:** Jackson (`tools.jackson`)
*   **Other Libraries:** Lombok, java-string-similarity

## Building and Running

### Prerequisites
*   **Java 25**: The project is configured for Java 25.
*   **Tesseract OCR**: Must be installed on the host system.
    *   The application looks for tessdata in `TESSDATA_PREFIX` env var, local `tessdata` folder, or standard system paths (e.g., `C:\Program Files\Tesseract-OCR\tessdata` on Windows).
*   **Grocy Instance**: A running instance of Grocy is required for the application to function fully.

### Key Commands
Use the provided Maven Wrapper for consistent build environments.

*   **Build the project:**
    ```bash
    ./mvnw clean install
    ```
*   **Run the application:**
    ```bash
    ./mvnw spring-boot:run
    ```
*   **Run tests:**
    ```bash
    ./mvnw test
    ```

### Configuration
Configuration is managed in `src/main/resources/application.properties`.
Required properties:
*   `grocy.url`: Base URL for the Grocy API (e.g., `http://192.168.x.x:9283/api`).
*   `grocy.api.key`: Valid API key for the Grocy instance.

## Development Conventions

*   **Architecture:** Standard Spring Boot Layered Architecture:
    *   **Controller:** `co.rcprdn.grocyreceiptscannerbe.controller` (REST endpoints).
    *   **Service:** `co.rcprdn.grocyreceiptscannerbe.service` (Business logic, OCR, Grocy API calls).
    *   **DTO:** `co.rcprdn.grocyreceiptscannerbe.dto` (Data structures).
*   **Data Structures:** Java `record` types are preferred for DTOs (`GrocyProduct`, `ScannedItem`, etc.).
*   **Mapping logic:**
    *   Store mappings are saved in `mappings.json`.
    *   Fuzzy matching is used for identifying products with a Levenshtein distance fallback.
    *   Shop-specific regex patterns are defined in `ScanService.java`.
*   **Dependencies:** Note the usage of `tools.jackson` packages instead of the older `com.fasterxml.jackson`.
*   **Language:** Code is in English, but some comments and regex patterns handle German receipt formats (e.g., "Summe", "Stk").

## AI Assistance & Tool Usage
To ensure efficient development, utilize the following MCP tools meaningfully:
- **Serena Toolset:** Use for deep codebase exploration, symbolic analysis, and refactoring. Prefer symbolic tools (like `find_symbol` or `replace_symbol_body`) over full file reads for better context management and precision.
- **Context7:** Use this to retrieve the latest documentation and code examples for Angular, Angular Material, or any other library (e.g., RxJS). Always check documentation via `query-docs` before implementing complex framework features to ensure adherence to current best practices.
