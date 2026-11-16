package kernbeisser.Tasks.Catalog;

import static org.junit.jupiter.api.Assertions.*;

import kernbeisser.Enums.MetricUnits;
import kernbeisser.Exeptions.InvalidValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CatalogImporterTest {

  @ParameterizedTest
  @CsvSource({
    "1 kg, KILOGRAM",
    "1 l, LITER",
    "100 g, GRAM",
    "100 ml, MILLILITER",
    "kg, KILOGRAM",
    "l, LITER",
    "1kg, KILOGRAM",
    "'1 KG', KILOGRAM"
  })
  void parseUnitAcceptsBnnBasePriceUnits(String value, MetricUnits expected) throws InvalidValue {
    assertEquals(expected, CatalogImporter.parseUnit(value));
  }

  @Test
  void parseUnitReturnsNullForEmptyValue() throws InvalidValue {
    assertNull(CatalogImporter.parseUnit(""));
  }

  @ParameterizedTest
  @CsvSource({"1 Stück", "1 kg extra", "xyz"})
  void parseUnitRejectsUnknownUnits(String value) {
    assertThrows(InvalidValue.class, () -> CatalogImporter.parseUnit(value));
  }
}
