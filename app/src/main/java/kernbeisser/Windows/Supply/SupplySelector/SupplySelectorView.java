package kernbeisser.Windows.Supply.SupplySelector;

import static kernbeisser.Windows.Supply.SupplySelector.ArticleChange.*;
import static kernbeisser.Windows.Supply.SupplyView.roundIfNecessary;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import java.awt.*;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;
import javax.swing.*;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import kernbeisser.CustomComponents.ComboBox.AdvancedComboBox;
import kernbeisser.CustomComponents.ObjectTable.Column;
import kernbeisser.CustomComponents.ObjectTable.Columns.Columns;
import kernbeisser.CustomComponents.ObjectTable.ObjectTable;
import kernbeisser.Tasks.ArticleComparedToCatalogEntry;
import kernbeisser.Useful.Date;
import kernbeisser.Useful.Icons;
import kernbeisser.Useful.Tools;
import kernbeisser.Windows.MVC.ComponentController.ComponentController;
import kernbeisser.Windows.MVC.IView;
import kernbeisser.Windows.MVC.Linked;
import org.jetbrains.annotations.NotNull;

public class SupplySelectorView implements IView<SupplySelectorController> {

  private JPanel main;
  private ObjectTable<Supply> supplySelector;
  private JButton deleteSupply;
  private JButton export;
  private ObjectTable<LineContent> lineContents;
  private AdvancedComboBox<ResolveStatus> filter;
  private JButton printProduce;
  private JProgressBar progressBar1;
  private JPanel loadingIndicator;
  private JButton viewOrders;
  private JButton openArticle;
  private JButton mergeArticle;
  @Linked private SupplySelectorController controller;

  @Override
  public void initialize(SupplySelectorController controller) {
    supplySelector.addSelectionListener(e -> lineContents.setObjects(e.getAllLineContents()));
    filter.getRenderer().setIconFunction(this::getIcon);
    filter.addActionListener(e -> controller.applyFilter(filter.getSelected().orElse(null)));
    filter.getModel().setAllowNullSelection(true);
    filter.getRenderer().setNoSelectionIcon(noFilterIcon);
    filter.getRenderer().setNoSelectionText("Alle Artikel");
    deleteSupply.setIcon(createIcon(FontAwesome.TRASH, new Color(0x5D1E01)));
    deleteSupply.addActionListener(controller::deleteCurrentSupply);
    export.setIcon(createIcon(FontAwesome.DOWNLOAD, new Color(0xDC7E00)));
    export.addActionListener(e -> controller.exportShoppingItems());
    printProduce.setIcon(produceIcon);
    printProduce.addActionListener(e -> controller.printProduce());
    viewOrders.setIcon(createIcon(FontAwesome.INFO_CIRCLE, new Color(0x3F2964)));
    viewOrders.addActionListener(controller::viewOrders);
    openArticle.setIcon(createIcon(FontAwesome.EYE, new Color(0x1A3186)));
    openArticle.addActionListener(e -> editArticle());
    mergeArticle.setIcon(createIcon(FontAwesome.FLOPPY_O, new Color(0x850010)));
    mergeArticle.addActionListener(e -> mergeArticle());
  }

  public void setLineContentFilter(Predicate<LineContent> filter) {
    lineContents.setRowFilter(filter::test);
  }

  public void setFilterOptions(Collection<ResolveStatus> filters) {
    this.filter.setItems(filters);
    filter.setSelectedItem(null);
  }

  @Override
  public @NotNull JComponent getContent() {
    return main;
  }

  public void setSupplies(Collection<Supply> supplies) {
    supplySelector.setObjects(supplies);
  }

  private void createUIComponents() {
    supplySelector =
        new ObjectTable<Supply>(
            Columns.<Supply>create(
                    "Datum",
                    e -> Date.INSTANT_DATE_TIME.format(e.getDeliveryDate()),
                    SwingConstants.LEFT)
                .withSorter(Column.DATE_SORTER(Date.INSTANT_DATE_TIME)),
            Columns.create("Anzahl Artikel", Supply::getArticleCount)
                .withRightClickConsumer(this::listSupplyFiles),
            Columns.create("Summe", e -> String.format("%.2f€", e.getContentSum())),
            Columns.create("Importiert", Supply::getImportState));
    supplySelector.setSortKeys(
        new RowSorter.SortKey(3, SortOrder.DESCENDING),
        new RowSorter.SortKey(0, SortOrder.ASCENDING));

    lineContents =
        new ObjectTable<>(
            Columns.<LineContent>createIconColumn("S", e -> getIcon(e.getStatus()))
                .withColumnAdjustor(
                    column -> column.setMaxWidth(Tools.scaleWithLabelScalingFactor(20))),
            Columns.<LineContent>create("Anz.", e -> roundIfNecessary(e.getContainerMultiplier()))
                .withSorter(Column.NUMBER_SORTER)
                .withPreferredWidth(50),
            Columns.<LineContent>create("VB", e -> roundIfNecessary(e.getUserPreorderCount()))
                .withSorter(Column.NUMBER_SORTER)
                .withPreferredWidth(40),
            Columns.create("Artikelnr.", LineContent::getKkNumber)
                .withSorter(Column.NUMBER_SORTER)
                .withPreferredWidth(80),
            Columns.create("Artikelname", LineContent::getName).withPreferredWidth(250),
            Columns.create("Hersteller", LineContent::getProducer).withPreferredWidth(100),
            Columns.create("E.preis", SupplySelectorController::formatDisplayPrice)
                .withSorter(Column.NUMBER_SORTER)
                .withPreferredWidth(100),
            Columns.<LineContent>create("Geb.", e -> Math.round(e.getContainerSize()))
                .withSorter(Column.NUMBER_SORTER)
                .withPreferredWidth(40),
            Columns.<LineContent>create("Packung", LineContent::getContainerUnit)
                .withPreferredWidth(100)
                .withSorter(Column.NUMBER_SORTER),
            Columns.create("Kommentar", LineContent::getMessage).withPreferredWidth(100),
            Columns.create("Preisliste", LineContent::getEstimatedPriceList)
                .withPreferredWidth(150),
            Columns.<LineContent>create(
                    "Aufschlaggr.", e -> e.getEstimatedSurchargeGroup().getNameWithSurcharge())
                .withPreferredWidth(150),
            Columns.<LineContent>create("Preis", e -> String.format("%.2f€", e.getTotalPrice()))
                .withSorter(Column.NUMBER_SORTER),
            Columns.create("Diff.", LineContent::getPriceDifference)
                .withSorter(Column.NUMBER_SORTER)
                .withPreferredWidth(40),
            Columns.<LineContent>create(
                    "Pfand",
                    e ->
                        String.format(
                            "%.2f€ / %.2f€", e.getSingleDeposit(), e.getContainerDeposit()))
                .withPreferredWidth(70),
            Columns.<LineContent>create("Ausw.", e -> e.isWeighableKb() ? "ja" : "nein")
                .withPreferredWidth(50)
                .withLeftClickConsumer(this::toggleWeighable),
            Columns.<LineContent>create(
                    "Warnhinweis", e -> getWarningMessage(e.getComparedToCatalog()))
                .withPreferredWidth(150),
            Columns.<LineContent>createIconColumn(
                    "OK", e -> e.isVerified() ? verified : notVerified)
                .withPreferredWidth(30)
                .withLeftClickConsumer(this::verifyLine));
  }

  private Object getWarningMessage(ArticleComparedToCatalogEntry compared) {
    if (compared == null) {
      return "";
    }
    String warning;
    switch (compared.getResultType()) {
      case BARCODE_CHANGED:
        warning = "geänderter Barcode!";
        break;
      case BARCODE_CONFLICT_SAME_SUPPLIER:
      case BARCODE_CONFLICT_OTHER_SUPPLIER:
        warning =
            String.format(
                "Barcode vorhanden (%s)!",
                compared.getConflictingArticle().getSupplier().getShortName());
        break;
      case NO_CATALOG_ENTRY:
        warning = "Barcode fehlt!";
        break;
      default:
        warning = "";
    }
    return warning;
  }

  private void toggleWeighable(LineContent lineContent) {
    controller.toggleWeighable(lineContent);
    try {
      lineContents.getModel().fireTableDataChanged();
    } catch (IllegalArgumentException ignored) {
    }
  }

  public void refreshTable() {
    int selectedRow = lineContents.getEditingRow();
    try {
      lineContents.getModel().fireTableDataChanged();
    } catch (IllegalArgumentException ignored) {
    }
    if (selectedRow > -1) {
      lineContents.selectRow(selectedRow);
    }
    lineContents.getSelectionModel().setSelectionInterval(selectedRow, selectedRow);
  }

  public void verifyLine(LineContent lineContent) {
    lineContent.verify(!lineContent.isVerified());
  }

  private void listSupplyFiles(Supply supply) {
    ObjectTable<SupplierFile> supplierFiles =
        new ObjectTable<>(Columns.create("Auftrag", e -> e.getHeader().getOrderNr()));
    supplierFiles.setObjects(supply.getSupplierFiles());
    new ComponentController(supplierFiles).openTab();
  }

  private static final Icon okIcon = createIcon(FontAwesome.CHECK_CIRCLE, new Color(0x239696));
  private static final Icon addedIcon = createIcon(FontAwesome.PLUS, new Color(0xB648BA));
  private static final Icon ignoreIcon =
      createIcon(FontAwesome.EXCLAMATION_CIRCLE, new Color(0xFF0000));
  private static final Icon produceIcon = createIcon(FontAwesome.LEAF, new Color(0x00BD1D));
  private static final Icon noProduceIcon = createIcon(FontAwesome.STAR, new Color(0xFFB700));
  private static final Icon noFilterIcon = createIcon(FontAwesome.QUESTION, Color.GRAY);

  private static final Icon verified = okIcon;
  private static final Icon notVerified = noFilterIcon;

  private static Icon createIcon(FontAwesome icon, Color color) {
    return IconFontSwing.buildIcon(icon, Tools.scaleWithLabelScalingFactor(15), color);
  }

  private Icon getIcon(ResolveStatus status) {
    switch (status) {
      case OK:
        return okIcon;
      case ADDED:
        return addedIcon;
      case IGNORE:
        return ignoreIcon;
      case PRODUCE:
        return produceIcon;
      case NO_PRODUCE:
        return noProduceIcon;
      default:
        throw new IllegalArgumentException("Status has no icon: " + status);
    }
  }

  private void editArticle() {
    Optional<LineContent> selectedLine = lineContents.getSelectedObject();
    if (!selectedLine.isPresent()) {
      return;
    }
    ;
    controller.openArticle(selectedLine.get());
  }

  private void mergeArticle() {
    Optional<LineContent> selectedLine = lineContents.getSelectedObject();
    if (!selectedLine.isPresent()) {
      return;
    }
    ;
    controller.editArticle(selectedLine.get());
  }

  public Optional<Supply> getSelectedSupply() {
    return supplySelector.getSelectedObject();
  }

  public Optional<LineContent> getSelectedLineContent() {
    return lineContents.getSelectedObject();
  }

  public void messageConfirmDelete() {
    if (JOptionPane.showConfirmDialog(
            getContent(),
            "Willst du diese Lieferung wirklich löschen? Dieser Vorgang kann nicht rückgängig gemacht werden!",
            "Potenzieller Datenverlust",
            JOptionPane.YES_NO_OPTION)
        != JOptionPane.YES_OPTION) {
      throw new CancellationException();
    }
  }

  public boolean messageConfirmLineMerge() {
    return JOptionPane.showConfirmDialog(
            getContent(),
            "Willst du die Daten dieser Zeile wirklich in den Artikelstamm übernehmen?",
            "Artikeldaten überschreiben",
            JOptionPane.YES_NO_OPTION)
        == JOptionPane.YES_OPTION;
  }

  public boolean messageConfirmArticleMerge() {
    return JOptionPane.showConfirmDialog(
            getContent(),
            "Willst du die Artikeldaten in den Lieferschein übernehmen?",
            "Lieferschein überschreiben",
            JOptionPane.YES_NO_OPTION)
        == JOptionPane.YES_OPTION;
  }

  public void setLoadingIndicatorVisible(boolean b) {
    loadingIndicator.setVisible(b);
  }

  public void messageSelectSupplyFirst() {
    message("Bitte wähle zunächst eine Lieferung aus!", "Keine Lieferung ausgewählt!");
  }

  public void messageDefaultDirNotFound() {
    message(
        "Das Standardverzeichnis konnte nicht gefunden werden!\nIst der USB-Stick eingesteckt?",
        "Standardverzeichnis nicht gefunden");
  }

  public boolean messageConfirmBarcode(LineContent lineContent, String barcode) {
    return JOptionPane.showConfirmDialog(
            getContent(),
            "Soll der Barcode \""
                + barcode
                + "\" für den Artikel \""
                + lineContent.getName()
                + "\" übernommen werden?",
            "Barcode übernehmen",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            createIcon(FontAwesome.BARCODE, new Color(0)))
        == JOptionPane.YES_OPTION;
  }

  public void messageInvalidBarcode(String barcode) {
    message(
        "\"" + barcode + "\" ist kein gültiger Barcode.",
        "Ungültiger Barcode",
        JOptionPane.ERROR_MESSAGE);
  }

  private static final Icon PRICE_ICON = Icons.defaultIcon(FontAwesome.EUR, new Color(0, 126, 107));
  private static final Icon DEPOSIT_ICON =
      Icons.defaultIcon(FontAwesome.BEER, new Color(0, 43, 151));
  private static final Icon CONTAINER_ICON =
      Icons.defaultIcon(FontAwesome.SHOPPING_CART, new Color(153, 72, 228));
  private static final Icon SIZE_ICON =
      Icons.defaultIcon(FontAwesome.ARROWS_ALT, new Color(193, 101, 62));

  private static Icon getChangeIcon(ArticleChange result) {
    switch (result.getType()) {
      case PRICE -> {
        return PRICE_ICON;
      }
      case SINGLE_DEPOSIT -> {
        return DEPOSIT_ICON;
      }
      case CONTAINER_DEPOSIT -> {
        return CONTAINER_ICON;
      }
      case CONTAINER_SIZE -> {
        return SIZE_ICON;
      }
      default -> {
        return Icons.SHOP_ICON;
      }
    }
  }

  private static String getChangeDesignation(ArticleChange result) {
    switch (result.getType()) {
      case PRICE -> {
        return "Preis";
      }
      case SINGLE_DEPOSIT -> {
        return "Einzelpfand";
      }
      case CONTAINER_DEPOSIT -> {
        return "Gebindepfand";
      }
      case CONTAINER_SIZE -> {
        return "Gebindegröße";
      }
      default -> {
        return "Unbekannt (??)";
      }
    }
  }

  /**
   * Shows the pending article changes. All changes are accepted by default, single changes can be
   * excluded by clicking their checkbox.
   *
   * @return the accepted changes (empty if the user deselected all), or empty optional if the
   *     dialog was cancelled and the import should be aborted
   */
  public Optional<List<ArticleChangeRow>> askArticleChanges(List<ArticleChangeRow> rows) {
    if (rows.isEmpty()) {
      return Optional.of(List.of());
    }
    Set<ArticleChangeRow> excluded = Collections.newSetFromMap(new IdentityHashMap<>());
    // the dialog is a separate window, so only the table itself has to be repainted
    ObjectTable<ArticleChangeRow> table = new ObjectTable<>(rows);
    table.setColumns(
        Columns.<ArticleChangeRow>createSortableBooleanIcon(
                "Übernehmen", row -> !excluded.contains(row))
            .withLeftClickConsumer(
                row -> {
                  if (!excluded.remove(row)) {
                    excluded.add(row);
                  }
                  table.repaint();
                })
            .withPreferredWidth(90),
        Columns.<ArticleChangeRow>createIconColumn("", row -> getChangeIcon(row.change())),
        Columns.<ArticleChangeRow>create("Änderung", row -> getChangeDesignation(row.change()))
            .withPreferredWidth(200),
        Columns.<ArticleChangeRow>create("von", row -> row.change().getOldValue())
            .withPreferredWidth(50)
            .withSorter(Column.NUMBER_SORTER),
        Columns.<ArticleChangeRow>create("zu", row -> row.change().getNewValue())
            .withPreferredWidth(50)
            .withSorter(Column.NUMBER_SORTER),
        Columns.<ArticleChangeRow>create(
                "Lieferantennummer", row -> row.article().getSuppliersItemNumber())
            .withPreferredWidth(100)
            .withSorter(Column.NUMBER_SORTER),
        Columns.<ArticleChangeRow>create("KB-Nummer", row -> row.article().getKbNumber())
            .withPreferredWidth(80)
            .withSorter(Column.NUMBER_SORTER),
        Columns.<ArticleChangeRow>create("Artikel", row -> row.article().getName())
            .withPreferredWidth(250));

    JLabel label =
        new JLabel(
            "<html>Sollen die folgenden Artikel-Änderungen übernommen werden? "
                + "Mit Klick auf das Kästchen lassen sich einzelne Änderungen ausschließen.</html>");
    label.setFont(label.getFont().deriveFont(Font.ITALIC));
    JPanel tablePanel = new JPanel(new BorderLayout());
    tablePanel.add(label, BorderLayout.NORTH);
    JScrollPane scrollPane = new JScrollPane(table);
    // about 1.5 times the default size of the dialog
    scrollPane.setPreferredSize(new Dimension(1200, 600));
    tablePanel.add(scrollPane, BorderLayout.CENTER);
    JButton selectAll = new JButton("Alle auswählen");
    selectAll.addActionListener(
        e -> {
          excluded.clear();
          table.repaint();
        });
    JButton deselectAll = new JButton("Alle abwählen");
    deselectAll.addActionListener(
        e -> {
          excluded.addAll(rows);
          table.repaint();
        });
    JPanel selectionButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
    selectionButtons.add(selectAll);
    selectionButtons.add(deselectAll);
    tablePanel.add(selectionButtons, BorderLayout.SOUTH);
    int choice =
        JOptionPane.showConfirmDialog(
            getContent(),
            tablePanel,
            "Artikeländerungen",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE);
    if (choice != JOptionPane.OK_OPTION) {
      return Optional.empty();
    }
    return Optional.of(rows.stream().filter(row -> !excluded.contains(row)).toList());
  }

  // @spotless:off

    {
// GUI initializer generated by IntelliJ IDEA GUI Designer
// >>> IMPORTANT!! <<<
// DO NOT EDIT OR ADD ANY CODE HERE!
        $$$setupUI$$$();
    }

    /** Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        createUIComponents();
        main = new JPanel();
        main.setLayout(new GridLayoutManager(3, 2, new Insets(5, 5, 5, 5), -1, -1));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(3, 4, new Insets(0, 0, 0, 0), -1, -1));
        panel1.setAlignmentX(0.2f);
        main.add(panel1, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, new Dimension(600, -1), 0, false));
        final JScrollPane scrollPane1 = new JScrollPane();
        panel1.add(scrollPane1, new GridConstraints(0, 0, 1, 3, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, new Dimension(300, -1), null, 0, false));
        scrollPane1.setViewportView(supplySelector);
        deleteSupply = new JButton();
        deleteSupply.setText("Lieferung löschen");
        panel1.add(deleteSupply, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        loadingIndicator = new JPanel();
        loadingIndicator.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        loadingIndicator.setVisible(false);
        panel1.add(loadingIndicator, new GridConstraints(1, 0, 1, 3, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JLabel label1 = new JLabel();
        label1.setText("Ordner wird gelesen...");
        loadingIndicator.add(label1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        progressBar1 = new JProgressBar();
        progressBar1.setIndeterminate(true);
        loadingIndicator.add(progressBar1, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        panel1.add(spacer1, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        viewOrders = new JButton();
        viewOrders.setText("Auftragsdetails");
        panel1.add(viewOrders, new GridConstraints(2, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(3, 4, new Insets(0, 0, 0, 0), -1, -1));
        panel2.setAlignmentX(1.0f);
        main.add(panel2, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JScrollPane scrollPane2 = new JScrollPane();
        panel2.add(scrollPane2, new GridConstraints(1, 0, 1, 4, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        scrollPane2.setViewportView(lineContents);
        final Spacer spacer2 = new Spacer();
        panel2.add(spacer2, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        filter = new AdvancedComboBox();
        panel2.add(filter, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel3 = new JPanel();
        panel3.setLayout(new GridLayoutManager(1, 4, new Insets(0, 0, 0, 0), -1, -1));
        panel2.add(panel3, new GridConstraints(2, 1, 1, 3, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        openArticle = new JButton();
        openArticle.setText("Artikel Öffnen");
        panel3.add(openArticle, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        printProduce = new JButton();
        printProduce.setText("Obst und Gemüse Verkaufspreise drucken");
        panel3.add(printProduce, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        export = new JButton();
        export.setText("Lieferung übernehmen");
        panel3.add(export, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        mergeArticle = new JButton();
        mergeArticle.setText("Artikel Speichern und Öffnen");
        panel3.add(mergeArticle, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JLabel label2 = new JLabel();
        label2.setText("Filter:");
        panel2.add(label2, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JLabel label3 = new JLabel();
        label3.setText("Artikel des Lieferscheins");
        main.add(label3, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JLabel label4 = new JLabel();
        label4.setText("Lieferungen");
        main.add(label4, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final Spacer spacer3 = new Spacer();
        main.add(spacer3, new GridConstraints(2, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
    }

    /** @noinspection ALL */
    public JComponent $$$getRootComponent$$$() {
        return main;
    }

    // @spotless:on
}
