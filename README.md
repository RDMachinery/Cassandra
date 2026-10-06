# CSVParser

A lightweight, zero-dependency Java class for parsing, querying, filtering, and exporting CSV data. Supports custom delimiters, quoted fields, named-column access, sorting, basic statistics, and round-trip serialisation — all in a single file.

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Usage Scenarios](#usage-scenarios)
  - [Parsing a CSV String](#1-parsing-a-csv-string)
  - [Reading a CSV File](#2-reading-a-csv-file)
  - [Accessing Rows, Columns and Cells](#3-accessing-rows-columns-and-cells)
  - [Working with Headers](#4-working-with-headers)
  - [Filtering and Searching](#5-filtering-and-searching)
  - [Sorting](#6-sorting)
  - [Numeric Statistics](#7-numeric-statistics)
  - [Mutating Data](#8-mutating-data)
  - [Exporting and Writing Files](#9-exporting-and-writing-files)
  - [Custom Delimiters and Quote Characters](#10-custom-delimiters-and-quote-characters)
- [API Reference](#api-reference)
- [Error Handling](#error-handling)
- [Limitations](#limitations)
- [Licence](#licence)

---

## Features

- Parse CSV from a `String` or directly from a file path
- Configurable delimiter, quote character, header detection, and whitespace trimming
- Access data by row index, column index, or column name
- Retrieve rows as `Map<String, String>` for easy key-based access
- Filter rows by exact match or case-insensitive substring search
- Sort rows by any column, ascending or descending
- Compute column sums and averages for numeric data
- Add, remove, and update rows and cells in place
- Serialise data back to a well-formed CSV string or write it to disk
- No external dependencies — drop in a single `.java` file

---

## Requirements

- Java 15 or later (uses text blocks in the demo `main` method; the class itself requires Java 11+)

---

## Installation

Copy `CSVParser.java` into your project's source tree. No build configuration or dependency management is needed.

```
src/
└── your/package/
    └── CSVParser.java
```

If you are using a package, add the appropriate `package` declaration to the top of the file.

---

## Quick Start

```java
CSVParser parser = new CSVParser();
parser.parseFile("data.csv");

System.out.println(parser.getRowCount() + " rows loaded");
System.out.println(parser.getHeaders());

parser.filterByColumn("department", "Engineering")
      .forEach(System.out::println);
```

---

## Configuration

The class provides four constructors, allowing progressive customisation:

```java
// Defaults: comma delimiter, double-quote, header row present, trim whitespace
CSVParser parser = new CSVParser();

// Custom delimiter only
CSVParser tsv = new CSVParser('\t');

// Custom delimiter and header flag
CSVParser noHeader = new CSVParser(',', false);

// Full control
CSVParser custom = new CSVParser(';', '\'', true, false);
```

| Parameter | Type | Default | Description |
|---|---|---|---|
| `delimiter` | `char` | `,` | Field separator character |
| `quoteChar` | `char` | `"` | Character used to quote fields containing special characters |
| `hasHeader` | `boolean` | `true` | Whether the first row is treated as a header |
| `trimWhitespace` | `boolean` | `true` | Whether leading/trailing whitespace is stripped from each field |

---

## Usage Scenarios

### 1. Parsing a CSV String

Useful for CSV data received from an API response, a database query result, or an in-memory buffer.

```java
String csv = """
    id,name,city,salary
    1,Alice,London,95000
    2,Bob,Manchester,72000
    3,Carol,London,105000
    """;

CSVParser parser = new CSVParser();
parser.parse(csv);

System.out.println(parser.getRowCount()); // 3
```

---

### 2. Reading a CSV File

```java
CSVParser parser = new CSVParser();
parser.parseFile("people.csv");

// With an explicit charset
parser.parseFile("people.csv", "UTF-8");
```

The file is read fully into memory. The first line is consumed as the header row when `hasHeader` is `true` (the default).

---

### 3. Accessing Rows, Columns and Cells

**All rows:**
```java
List<List<String>> rows = parser.getRows();
```

**A specific row:**
```java
List<String> row = parser.getRow(2); // zero-based index
```

**An entire column by index or name:**
```java
List<String> cities   = parser.getColumn(3);
List<String> salaries = parser.getColumn("salary");
```

**A single cell:**
```java
String value = parser.getCell(0, "name");    // row 0, column "name"
String value = parser.getCell(0, 2);         // row 0, column index 2
```

**Dimensions:**
```java
int rows    = parser.getRowCount();
int columns = parser.getColumnCount();
```

---

### 4. Working with Headers

When `hasHeader` is `true`, each row can be retrieved as a `Map<String, String>` keyed by column name, which makes downstream code more readable and resilient to column reordering.

```java
// Single row as a map
Map<String, String> person = parser.getRowAsMap(0);
System.out.println(person.get("first_name")); // Alice
System.out.println(person.get("salary"));     // 95000

// All rows as maps
List<Map<String, String>> people = parser.getAllRowsAsMaps();
people.forEach(p -> System.out.println(p.get("email")));
```

---

### 5. Filtering and Searching

**Exact match on a named column:**
```java
// All employees in Engineering
List<List<String>> engineers = parser.filterByColumn("department", "Engineering");
```

**Case-insensitive substring search on a named column:**
```java
// Rows where job_title contains "manager" (any casing)
List<List<String>> managers = parser.searchByColumn("job_title", "manager");
```

**Full-table search across every column:**
```java
// Rows where any field contains "London"
List<List<String>> londoners = parser.search("London");
```

These methods all return a new `List` and do not modify the parser's internal state, so they are safe to chain or call multiple times.

---

### 6. Sorting

```java
// Ascending (default)
List<List<String>> byName = parser.sortByColumn("last_name");

// Descending
List<List<String>> highestPaid = parser.sortByColumn("salary", true);
highestPaid.forEach(row -> System.out.println(row));
```

Sorting is lexicographic. For numeric columns where lexicographic and numeric order may diverge (e.g. "9" > "10"), consider zero-padding values in your source data, or post-processing the sorted list.

---

### 7. Numeric Statistics

```java
double totalPayroll = parser.sumColumn("salary");
double avgRating    = parser.averageColumn("performance_rating");

System.out.printf("Total payroll : £%.0f%n", totalPayroll);
System.out.printf("Avg rating    : %.2f%n",  avgRating);
```

Non-numeric cells are silently skipped during aggregation, so mixed columns (e.g. empty strings or `"N/A"`) won't throw an exception.

---

### 8. Mutating Data

**Add a row:**
```java
parser.addRow(List.of("26", "Zara", "Ali", "zara.ali@example.com",
        "Female", "2000-01-01", "London", "UK",
        "Intern", "Engineering", "28000", "0", "3.5", "true"));
```

**Remove a row:**
```java
parser.removeRow(5); // removes the row at zero-based index 5
```

**Update a cell:**
```java
parser.setCell(0, "salary", "100000"); // promote Alice
// or by column index:
parser.setCell(0, 10, "100000");
```

**Clear everything:**
```java
parser.clear(); // wipes headers and all rows
```

---

### 9. Exporting and Writing Files

After parsing and/or modifying data, you can serialise it back to CSV.

**Get as a string:**
```java
String output = parser.toCSVString();
System.out.println(output);
```

**Write to a file:**
```java
parser.writeToFile("output.csv");
```

The exporter uses the same delimiter and quote character the parser was constructed with. Fields that contain the delimiter, the quote character, or a newline are automatically quoted and internal quote characters are escaped by doubling them — so the output is always valid CSV.

---

### 10. Custom Delimiters and Quote Characters

**Tab-separated values (TSV):**
```java
CSVParser tsv = new CSVParser('\t');
tsv.parseFile("export.tsv");
```

**Semicolon-delimited (common in European locales):**
```java
CSVParser eu = new CSVParser(';');
eu.parseFile("european_data.csv");
```

**Single-quoted fields:**
```java
CSVParser sq = new CSVParser(',', '\'', true, true);
sq.parse("id,name\n1,'Alice, B.'\n2,'Bob'");
```

---

## API Reference

### Constructors

| Signature | Description |
|---|---|
| `CSVParser()` | Defaults: `,` delimiter, `"` quote, header on, trim on |
| `CSVParser(char delimiter)` | Custom delimiter |
| `CSVParser(char delimiter, boolean hasHeader)` | Custom delimiter and header flag |
| `CSVParser(char delimiter, char quoteChar, boolean hasHeader, boolean trimWhitespace)` | Full configuration |

### Parsing

| Method | Description |
|---|---|
| `void parse(String csv)` | Parse a CSV string |
| `void parseFile(String path)` | Parse a UTF-8 file |
| `void parseFile(String path, String charset)` | Parse a file with a specific charset |
| `List<String> parseLine(String line)` | Parse a single CSV line |

### Data Access

| Method | Description |
|---|---|
| `List<List<String>> getRows()` | All data rows |
| `List<String> getRow(int index)` | Row by zero-based index |
| `int getRowCount()` | Number of data rows |
| `List<String> getHeaders()` | Header row values |
| `boolean hasHeaders()` | True if a header row was parsed |
| `List<String> getColumn(int index)` | All values in a column by index |
| `List<String> getColumn(String name)` | All values in a named column |
| `String getCell(int row, int col)` | Cell by row and column index |
| `String getCell(int row, String col)` | Cell by row index and column name |
| `Map<String,String> getRowAsMap(int index)` | Row as a header-keyed map |
| `List<Map<String,String>> getAllRowsAsMaps()` | All rows as header-keyed maps |
| `int getColumnCount()` | Number of columns |

### Filtering & Searching

| Method | Description |
|---|---|
| `List<List<String>> filterByColumn(String col, String value)` | Exact match |
| `List<List<String>> searchByColumn(String col, String substring)` | Case-insensitive substring in one column |
| `List<List<String>> search(String substring)` | Case-insensitive substring across all columns |

### Sorting

| Method | Description |
|---|---|
| `List<List<String>> sortByColumn(String col)` | Sort ascending |
| `List<List<String>> sortByColumn(String col, boolean descending)` | Sort ascending or descending |

### Statistics

| Method | Description |
|---|---|
| `double sumColumn(String col)` | Sum numeric values in a column |
| `double averageColumn(String col)` | Average numeric values in a column |

### Mutation

| Method | Description |
|---|---|
| `void addRow(List<String> row)` | Append a row |
| `void removeRow(int index)` | Remove row at index |
| `void setCell(int row, int col, String value)` | Update a cell by index |
| `void clear()` | Remove all data |

### Export

| Method | Description |
|---|---|
| `String toCSVString()` | Serialise to a CSV string |
| `void writeToFile(String path)` | Write CSV to a file (UTF-8, overwrite) |

---

## Error Handling

| Situation | Behaviour |
|---|---|
| Column name not found | `IllegalArgumentException` with a list of valid column names |
| Named-column method called without a header row | `IllegalStateException` |
| Row or column index out of bounds | Standard `IndexOutOfBoundsException` from the underlying `List` |
| File not found | `IOException` propagated to the caller |
| Non-numeric values in `sumColumn` / `averageColumn` | Silently skipped; no exception thrown |

---

## Limitations

- The entire file is loaded into memory; not suitable for very large files (> a few hundred MB) without modification.
- Sorting is lexicographic — numeric columns require pre-padded values or post-processing for correct ordering.
- Multi-line quoted fields (fields containing a literal newline character) are not supported; each physical line is treated as one record.
- No type inference — all values are stored as `String`.

---

## Licence

This project is released into the public domain. Use it freely in personal and commercial projects without restriction.
