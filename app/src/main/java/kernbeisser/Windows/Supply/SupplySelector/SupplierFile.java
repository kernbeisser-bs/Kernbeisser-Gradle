package kernbeisser.Windows.Supply.SupplySelector;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.*;
import kernbeisser.CustomComponents.ObjectTable.Column;
import kernbeisser.CustomComponents.ObjectTable.Columns.Columns;
import kernbeisser.CustomComponents.ObjectTable.ObjectTable;
import kernbeisser.DBConnection.DBConnection;
import kernbeisser.DBConnection.QueryBuilder;
import kernbeisser.DBEntities.Repositories.ArticleRepository;
import kernbeisser.DBEntities.ShoppingItem;
import kernbeisser.DBEntities.ShoppingItem_;
import kernbeisser.DBEntities.Supplier;
import kernbeisser.Enums.ArticleCatalogState;
import kernbeisser.Tasks.ArticleComparedToCatalogEntry;
import kernbeisser.Tasks.Catalog.Catalog;
import kernbeisser.Useful.Constants;
import kernbeisser.Windows.Supply.SupplyController;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Cleanup;
import lombok.Data;
import lombok.Setter;
import lombok.SneakyThrows;

@AllArgsConstructor
@Data
@Setter(AccessLevel.NONE)
public class SupplierFile {
  private final FileHeader header;
  private final List<LineContent> contents;
  private final Instant createDate;
  private final File origin;
  private final boolean alreadyImported;

  @SneakyThrows
  public static SupplierFile parse(File file) {
    List<String> lines = Files.readAllLines(file.toPath(), Catalog.DEFAULT_ENCODING);
    if (lines.isEmpty()) {
      return emptySupplierFile(file);
    }
    FileHeader header = FileHeader.parseLine(lines.get(0));
    return new SupplierFile(
        header,
        LineContent.parseContents(lines, file.getName(), 1),
        Files.readAttributes(file.toPath(), BasicFileAttributes.class).creationTime().toInstant(),
        file,
        QueryBuilder.selectAll(ShoppingItem.class)
            .where(ShoppingItem_.orderNo.eq(header.getOrderNr()))
            .hasResult());
  }

  @SneakyThrows
  private static SupplierFile emptySupplierFile(File file) {
    return new SupplierFile(
        null,
        null,
        Files.readAttributes(file.toPath(), BasicFileAttributes.class).creationTime().toInstant(),
        file,
        false);
  }

  public Collection<LineContent> getNotInCatalog() {
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup("commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    Supplier kkSupplier = Constants.KK_SUPPLIER;
    return contents.stream()
        .filter(
            e ->
                ArticleRepository.getBySuppliersItemNumber(kkSupplier, e.getKkNumber()).isPresent())
        .collect(Collectors.toCollection(ArrayList::new));
  }

  public List<ArticleChangeRow> collectPendingArticleChanges() {
    List<ArticleChangeRow> result = new ArrayList<>();
    for (LineContent content : contents) {
      if (SupplyController.shouldBecomeShoppingItem(content)) {
        result.addAll(ArticleRepository.findPendingArticleChanges(Constants.KK_SUPPLIER, content));
      }
    }
    return result;
  }

  public Collection<ShoppingItem> collectShoppingItems(JComponent errorDisplayComponent) {

    List<ArticleCatalogState> irrelevantCatalogStates =
        Collections.singletonList(ArticleCatalogState.BARCODE_CHANGED);
    List<ArticleCatalogState> noBarcodeCatalogStates =
        Arrays.asList(
            ArticleCatalogState.BARCODE_CONFLICT_SAME_SUPPLIER,
            ArticleCatalogState.BARCODE_CONFLICT_OTHER_SUPPLIER);
    Supplier kkSupplier = Constants.KK_SUPPLIER;
    Collection<ShoppingItem> shoppingItems = new ArrayList<>();
    Collection<ArticleComparedToCatalogEntry> collectErrors = new ArrayList<>();
    for (LineContent content : contents) {
      if (!SupplyController.shouldBecomeShoppingItem(content)) {
        continue;
      }
      boolean noBarcode = false;
      ArticleComparedToCatalogEntry comparison = content.getComparedToCatalog();
      if (comparison != null) {
        ArticleCatalogState comparisonResultType = comparison.getResultType();
        if (irrelevantCatalogStates.contains(comparisonResultType)) {
          collectErrors.add(comparison);
          continue;
        }
        noBarcode = noBarcodeCatalogStates.contains(comparisonResultType);
        if (noBarcode) {
          collectErrors.add(comparison);
        }
      }
      shoppingItems.add(
          SupplyController.createShoppingItem(kkSupplier, content, header.getOrderNr(), noBarcode));
    }
    if (!collectErrors.isEmpty()) {
      ObjectTable<ArticleComparedToCatalogEntry> errors =
          new ObjectTable<>(
              collectErrors,
              Columns.<ArticleComparedToCatalogEntry>create(
                      "KK-Nummer", e -> e.getArticle().getSuppliersItemNumber())
                  .withSorter(Column.NUMBER_SORTER),
              Columns.<ArticleComparedToCatalogEntry>create(
                      "KB-Nummer", e -> e.getArticle().getKbNumber())
                  .withSorter(Column.NUMBER_SORTER),
              Columns.<ArticleComparedToCatalogEntry>create(
                      "Artikel", e -> e.getArticle().getName())
                  .withPreferredWidth(150),
              Columns.create("Fehler", this::comparisonErrorMessage).withPreferredWidth(350));
      JScrollPane errorPane = new JScrollPane(errors);
      Dimension thisSize = errorDisplayComponent.getSize();
      errorPane.setPreferredSize(
          new Dimension((int) (thisSize.getWidth() * 0.8), (int) (thisSize.getHeight() * 0.8)));

      JOptionPane.showMessageDialog(
          errorDisplayComponent,
          errorPane,
          "Bei der Übernahme der Artikel sind Fehler aufgetreten",
          JOptionPane.WARNING_MESSAGE);
    }
    return shoppingItems;
  }

  private String comparisonErrorMessage(ArticleComparedToCatalogEntry compared) {
    String message;
    switch (compared.getResultType()) {
      case BARCODE_CHANGED:
        message =
            "geänderter Barcode! Evtl. wurde die Artikelnummer durch den Lieferanten für einen neuen Artikel vergeben? Der Artikel wird daher nicht geändert!";
        break;
      case BARCODE_CONFLICT_SAME_SUPPLIER:
        message =
            String.format(
                "Barcode %d ist beim KB-Artikel %d vorhanden und wird daher nicht übernommen!",
                compared.getCatalogEntry().getEanLadenEinheit(),
                compared.getConflictingArticle().getKbNumber());
        break;
      case BARCODE_CONFLICT_OTHER_SUPPLIER:
        message =
            String.format(
                "Barcode %d ist schon beim KB-Artikel %d des Lieferanten %s vorhanden und wird daher nicht übernommen!",
                compared.getCatalogEntry().getEanLadenEinheit(),
                compared.getConflictingArticle().getKbNumber(),
                compared.getConflictingArticle().getSupplier().getName());
        break;
      default:
        message = "";
    }
    return message;
  }

  @Override
  public boolean equals(Object obj) {
    return super.equals(obj);
  }

  public int hashCode() {
    return header.getId().hashCode();
  }
}
