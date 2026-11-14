package kernbeisser.Tasks.Catalog;

import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kernbeisser.DBEntities.CatalogEntry;
import kernbeisser.Enums.MetricUnits;
import kernbeisser.Enums.VAT;
import kernbeisser.Exeptions.CatalogImportMissingDepositException;
import kernbeisser.Exeptions.InvalidValue;
import kernbeisser.Exeptions.UnknownFileFormatException;
import kernbeisser.Exeptions.handler.UnexpectedExceptionHandler;
import kernbeisser.Useful.Date;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class CatalogImporter {

  @Getter private String address;
  @Getter private String scope; // V - complete list, T - partial list, S - special list
  @Getter private String description;
  @Getter private Instant validFrom;
  @Getter private Instant validTo;
  @Getter private Instant createdDate;
  @Getter private Instant createdTime;
  @Getter private String infoLine;
  private int fileSeqNo;

  public static final String DELIMITER = ";";
  @Getter private final List<CatalogImportError> readErrors = new ArrayList<>();
  @Getter private final List<CatalogEntry> catalog = new ArrayList<>();

  public String getScopeDescription() {
    switch (scope) {
      case "V":
        return "vollständige Liste";
      case "T":
        return "Teilliste";
      case "S":
        return "Sonderliste";
      default:
        return "unbekannt";
    }
  }

  private static Instant parseInstant(Field field, Instant date, String s)
      throws DateTimeParseException {
    if (field.getName().equals("aenderungsZeit")) {
      return Date.parseInstantTime(s, date, Date.INSTANT_CATALOG_TIME);
    }
    return Date.parseInstantDate(s, Date.INSTANT_CATALOG_DATE);
  }

  // BNN base price units are written with a reference amount, e.g. "1 kg", "100 g" or "1 l"
  private static final Pattern BASE_PRICE_UNIT =
      Pattern.compile("(?:\\d+(?:[.,]\\d+)?\\s*)?(\\S+)");

  static MetricUnits parseUnit(String s) throws InvalidValue {
    if (s.isBlank()) {
      return null;
    }
    Matcher matcher = BASE_PRICE_UNIT.matcher(s.trim());
    if (!matcher.matches()) {
      throw new InvalidValue("falsch codierte Einheit: " + s);
    }
    return switch (matcher.group(1).toLowerCase()) {
      case "kg" -> MetricUnits.KILOGRAM;
      case "g" -> MetricUnits.GRAM;
      case "l" -> MetricUnits.LITER;
      case "ml" -> MetricUnits.MILLILITER;
      default -> throw new InvalidValue("falsch codierte Einheit: " + s);
    };
  }

  private static VAT parseVAT(String s) throws InvalidValue {
    switch (s) {
      case "1":
        return VAT.LOW;
      case "2":
        return VAT.HIGH;
      case "3":
        throw new InvalidValue("Pauschale MWSt Sätze werden zurzeit nicht unterstützt!");
      default:
        throw new InvalidValue("falsch codierte Mehrwertsteuer");
    }
  }

  private static Boolean parseBoolean(String s, Field f) throws InvalidValue {
    String trueValue = "true";
    String falseValue = "false";
    BoolValues boolValues = f.getAnnotation(BoolValues.class);
    if (boolValues != null) {
      trueValue = boolValues.trueValue();
      falseValue = boolValues.falseValue();
    }
    if (trueValue.equals(s)) return true;
    if (falseValue.equals(s)) return false;
    return null;
  }

  private static void parseField(CatalogEntry out, Field declaredField, String part)
      throws NumberFormatException, IllegalAccessException, DateTimeParseException, InvalidValue {
    declaredField.setAccessible(true);
    Class<?> type = declaredField.getType();
    if (type.equals(String.class)) {
      declaredField.set(out, part);
      if (declaredField.getName().equals("aenderungskennung") && !"A;X;N;R;V;W".contains(part)) {
        throw new InvalidValue("ungültige Änderungskennung: " + part);
      }
    } else if (type.equals(VAT.class)) {
      declaredField.set(out, parseVAT(part));
    } else if (type.equals(Boolean.class))
      declaredField.set(out, parseBoolean(part, declaredField));
    else {
      if (!part.replace(" ", "").equals("")) {
        if (type.equals(Double.class))
          declaredField.set(
              out, tryParse(part.replace(",", ".").replace(" ", ""), Double::parseDouble));
        else if (type.equals(Integer.class))
          declaredField.set(out, tryParse(part.replace(" ", ""), Integer::parseInt));
        else if (type.equals(Long.class))
          declaredField.set(out, tryParse(part.replace(" ", ""), Long::parseLong));
        else if (type.equals(Instant.class))
          declaredField.set(
              out, tryParse(part, e -> parseInstant(declaredField, out.getAenderungsDatum(), e)));
        else if (type.equals(MetricUnits.class)) declaredField.set(out, parseUnit(part));
      }
    }
  }

  public static CatalogEntry parseRowCore(
      String[] parts, BiConsumer<Exception, String[]> FormatExceptionHandler) {
    CatalogEntry out = new CatalogEntry();
    Field[] declaredFields = CatalogEntry.class.getDeclaredFields();
    for (int i = 0; i < Math.min(declaredFields.length, parts.length); i++) {
      try {
        parseField(out, declaredFields[i], parts[i].trim());
      } catch (NumberFormatException
          | IllegalAccessException
          | DateTimeParseException
          | InvalidValue e) {
        FormatExceptionHandler.accept(e, new String[] {parts[i], declaredFields[i].getName()});
      } catch (ArrayIndexOutOfBoundsException e) {
        return out;
      }
    }
    return out;
  }

  public static CatalogEntry parseRowWithLog(String[] parts, List<Exception> errorLog) {
    return parseRowCore(parts, (e, value_field) -> errorLog.add(fieldError(e, value_field)));
  }

  private static Exception fieldError(Exception cause, String[] valueField) {
    return new Exception(
        "Fehler beim Schreiben des Wertes \"" + valueField[0] + "\" in das Feld " + valueField[1],
        cause);
  }

  private static Exception rowError(List<Exception> fieldErrors) {
    if (fieldErrors.size() == 1) {
      return fieldErrors.get(0);
    }
    Exception rowError =
        new Exception(
            fieldErrors.stream().map(Exception::getMessage).collect(Collectors.joining("; ")));
    fieldErrors.forEach(rowError::addSuppressed);
    return rowError;
  }

  public static CatalogEntry parseRow(String[] parts) {
    return parseRowCore(
        parts,
        (e, value_field) -> {
          log.error(
              "Catalog error: cannot parse value \""
                  + value_field[0]
                  + "\" into field "
                  + value_field[1],
              e);
          UnexpectedExceptionHandler.showUnexpectedErrorWarning(e);
        });
  }

  private static <T> T tryParse(String in, Function<String, T> function)
      throws NumberFormatException, DateTimeParseException {
    return function.apply(in);
  }

  private Instant tryParseHeaderDate(String dateString) {
    try {
      return tryParse(dateString, s -> Date.parseInstantDate(s, Date.INSTANT_CATALOG_DATE));
    } catch (DateTimeParseException e) {
      readErrors.add(new CatalogImportError("0", e));
    }
    return null;
  }

  public void parseHeader(String[] parts) throws UnknownFileFormatException {
    DateTimeFormatter dateFormatter = Date.INSTANT_CATALOG_DATE;
    DateTimeFormatter timeFormatter = Date.INSTANT_CATALOG_TIME;
    String fileFormat = parts[0];
    String formatVersion = parts[1];
    if (!(fileFormat.equals("BNN") && formatVersion.equals("3"))) {
      throw new UnknownFileFormatException(
          "Unbekanntes Format oder Version: " + fileFormat + " V. " + formatVersion);
    }
    String encoding = parts[2];
    switch (encoding) {
      case "0":
        Charset charset = Charset.forName("CP850");
        break;
      case "1":
        charset = Charset.forName("CP1252");
        break;
      default:
        throw new UnknownFileFormatException("Unbenkannte Kodierung: " + encoding);
    }
    address = parts[3];
    scope = parts[4];
    description = parts[5];
    String currency = parts[6];
    if (!currency.equals("EUR")) {
      throw new UnknownFileFormatException("Falsche Währung: " + currency);
    }
    validFrom = tryParseHeaderDate(parts[7]);
    validTo = tryParseHeaderDate(parts[8]);
    createdDate = tryParseHeaderDate(parts[9]);
    createdTime = Date.parseInstantTime(parts[10], createdDate, timeFormatter);
    fileSeqNo = Integer.parseInt(parts[11]);
  }

  public CatalogImporter(Path bnnFile) {
    try {
      List<String> catalogSource = Files.readAllLines(bnnFile, Catalog.DEFAULT_ENCODING);
      infoLine = catalogSource.get(0);
      parseHeader(infoLine.split(DELIMITER));
      int followUpSeqNo =
          Integer.parseInt(catalogSource.get(catalogSource.size() - 1).split(DELIMITER)[2]);
      if (fileSeqNo != 1 || followUpSeqNo != 99) {
        throw new UnknownFileFormatException("Kann keine mehrteiligen Katalogdateien verarbeiten!");
      }
      Map<String, Double> depositEntries = new HashMap<>();
      for (int i = 1; i < catalogSource.size() - 1; i++) {
        String[] parts = catalogSource.get(i).split(DELIMITER);
        List<Exception> rowLog = new ArrayList<>();
        List<String> faultyFields = new ArrayList<>();
        CatalogEntry catalogEntry =
            parseRowCore(
                parts,
                (e, valueField) -> {
                  rowLog.add(fieldError(e, valueField));
                  faultyFields.add(valueField[1]);
                });
        if (validTo != null) {
          catalogEntry.setKatalogGueltigBis(validTo);
        }
        catalog.add(catalogEntry);
        String singleDepositNo = catalogEntry.getPfandNrLadeneinheit();
        if (!singleDepositNo.trim().isEmpty()) {
          depositEntries.put(singleDepositNo, 0.0);
        }
        String containerDepositNo = catalogEntry.getPfandNrBestelleinheit();
        if (!containerDepositNo.trim().isEmpty()) {
          depositEntries.put(containerDepositNo, 0.0);
        }
        if (!rowLog.isEmpty()) {
          // one error per line, the details dialog marks all faulty columns of the line
          readErrors.add(
              new CatalogImportError(
                  Integer.toString(i), rowError(rowLog), catalogEntry, parts, faultyFields));
        }
      }
      for (String depositKKNumber : depositEntries.keySet()) {
        List<CatalogEntry> depositArticle = new ArrayList<>();
        boolean found = false;
        for (CatalogEntry catalogRow : catalog) {
          if (catalogRow.getArtikelNr().equals(depositKKNumber)) {
            found = true;
            depositArticle.add(catalogRow);
            break;
          }
        }
        if (!found) {
          depositArticle = CatalogEntry.getByArticleNo(depositKKNumber);
        }
        if (depositArticle.size() > 0) {
          depositEntries.put(depositKKNumber, depositArticle.get(0).getPreis());
        } else {
          readErrors.add(
              new CatalogImportError(
                  "-1",
                  new CatalogImportMissingDepositException(
                      "Der Pfandartikel "
                          + depositKKNumber
                          + " ist nicht im Katalog enthalten. Es kann kein Pfand berechnet werden für Artikel, die diesen Pfandartikel referenzieren!",
                      depositKKNumber)));
        }
      }
      for (CatalogEntry catalogRow : catalog) {
        if (!catalogRow.getPfandNrLadeneinheit().trim().isEmpty()) {
          catalogRow.setEinzelPfand(depositEntries.get(catalogRow.getPfandNrLadeneinheit()));
        }
        if (!catalogRow.getPfandNrBestelleinheit().trim().isEmpty()) {
          catalogRow.setGebindePfand(depositEntries.get(catalogRow.getPfandNrBestelleinheit()));
        }
      }

    } catch (Exception e) {
      UnexpectedExceptionHandler.showUnexpectedErrorWarning(e);
    }
  }
}
