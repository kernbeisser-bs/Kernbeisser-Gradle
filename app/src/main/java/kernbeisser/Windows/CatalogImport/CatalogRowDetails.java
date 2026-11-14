package kernbeisser.Windows.CatalogImport;

import java.awt.*;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.swing.*;
import kernbeisser.CustomComponents.ObjectTable.Column;
import kernbeisser.CustomComponents.ObjectTable.Columns.Columns;
import kernbeisser.CustomComponents.ObjectTable.Columns.CustomizableColumn;
import kernbeisser.CustomComponents.ObjectTable.ObjectTable;
import kernbeisser.DBEntities.Article;
import kernbeisser.DBEntities.CatalogEntry;
import kernbeisser.Tasks.Catalog.CatalogImportError;
import kernbeisser.Useful.Date;

/**
 * Shows a faulty catalog line with all its columns, the faulty ones highlighted, and whether the
 * article is in the range and was sold, so it can be decided whether the error is relevant.
 */
class CatalogRowDetails {

  private static final Color FAULTY_BACKGROUND = new Color(255, 199, 199);
  private static final Color FAULTY_FOREGROUND = new Color(140, 0, 0);

  record FieldRow(int column, String field, String rawValue, String parsedValue, boolean faulty) {}

  private CatalogRowDetails() {}

  static List<FieldRow> fieldRows(CatalogImportError error) {
    List<FieldRow> rows = new ArrayList<>();
    Field[] fields = CatalogEntry.class.getDeclaredFields();
    String[] rawValues = error.getRawValues();
    // the catalog line is mapped to the fields by position, like in CatalogImporter.parseRowCore
    for (int i = 0; i < Math.min(fields.length, rawValues.length); i++) {
      Field field = fields[i];
      rows.add(
          new FieldRow(
              i + 1,
              field.getName(),
              rawValues[i],
              readValue(field, error.getEntry()),
              error.getFaultyFields().contains(field.getName())));
    }
    return rows;
  }

  private static String readValue(Field field, CatalogEntry entry) {
    try {
      field.setAccessible(true);
      Object value = field.get(entry);
      if (value instanceof Instant instant) {
        return Date.safeDateFormat(instant, Date.INSTANT_DATE_TIME);
      }
      return value == null ? "" : value.toString();
    } catch (IllegalAccessException e) {
      return "?";
    }
  }

  static String salesDescription(Optional<Article> article, List<Instant> saleDates) {
    if (article.isEmpty()) {
      return "Nicht im Sortiment";
    }
    String range =
        "Im Sortiment: KB-Nr. " + article.get().getKbNumber() + " – " + article.get().getName();
    return saleDates.isEmpty()
        ? range + "\nNoch nie verkauft"
        : range
            + "\n"
            + saleDates.size()
            + "-mal verkauft, zuletzt am "
            + Date.safeDateFormat(
                saleDates.stream().max(Comparator.naturalOrder()).get(), Date.INSTANT_DATE);
  }

  private static CustomizableColumn<FieldRow> highlightFaulty(CustomizableColumn<FieldRow> c) {
    // the renderer component is reused for all cells, so the foreground has to be reset as well
    return c.withCellAdjustor(
        (component, row, isSelected, hasFocus, rowIndex, column) -> {
          if (isSelected) return;
          if (row.faulty()) {
            component.setBackground(FAULTY_BACKGROUND);
            component.setForeground(FAULTY_FOREGROUND);
          } else {
            component.setForeground(UIManager.getColor("Table.foreground"));
          }
        });
  }

  static JPanel buildPanel(
      CatalogImportError error,
      Optional<Article> article,
      List<Instant> saleDates,
      JComponent moreUI) {
    ObjectTable<FieldRow> table =
        new ObjectTable<>(
            fieldRows(error),
            highlightFaulty(
                Columns.<FieldRow>create("Spalte", FieldRow::column)
                    .withSorter(Column.NUMBER_SORTER)
                    .withPreferredWidth(50)),
            highlightFaulty(
                Columns.<FieldRow>create("Feld", FieldRow::field).withPreferredWidth(200)),
            highlightFaulty(
                Columns.<FieldRow>create("Wert in der Datei", FieldRow::rawValue)
                    .withPreferredWidth(300)),
            highlightFaulty(
                Columns.<FieldRow>create("Eingelesener Wert", FieldRow::parsedValue)
                    .withPreferredWidth(300)));
    JScrollPane scrollPane = new JScrollPane(table);
    scrollPane.setPreferredSize(new Dimension(900, 500));

    CatalogEntry entry = error.getEntry();
    JTextArea info =
        new JTextArea(
            entry.getBezeichnung()
                + " (Artikelnummer "
                + entry.getArtikelNr()
                + ")\n"
                + salesDescription(article, saleDates)
                + "\n\nFehlerhafte Spalten (rot markiert): "
                + String.join(", ", error.getFaultyFields()));
    info.setEditable(false);
    info.setOpaque(false);
    info.setFont(UIManager.getFont("Label.font"));

    JPanel panel = new JPanel(new BorderLayout(0, 10));
    panel.add(info, BorderLayout.NORTH);
    panel.add(scrollPane, BorderLayout.CENTER);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    buttons.add(moreUI);
    panel.add(buttons, BorderLayout.SOUTH);
    return panel;
  }

  static void show(
      Component parent,
      CatalogImportError error,
      Optional<Article> article,
      List<Instant> saleDates,
      JComponent moreUI) {
    JOptionPane.showMessageDialog(
        parent,
        buildPanel(error, article, saleDates, moreUI),
        "Katalogzeile " + error.getLineNumber(),
        JOptionPane.INFORMATION_MESSAGE);
  }
}
