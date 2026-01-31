# Suggested Commands

## Building and Running
- Build the project: `./mvnw clean install`
- Run the application: `./mvnw spring-boot:run`
- Run tests: `./mvnw test`

## Environment Setup
- The application requires Tesseract OCR installed on the system.
- `TESSDATA_PREFIX` environment variable should point to the tessdata directory, or it should be in `C:\Program Files\Tesseract-OCR\tessdata` (Windows) or `tessdata` in the project root.

## Development Tools
- Lombok is used, so ensure your IDE supports it.
- Uses Java 25 features (like records and potentially new language features).
