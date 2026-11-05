package kernbeisser.Windows.PrintLabels;

import jakarta.persistence.NoResultException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import kernbeisser.CustomComponents.ObjectTable.Column;
import kernbeisser.CustomComponents.ObjectTable.Columns.Columns;
import kernbeisser.DBConnection.QueryBuilder;
import kernbeisser.DBEntities.Article;
import kernbeisser.DBEntities.Article_;
import kernbeisser.DBEntities.Repositories.ArticleRepository;
import kernbeisser.Enums.ShopRange;
import kernbeisser.Exeptions.handler.UnexpectedExceptionHandler;
import kernbeisser.Forms.ObjectForm.Components.Source;
import kernbeisser.Reports.ArticleLabel;
import kernbeisser.Reports.Report;
import kernbeisser.Useful.Date;
import kernbeisser.Windows.CollectionView.CollectionController;
import kernbeisser.Windows.MVC.IModel;
import lombok.Getter;

public class PrintLabelsModel implements IModel<PrintLabelsController> {

  @Getter private Map<Article, Integer> printPoolMapBefore;
  @Getter private Map<Article, Integer> printPoolMap;

  public static CollectionController<Article> getArticleSource() {
    Map<Integer, Instant> lastDeliveries = ArticleRepository.getLastDeliveries();
    return new CollectionController<>(
        new ArrayList<>(),
        Source.empty(),
        Columns.create("Name", Article::getName)
            .withDefaultFilter()
            .withColumnAdjustor(e -> e.setPreferredWidth(200)),
        Columns.create("Ladennummer", Article::getKbNumber)
            .withSorter(Column.NUMBER_SORTER)
            .withDefaultFilter(),
        Columns.create("Lieferantennummer", Article::getSuppliersItemNumber)
            .withSorter(Column.NUMBER_SORTER)
            .withDefaultFilter(),
        Columns.<Article>create(
                "Letzte Lieferung",
                e -> Date.safeDateFormat(lastDeliveries.get(e.getKbNumber()), Date.INSTANT_DATE))
            .withSorter(Column.DATE_SORTER(Date.INSTANT_DATE))
            .withDefaultFilter());
  }

  public int getPrintPool(Article article) {
    return Optional.ofNullable(printPoolMap.get(article)).orElse(0);
  }

  public void setPrintPoolBefore(Map<Article, Integer> printPool) {
    printPoolMapBefore = new HashMap<>(printPool);
    printPoolMap = new HashMap<>(printPool);
  }

  public long getArticlePrintPoolSize() {
    return printPoolMap.values().stream().mapToInt(i -> i).sum();
  }

  public static Collection<Article> getAllArticles() {
    return QueryBuilder.selectAll(Article.class)
        .where(Article_.shopRange.eq(ShopRange.NOT_IN_RANGE).not())
        .orderBy(Article_.name.asc())
        .getResultList();
  }

  static String getArticleSupplierName(Article article) {
    return article.getSupplier().getName();
  }

  Map<Article, Integer> getArticlesWithManyLabels(
      CollectionController<Article> articles, int threshold) {
    Map<Article, Integer> result = new LinkedHashMap<>();
    articles.getModel().getLoaded().stream()
        .filter(a -> printPoolMap.getOrDefault(a, 0) > threshold)
        .forEach(a -> result.put(a, printPoolMap.get(a)));
    return result;
  }

  void print(CollectionController<Article> articles) {
    List<Article> printPool =
        articles.getModel().getLoaded().stream()
            .flatMap(a -> Collections.nCopies(printPoolMap.get(a), a).stream())
            .collect(Collectors.toList());
    Report report = new ArticleLabel(printPool);
    String printMessage = "Drucke Ladenschilder";
    report.exportPdf(printMessage, UnexpectedExceptionHandler::showUnexpectedErrorWarning);
  }

  void setPrintPool(Article article, int numberOfLabels) {
    if (numberOfLabels == 0) {
      printPoolMap.remove(article);
    } else {
      printPoolMap.put(article, numberOfLabels);
    }
  }

  public static Article getByBarcode(String s) throws NoResultException {
    return ArticleRepository.getByBarcode(Long.parseLong(s)).orElseThrow(NoResultException::new);
  }
}
