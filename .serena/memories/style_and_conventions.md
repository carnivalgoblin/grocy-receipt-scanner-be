# Style and Conventions

- **Framework:** Spring Boot (Standard conventions).
- **Language Features:** Uses Java 25. Prefers `record` for DTOs and simple data containers.
- **Naming:** CamelCase for classes and methods, camelCase for variables.
- **Architecture:** Controller-Service pattern.
- **Comments:** Mix of English and German comments. German is often used for descriptive notes or internal "todo-like" markers.
- **Dependencies:** Uses `tools.jackson` instead of the traditional `com.fasterxml.jackson` (likely a newer major version or variant).
- **Error Handling:** Currently uses basic `try-catch` with `printStackTrace()`.
