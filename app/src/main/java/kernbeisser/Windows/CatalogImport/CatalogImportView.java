package kernbeisser.Windows.CatalogImport;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.FontUIResource;
import javax.swing.text.StyleContext;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import kernbeisser.Config.Config;
import kernbeisser.CustomComponents.ObjectTable.Column;
import kernbeisser.CustomComponents.ObjectTable.Columns.Columns;
import kernbeisser.CustomComponents.ObjectTable.ObjectTable;
import kernbeisser.Exeptions.CatalogImportCriticalErrorException;
import kernbeisser.Tasks.Catalog.CatalogImportError;
import kernbeisser.Useful.Date;
import kernbeisser.Useful.Icons;
import kernbeisser.Windows.MVC.IView;
import org.jetbrains.annotations.NotNull;

public class CatalogImportView implements IView<CatalogImportController> {
  private JTextField filePath;
  private JButton fileChooser;
  private ObjectTable<CatalogImportError> protocol;
  private JButton close;
  private JButton applyChanges;

  private JPanel main;
  private JTextField scope;
  private JTextField description;
  private JTextField validFrom;
  private JTextField validTo;
  private JTextField createdDate;
  private JTextField createdTime;
  private JPanel infoPanel;
  private JScrollPane protocolPane;
  private JLabel protocolCaption;
  private JTextField lastCatalogCreationDate;
  private JTextField lastCatalogValidDate;
  private JProgressBar loadingIndicator;
  private CatalogImportController controller;

  @Override
  public void initialize(CatalogImportController controller) {
    this.controller = controller;
    close.addActionListener(e -> back());
    // readFile.addActionListener(e -> controller.readFile(filePath.getText()));
    filePath.addActionListener(e -> controller.readFile(filePath.getText()));
    applyChanges.addActionListener(e -> controller.applyChanges());
    applyChanges.setEnabled(false);
    fileChooser.addActionListener(e -> openFileExplorer());
    fileChooser.setIcon(IconFontSwing.buildIcon(FontAwesome.FOLDER, 20, new Color(255, 192, 3)));
    applyChanges.setIcon(IconFontSwing.buildIcon(FontAwesome.DOWNLOAD, 20, new Color(26, 49, 134)));
    close.setIcon(IconFontSwing.buildIcon(FontAwesome.WINDOW_CLOSE, 20, new Color(133, 0, 16)));
  }

  public void setScope(String t) {
    scope.setText(t);
  }

  public void setDescription(String t) {
    description.setText(t);
  }

  public void setCreatedDate(Instant date) {
    createdDate.setText(Date.safeDateFormat(date, Date.INSTANT_DATE));
  }

  public void setCreatedTime(Instant time) {
    createdTime.setText(Date.safeDateFormat(time, Date.INSTANT_TIME));
  }

  public void setValidFrom(Instant date) {
    validFrom.setText(Date.safeDateFormat(date, Date.INSTANT_DATE));
  }

  public void setValidTo(Instant date) {
    validTo.setText(Date.safeDateFormat(date, Date.INSTANT_DATE));
  }

  public void setLastCatalogInfo(Instant lastCreationDate, Instant lastValidDate) {
    lastCatalogCreationDate.setText(Date.safeDateFormat(lastCreationDate, Date.INSTANT_DATE));
    lastCatalogValidDate.setText(Date.safeDateFormat(lastValidDate, Date.INSTANT_DATE));
  }

  public void setApplyChangesEnabled(boolean b) {
    applyChanges.setEnabled(b);
  }

  public void indicateLoading(boolean b) {
    applyChanges.setEnabled(!b);
    loadingIndicator.setVisible(b);
  }

  static final Icon infoIcon = Icons.defaultIcon(FontAwesome.INFO_CIRCLE, Color.DARK_GRAY);
  static final Icon criticalIcon = Icons.defaultIcon(FontAwesome.EXCLAMATION_CIRCLE, Color.ORANGE);
  static final Icon rowIcon = Icons.defaultIcon(FontAwesome.TABLE, new Color(26, 49, 134));

  private void createUIComponents() {
    protocol =
        new ObjectTable<CatalogImportError>(
            Columns.create("Zeile / Artikelnummer", CatalogImportError::getLineNumber)
                .withSorter(Column.NUMBER_SORTER)
                .withPreferredWidth(100),
            Columns.<CatalogImportError>create(
                    "Fehlerbeschreibung", e -> e.getE().getLocalizedMessage())
                .withPreferredWidth(1200),
            Columns.<CatalogImportError>createIconColumn(
                    "Details",
                    e ->
                        e.getE() instanceof CatalogImportCriticalErrorException
                            ? criticalIcon
                            : e.hasRowDetails() ? rowIcon : infoIcon)
                .withTooltip(
                    e ->
                        e.hasRowDetails()
                            ? "Katalogzeile mit markierten Fehlern anzeigen"
                            : "Fehlerdetails anzeigen")
                .withPreferredWidth(70)
                .withLeftClickConsumer(e -> controller.showImportErrorDetails(e)));
    protocol.setSortKeys(new RowSorter.SortKey(0, SortOrder.ASCENDING));
  }

  public void setReadErrors(List<CatalogImportError> errors) {
    protocol.setObjects(errors);
    protocol.repaint();
  }

  @Override
  public @NotNull JComponent getContent() {
    return main;
  }

  @Override
  public String getTitle() {
    return "Katalog einlesen";
  }

  public void messageFormatError(String message) {
    JOptionPane.showMessageDialog(
        getContent(), message, "Fehler in der Katalogdatei", JOptionPane.ERROR_MESSAGE);
  }

  public void messagePathNotFound(String path) {
    JOptionPane.showMessageDialog(
        getContent(),
        "Die Datei \"" + path + "\" konnte nicht gefunden werden.",
        "Datei nicht gefunden",
        JOptionPane.ERROR_MESSAGE);
  }

  void openFileExplorer() {
    Path importPath = Config.getConfig().getDefaultBnnInboxDir().toPath();
    String chooserRoot = Files.exists(importPath) ? importPath.toString() : ".";
    JFileChooser jFileChooser = new JFileChooser(chooserRoot);
    jFileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
    jFileChooser.setFileFilter(
        new FileNameExtensionFilter("Katalog", "BNN", "bnn", "CSV", "csv", "TXT", "txt"));
    jFileChooser.addActionListener(
        e -> {
          if (jFileChooser.getSelectedFile() == null) {
            return;
          }
          String choosenFile = jFileChooser.getSelectedFile().getAbsolutePath();
          filePath.setText(choosenFile);
          controller.readFile(choosenFile);
        });
    jFileChooser.showOpenDialog(getContent());
  }

  public boolean confirmImportInValidCatalog(String s) {
    return JOptionPane.showConfirmDialog(
            getContent(),
            s + "\nSoll der Katalog wirklich eingelesen werden?",
            "Katalog nicht aktuell",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE)
        == JOptionPane.YES_OPTION;
  }

  public boolean confirmMergeCatalog() {
    return JOptionPane.showConfirmDialog(
            getContent(),
            "Der Katalog ist als \"Vollständig\" gekennzeichnet."
                + "\nSollen alte Einträge beibehalten werden, die in diesem Katalog nicht enthalten sind?"
                + "\nMit \"Nein\" wird der Katalog vollständig ersetzt.",
            "Katalog zusammenführen",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE)
        == JOptionPane.YES_OPTION;
  }

  // @spotless:off

    {
// GUI initializer generated by IntelliJ IDEA GUI Designer
// >>> IMPORTANT!! <<<
// DO NOT EDIT OR ADD ANY CODE HERE!
        $$$setupUI$$$();
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        createUIComponents();
        main = new JPanel();
        main.setLayout(new GridLayoutManager(7, 4, new Insets(5, 5, 5, 5), -1, -1));
        main.setAlignmentX(0.5f);
        final JLabel label1 = new JLabel();
        Font label1Font = this.$$$getFont$$$(null, Font.BOLD, 14, label1.getFont());
        if (label1Font != null) label1.setFont(label1Font);
        label1.setText("Kornkraft Katalog importieren");
        main.add(label1, new GridConstraints(0, 0, 1, 4, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        main.add(panel1, new GridConstraints(6, 0, 1, 4, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        close = new JButton();
        close.setText("Schließen");
        panel1.add(close, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        panel1.add(spacer1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        protocolPane = new JScrollPane();
        main.add(protocolPane, new GridConstraints(3, 0, 1, 4, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        protocolPane.setViewportView(protocol);
        fileChooser = new JButton();
        fileChooser.setText("");
        main.add(fileChooser, new GridConstraints(5, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JLabel label2 = new JLabel();
        label2.setText("Dateipfad:");
        label2.setVerticalAlignment(3);
        label2.setVerticalTextPosition(3);
        main.add(label2, new GridConstraints(4, 0, 1, 2, GridConstraints.ANCHOR_SOUTHWEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        infoPanel = new JPanel();
        infoPanel.setLayout(new GridLayoutManager(3, 6, new Insets(0, 0, 0, 0), -1, -1));
        main.add(infoPanel, new GridConstraints(1, 0, 1, 4, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JLabel label3 = new JLabel();
        label3.setText("Umfang:");
        label3.setVerticalAlignment(3);
        label3.setVerticalTextPosition(3);
        infoPanel.add(label3, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_SOUTHWEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(154, 16), null, 0, false));
        description = new JTextField();
        description.setAlignmentX(0.5f);
        description.setAutoscrolls(false);
        description.setEditable(false);
        description.setMargin(new Insets(2, 9, 2, 6));
        infoPanel.add(description, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(208, 30), null, 0, false));
        final JLabel label4 = new JLabel();
        label4.setText("Beschreibung:");
        label4.setVerticalAlignment(3);
        label4.setVerticalTextPosition(3);
        infoPanel.add(label4, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_SOUTHWEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(208, 16), null, 0, false));
        validFrom = new JTextField();
        validFrom.setAlignmentX(0.5f);
        validFrom.setAlignmentY(0.5f);
        validFrom.setAutoscrolls(false);
        validFrom.setEditable(false);
        validFrom.setMargin(new Insets(2, 9, 2, 6));
        infoPanel.add(validFrom, new GridConstraints(1, 4, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JLabel label5 = new JLabel();
        label5.setText("gütlig ab:");
        label5.setVerticalAlignment(3);
        label5.setVerticalTextPosition(3);
        infoPanel.add(label5, new GridConstraints(0, 4, 1, 1, GridConstraints.ANCHOR_SOUTHWEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        validTo = new JTextField();
        validTo.setAlignmentX(0.5f);
        validTo.setAlignmentY(0.5f);
        validTo.setAutoscrolls(false);
        validTo.setEditable(false);
        validTo.setMargin(new Insets(2, 9, 2, 6));
        infoPanel.add(validTo, new GridConstraints(1, 5, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JLabel label6 = new JLabel();
        label6.setText("gültig bis:");
        label6.setVerticalAlignment(3);
        label6.setVerticalTextPosition(3);
        infoPanel.add(label6, new GridConstraints(0, 5, 1, 1, GridConstraints.ANCHOR_SOUTHWEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        createdDate = new JTextField();
        createdDate.setAlignmentX(0.5f);
        createdDate.setAlignmentY(0.5f);
        createdDate.setAutoscrolls(false);
        createdDate.setEditable(false);
        createdDate.setMargin(new Insets(2, 9, 2, 6));
        infoPanel.add(createdDate, new GridConstraints(1, 2, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JLabel label7 = new JLabel();
        label7.setText("erstellt am:");
        label7.setVerticalAlignment(3);
        label7.setVerticalTextPosition(3);
        infoPanel.add(label7, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_SOUTHWEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        createdTime = new JTextField();
        createdTime.setAlignmentX(0.5f);
        createdTime.setAlignmentY(0.5f);
        createdTime.setAutoscrolls(false);
        createdTime.setEditable(false);
        createdTime.setMargin(new Insets(2, 9, 2, 6));
        infoPanel.add(createdTime, new GridConstraints(1, 3, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JLabel label8 = new JLabel();
        label8.setText("erstellt um:");
        label8.setVerticalAlignment(3);
        label8.setVerticalTextPosition(3);
        infoPanel.add(label8, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_SOUTHWEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        scope = new JTextField();
        scope.setAlignmentX(0.5f);
        scope.setAlignmentY(0.5f);
        scope.setAutoscrolls(false);
        scope.setEditable(false);
        scope.setInheritsPopupMenu(false);
        scope.setMargin(new Insets(2, 9, 2, 6));
        infoPanel.add(scope, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(154, 30), null, 0, false));
        lastCatalogCreationDate = new JTextField();
        lastCatalogCreationDate.setEditable(false);
        infoPanel.add(lastCatalogCreationDate, new GridConstraints(2, 2, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JLabel label9 = new JLabel();
        label9.setText("aktueller Katalog:");
        infoPanel.add(label9, new GridConstraints(2, 0, 1, 2, GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        lastCatalogValidDate = new JTextField();
        lastCatalogValidDate.setEditable(false);
        infoPanel.add(lastCatalogValidDate, new GridConstraints(2, 5, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        applyChanges = new JButton();
        applyChanges.setText("Daten übernehmen");
        main.add(applyChanges, new GridConstraints(5, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        filePath = new JTextField();
        main.add(filePath, new GridConstraints(5, 1, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        protocolCaption = new JLabel();
        protocolCaption.setText("Protokoll:");
        protocolCaption.setVerticalAlignment(3);
        protocolCaption.setVerticalTextPosition(3);
        main.add(protocolCaption, new GridConstraints(2, 0, 1, 4, GridConstraints.ANCHOR_SOUTHWEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        loadingIndicator = new JProgressBar();
        loadingIndicator.setIndeterminate(true);
        loadingIndicator.setVisible(false);
        main.add(loadingIndicator, new GridConstraints(4, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        protocolCaption.setLabelFor(protocolPane);
    }

    /**
     * @noinspection ALL
     */
    private Font $$$getFont$$$(String fontName, int style, int size, Font currentFont) {
        if (currentFont == null) return null;
        String resultName;
        if (fontName == null) {
            resultName = currentFont.getName();
        } else {
            Font testFont = new Font(fontName, Font.PLAIN, 10);
            if (testFont.canDisplay('a') && testFont.canDisplay('1')) {
                resultName = fontName;
            } else {
                resultName = currentFont.getName();
            }
        }
        Font font = new Font(resultName, style >= 0 ? style : currentFont.getStyle(), size >= 0 ? size : currentFont.getSize());
        boolean isMac = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).startsWith("mac");
        Font fontWithFallback = isMac ? new Font(font.getFamily(), font.getStyle(), font.getSize()) : new StyleContext().getFont(font.getFamily(), font.getStyle(), font.getSize());
        return fontWithFallback instanceof FontUIResource ? fontWithFallback : new FontUIResource(fontWithFallback);
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return main;
    }

    // @spotless:on
}
