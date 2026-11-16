package kernbeisser.Tasks.Catalog;

import java.util.List;
import kernbeisser.DBEntities.CatalogEntry;
import lombok.Getter;

public class CatalogImportError {
  @Getter final String lineNumber;
  @Getter final Exception e;

  /** The catalog entry the error belongs to, {@code null} if it does not belong to a row. */
  @Getter final CatalogEntry entry;

  /** The raw columns of the catalog line, {@code null} if not available. */
  @Getter final String[] rawValues;

  /** Names of the {@link CatalogEntry} fields that could not be read. */
  @Getter final List<String> faultyFields;

  public CatalogImportError(String lineNumber, Exception e) {
    this(lineNumber, e, null, null, List.of());
  }

  public CatalogImportError(
      String lineNumber,
      Exception e,
      CatalogEntry entry,
      String[] rawValues,
      List<String> faultyFields) {
    this.lineNumber = lineNumber;
    this.e = e;
    this.entry = entry;
    this.rawValues = rawValues;
    this.faultyFields = faultyFields;
  }

  public boolean hasRowDetails() {
    return entry != null;
  }
}
