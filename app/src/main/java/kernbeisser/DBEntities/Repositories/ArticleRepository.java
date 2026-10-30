package kernbeisser.DBEntities.Repositories;

import static kernbeisser.DBConnection.ExpressionFactory.*;
import static kernbeisser.DBConnection.PredicateFactory.*;
import static kernbeisser.Enums.ArticleDeletionResult.*;
import static kernbeisser.Enums.ShopRange.DISCONTINUED;
import static kernbeisser.Useful.Constants.KK_SUPPLIER;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import kernbeisser.DBConnection.*;
import kernbeisser.DBEntities.*;
import kernbeisser.EntityWrapper.ObjectState;
import kernbeisser.Enums.*;
import kernbeisser.Exeptions.handler.UnexpectedExceptionHandler;
import kernbeisser.Tasks.ArticleComparedToCatalogEntry;
import kernbeisser.Useful.Tools;
import kernbeisser.Windows.Supply.SupplySelector.ArticleChange;
import kernbeisser.Windows.Supply.SupplySelector.ArticleChangeRow;
import kernbeisser.Windows.Supply.SupplySelector.LineContent;
import lombok.Cleanup;
import lombok.extern.log4j.Log4j2;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.DefaultRevisionEntity;
import org.hibernate.envers.query.AuditEntity;
import org.hibernate.envers.query.AuditQuery;
import org.jetbrains.annotations.NotNull;
import rs.groump.Access;
import rs.groump.AccessManager;

@Log4j2
public class ArticleRepository {
  public static final SurchargeGroup UNLISTED_GROUP = SurchargeGroup.getUnlistedGroup();

  private ArticleRepository() {}

  public static Collection<Article> defaultSearch(String search, int maxResults) {
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup(value = "commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    return createQuery(em, search).setMaxResults(maxResults).getResultList();
  }

  public static Collection<Article> getDefaultAll(
      String search, Predicate<Article> articlePredicate, int max) {
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup(value = "commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    return createQuery(em, search)
        .getResultStream()
        .filter(articlePredicate)
        .limit(max)
        .collect(Collectors.toCollection(ArrayList::new));
  }

  public static boolean isKkArticle(Article article) {
    Supplier supplier = article.getSupplier();
    return (supplier != null && supplier.equals(KK_SUPPLIER));
  }

  public static Optional<ObjectState<Article>> getByKbNumber(
      EntityManager em, int kbNumber, boolean filterShopRange) {
    var qb = QueryBuilder.selectAll(Article.class).where(Article_.kbNumber.eq(kbNumber));
    if (filterShopRange) qb.where(Article_.shopRange.in(ShopRange.visibleRanges()));
    return qb.getResultStream(em)
        .findAny()
        .map(ObjectState::currentState)
        .or(() -> searchInArticleHistoryForKbNumber(em, kbNumber));
  }

  public static Optional<ObjectState<Article>> getByKbNumber(
      int kbNumber, boolean filterShopRange) {
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup(value = "commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    return getByKbNumber(em, kbNumber, filterShopRange);
  }

  public static Optional<ObjectState<Article>> searchInArticleHistoryForKbNumber(
      EntityManager em, int kbNumber) {
    return ((Optional<Object[]>)
            AuditReaderFactory.get(em)
                .createQuery()
                .forRevisionsOfEntity(Article.class, false, true)
                .add(AuditEntity.property("kbNumber").eq(kbNumber))
                .addOrder(AuditEntity.property("updateDate").desc())
                .getResultList()
                .stream()
                .findFirst())
        .map(e -> ObjectState.wrap(((Article) e[0]), ((DefaultRevisionEntity) e[1]).getId()))
        .map(
            e -> {
              e.getValue().getSurchargeGroup().getSurcharge();
              return e;
            });
  }

  public static Optional<Article> getBySuppliersItemNumber(Supplier supplier, int suppliersNumber) {
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup("commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    return getBySuppliersItemNumber(supplier, suppliersNumber, em);
  }

  public static Optional<Article> getByKkItemNumber(int suppliersNumber) {
    return getBySuppliersItemNumber(KK_SUPPLIER, suppliersNumber);
  }

  public static Optional<Article> getBySuppliersItemNumber(
      Supplier targetSupplier, int suppliersNumber, EntityManager em) {
    return DBConnection.getConditioned(
            em,
            Article.class,
            Article_.suppliersItemNumber.eq(suppliersNumber),
            Article_.supplier.eq(targetSupplier))
        .stream()
        .findFirst();
  }

  public static Optional<Article> getByBarcode(long targetBarcode) throws NoResultException {
    List<Article> articles =
        DBConnection.getConditioned(Article.class, Article_.barcode.eq(targetBarcode));
    return articles.stream().findFirst();
  }

  public static Article getOrCreateRawPriceArticle(RawPrice rawPrice) {
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    List<Integer> rawPriceIdentifiers =
        Arrays.stream(ArticleConstants.class.getEnumConstants())
            .filter(e -> e != ArticleConstants.CUSTOM_PRODUCT)
            .map(ArticleConstants::getUniqueIdentifier)
            .collect(Collectors.toList());
    try {
      @Cleanup(value = "commit")
      EntityTransaction et = em.getTransaction();
      et.begin();
      return QueryBuilder.selectAll(Article.class)
          .where(Article_.name.eq(rawPrice.getName()), Article_.kbNumber.in(rawPriceIdentifiers))
          .getSingleResult(em);
    } catch (NoResultException e) {
      ArticleConstants identifierEnum = ArticleConstants.CUSTOM_PRODUCT;
      AtomicReference<Supplier> supplier = new AtomicReference<>();
      VAT vat = VAT.LOW;
      switch (rawPrice) {
        case SOLIDARITY:
          supplier.set(Supplier.getSolidaritySupplier());
          identifierEnum = ArticleConstants.SOLIDARITY;
          break;
        case BAKERY:
          supplier.set(Supplier.getBakerySupplier());
          identifierEnum = ArticleConstants.BAKERY;
          break;
        case DEPOSIT:
          supplier.set(Supplier.getDepositSupplier());
          identifierEnum = ArticleConstants.DEPOSIT;
          vat = VAT.HIGH;
          break;
        case PRODUCE:
          supplier.set(Supplier.getProduceSupplier());
          identifierEnum = ArticleConstants.PRODUCE;
          break;
        case ITEM_DEPOSIT:
        case CONTAINER_DEPOSIT:
          return getOrCreateRawPriceArticle(RawPrice.DEPOSIT);
      }
      EntityTransaction et = em.getTransaction();
      et.begin();
      Article article = new Article();
      article.setName(rawPrice.getName());
      article.setKbNumber(identifierEnum.getUniqueIdentifier());
      article.setMetricUnits(MetricUnits.NONE);
      article.setVat(vat);
      article.setSupplier(supplier.get());
      article.setSuppliersItemNumber(identifierEnum.getUniqueIdentifier());
      rs.groump.Access.runWithAccessManager(
          AccessManager.ACCESS_GRANTED,
          () -> article.setSurchargeGroup(supplier.get().getOrPersistDefaultSurchargeGroup(em)));
      article.setShopRange(ShopRange.NOT_IN_RANGE);
      em.persist(article);
      em.flush();
      et.commit();
      return getOrCreateRawPriceArticle(rawPrice);
    } catch (Exception o) {
      throw UnexpectedExceptionHandler.showUnexpectedErrorWarning(o);
    }
  }

  private static TypedQuery<Article> createQuery(EntityManager em, String search) {
    if (search.startsWith("PL:")) {
      return QueryBuilder.selectAll(Article.class)
          .where(Article_.priceList.child(PriceList_.name).eq(search.replace("PL:", "").trim()))
          .buildQuery(em);
    }
    int n = Tools.tryParseInt(search);
    String ds = (search.length() > 3 ? "%" + search + "%" : search + "%").toLowerCase();
    long l = Tools.tryParseLong(search);
    return QueryBuilder.selectAll(Article.class)
        .where(
            or(
                Article_.kbNumber.eq(n),
                Article_.suppliersItemNumber.eq(n),
                like(lower(Article_.name), ds),
                Article_.barcode.eq(l),
                like(Article_.barcode.as(String.class), "%" + search)))
        .orderBy(Article_.name.asc())
        .buildQuery(em);
  }

  // TODO find better solution this one only makes sense when trying to find hundreds of free ids...
  public static int nextFreeKBNumber(EntityManager em, int min) {
    List<Integer> usedKbNumbers =
        QueryBuilder.select(Article_.kbNumber)
            .where(greaterOrEq(Article_.kbNumber, asExpression(min)))
            .orderBy(Article_.kbNumber.asc())
            .getResultList(em);
    for (int id = min; ; id++) {
      if (!usedKbNumbers.contains(id)) return id;
    }
  }

  public static int nextFreeKBNumber(EntityManager em) {
    return nextFreeKBNumber(em, 1);
  }

  public static Article nextArticleTo(
      EntityManager em, int suppliersItemNumber, Supplier supplier) {
    return QueryBuilder.selectAll(Article.class)
        .where(Article_.supplier.eq(supplier))
        .orderBy(abs(diff(Article_.suppliersItemNumber, asExpression(suppliersItemNumber))).asc())
        .buildQuery(em)
        .setMaxResults(1)
        .getResultStream()
        .findFirst()
        .orElse(null);
  }

  public static Article nextArticleTo(
      EntityManager em, int suppliersItemNumber, Supplier supplier, PriceList excludedPriceList) {
    return QueryBuilder.selectAll(Article.class)
        .where(Article_.supplier.eq(supplier), Article_.priceList.eq(excludedPriceList).not())
        .orderBy(abs(diff(Article_.suppliersItemNumber, asExpression(suppliersItemNumber))).asc())
        .getResultStream(em)
        .findFirst()
        .orElse(null);
  }

  public static Map<Integer, Instant> getLastDeliveries() {
    return QueryBuilder.select(
            ShoppingItem.class, ShoppingItem_.kbNumber, max(ShoppingItem_.createDate))
        .where(ShoppingItem_.purchase.isNull())
        .groupBy(ShoppingItem_.kbNumber)
        .orderBy(ShoppingItem_.kbNumber.asc())
        .getResultList()
        .stream()
        .collect(
            Collectors.toMap(
                tuple -> tuple.get(0, Integer.class), tuple -> tuple.get(1, Instant.class)));
  }

  public static double getContainerSurchargeReduction() {
    return Setting.CONTAINER_SURCHARGE_REDUCTION.getDoubleValue();
  }

  public static double getOfferSurchargeReduction() {
    return Setting.OFFER_SURCHARGE_REDUCTION.getDoubleValue();
  }

  public static double calculateSurcharge(Article article, boolean preordered) {
    double articleSurcharge = article.getSurchargeGroup().getSurcharge();
    double offerReduction = article.isOffer() ? getOfferSurchargeReduction() : 1.0;
    double preorderReduction = preordered ? getContainerSurchargeReduction() : 1.0;
    return articleSurcharge * Math.min(offerReduction, preorderReduction);
  }

  public static double calculateRetailPrice(
      double netPrice, VAT vat, double surcharge, double discount, boolean preordered)
      throws NullPointerException {
    return netPrice * (1 + vat.getValue()) * (1 + surcharge) * (1 - discount / 100.);
  }

  public static double calculateUnroundedArticleNetPrice(Article article, boolean preordered) {
    return article.getNetPrice()
        * (preordered && article.isWeighable()
            ? getSafeAmount(article) * article.getMetricUnits().getBaseFactor()
            : 1.0);
  }

  public static double calculateArticleNetPrice(Article article, boolean preordered) {
    return Tools.roundCurrency(calculateUnroundedArticleNetPrice(article, preordered));
  }

  public static double calculateArticleRetailPrice(
      Article article, double discount, boolean preordered) {
    return Tools.roundCurrency(
        calculateRetailPrice(
            calculateUnroundedArticleNetPrice(article, preordered),
            article.getVat(),
            calculateSurcharge(article, preordered),
            discount,
            preordered));
  }

  public static String getShortBarcode(Article article) {
    Long barcode = article.getBarcode();
    if (barcode == null) {
      return "";
    }
    String barcodeString = Long.toString(barcode);
    return barcodeString.substring(Math.max(barcodeString.length() - 4, 0));
  }

  public static Collection<Article> getPrintPool() {
    return QueryBuilder.select(ArticlePrintPool_.article).distinct().getResultList();
  }

  public static int getArticlePrintPoolSize() {
    return QueryBuilder.select(ArticlePrintPool.class, sum(ArticlePrintPool_.number))
        .getSingleResultOptional()
        .map(tuple -> tuple.get(0, Integer.class))
        .orElse(0);
  }

  public static Collection<Article> getAllActiveArticlesFromPriceList(
      EntityManager em, PriceList priceList) {
    if (!Setting.INVENTORY_LOOK_FOR_ARTICLE_USAGE.getBooleanValue()) {
      return priceList.getAllArticles(em);
    }
    Instant expireDate =
        Instant.now().minus(Setting.INVENTORY_INACTIVE_ARTICLE.getIntValue(), ChronoUnit.DAYS);
    List<Integer> articleIds =
        QueryBuilder.select(ShoppingItem_.articleId)
            .where(greaterOrEq(ShoppingItem_.createDate, asExpression(expireDate)))
            .distinct()
            .getResultStream(em)
            .map(Long::intValue)
            .toList();
    return QueryBuilder.selectAll(Article.class)
        .where(Article_.priceList.eq(priceList), Article_.id.in(articleIds))
        .getResultList(em);
  }

  public static Article getCustomArticleVersion(Consumer<Article> transformer) {
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup("commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    return Access.runWithAccessManager(
        AccessManager.ACCESS_GRANTED,
        () -> {
          Article article =
              DBConnection.getConditioned(
                      em,
                      Article.class,
                      Article_.kbNumber.eq(ArticleConstants.CUSTOM_PRODUCT.getUniqueIdentifier()))
                  .stream()
                  .findFirst()
                  .orElseGet(ArticleRepository::createCustomArticlePreset);
          Access.runWithAccessManager(
              AccessManager.ACCESS_GRANTED, () -> transformer.accept(article));
          em.persist(article);
          return article;
        });
  }

  private static Article createCustomArticlePreset() {
    Article out = new Article();
    out.setKbNumber(ArticleConstants.CUSTOM_PRODUCT.getUniqueIdentifier());
    out.setSupplier(Supplier.getCustomProductSupplier());
    out.setSurchargeGroup(out.getSupplier().getOrPersistDefaultSurchargeGroup());
    out.setSuppliersItemNumber(ArticleConstants.CUSTOM_PRODUCT.getUniqueIdentifier());
    return out;
  }

  public static int getSafeAmount(Article article) {
    int amount = article.getAmount();
    if (amount == 0) {
      return 1;
    }
    return Math.abs(amount);
  }

  public static MetricUnits getMultiplierUnit(Article article) {
    if (article.isWeighable()) {
      return article.getMetricUnits();
    } else {
      return MetricUnits.PIECE;
    }
  }

  public static MetricUnits getContainerUnits(Article article) {
    MetricUnits unit = article.getMetricUnits();
    if (!article.isWeighable() && unit != MetricUnits.NONE) {
      return MetricUnits.PIECE;
    }
    return unit;
  }

  public static MetricUnits getPriceUnit(Article article) {
    if (article.isWeighable()) {
      return article.getMetricUnits().getDisplayUnit();
    } else {
      return MetricUnits.PIECE;
    }
  }

  public static String getPieceAmount(Article article) {
    return String.format("%,1d", getSafeAmount(article)) + article.getMetricUnits().getShortName();
  }

  public static PriceList getValidPriceList(EntityManager em, Article article) {
    PriceList priceList = article.getPriceList();
    if (!priceList.getName().equals("Verdeckte Aufnahme")) return priceList;
    return nextArticleTo(em, article.getSuppliersItemNumber(), article.getSupplier(), priceList)
        .getPriceList();
  }

  public static boolean validateBarcode(Long barcode) {
    return barcode > 1E9;
  }

  public static Map<String, CatalogEntry> getArticleCatalogMap(
      Collection<Article> articles, Supplier supplier) {
    Collection<String> articleNos =
        articles.stream()
            .filter(e -> e.getSupplier().equals(supplier))
            .mapToInt(Article::getSuppliersItemNumber)
            .mapToObj(Integer::toString)
            .collect(Collectors.toList());
    return QueryBuilder.selectAll(CatalogEntry.class)
        .where(
            CatalogEntry_.aktionspreis.eq(false),
            CatalogEntry_.artikelNr.in(articleNos),
            or(
                CatalogEntry_.ladeneinheit.isNull().not(),
                CatalogEntry_.gebindePfand.eq(0.0).not(),
                CatalogEntry_.einzelPfand.eq(0.0).not()))
        .getResultList()
        .stream()
        .collect(Collectors.toMap(CatalogEntry::getArtikelNr, c -> c));
  }

  public static List<ArticleComparedToCatalogEntry> compareArticlesToCatalog(
      Collection<Article> articles, Supplier supplier) {
    List<ArticleComparedToCatalogEntry> differences = new ArrayList<>();
    Map<String, CatalogEntry> catalogMap = getArticleCatalogMap(articles, supplier);
    for (Article article : articles) {
      if (!article.getSupplier().equals(supplier)) {
        continue;
      }
      CatalogEntry correspondingCatalogEntry =
          catalogMap.get(Integer.toString(article.getSuppliersItemNumber()));
      if (correspondingCatalogEntry == null) {
        continue;
      }
      ArticleComparedToCatalogEntry compared =
          new ArticleComparedToCatalogEntry(article, correspondingCatalogEntry);
      String description = "";
      switch (compared.getResultType()) {
        case BARCODE_CHANGED:
          description = "Barcode geändert";
          break;
        case BARCODE_CONFLICT_SAME_SUPPLIER:
          description = "Barcode veraltet";
          break;
        case BARCODE_CONFLICT_OTHER_SUPPLIER:
          description = "Barcode (Lieferanten-Konflikt)";
          break;
      }
      Set<String> fieldDifferences = compared.getFieldDifferences();
      if (!description.isEmpty()) {
        fieldDifferences.remove("Barcode");
        description += ", ";
      }
      compared.setDescription(description + String.join(", ", fieldDifferences));
      differences.add(compared);
    }
    return differences;
  }

  public static List<String> mergeCatalog(
      Collection<Article> articles, List<ArticleComparedToCatalogEntry> differences) {
    List<String> mergeLog = new ArrayList<>();
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    EntityTransaction et = em.getTransaction();
    et.begin();

    try {
      String logBuilder;
      for (ArticleComparedToCatalogEntry difference : differences) {
        logBuilder = "";
        Article article = difference.getArticle();
        if (!articles.contains(article)) {
          continue;
        }

        CatalogEntry catalogEntry = difference.getCatalogEntry();
        ArticleCatalogState resultType = difference.getResultType();

        logBuilder += "Artikel " + article.getSuppliersItemNumber() + ":";
        long a_barcode = Tools.ifNull(article.getBarcode(), -999L);
        long c_barcode = Tools.ifNull(catalogEntry.getEanLadenEinheit(), -999L);
        double singleDeposit = catalogEntry.getEinzelPfand();
        double containerDeposit = catalogEntry.getGebindePfand();
        boolean changed = false;
        Article conflictingArticle;
        switch (resultType) {
          case EQUAL:
            break;
          case DIFFERENT:
            if (singleDeposit > 0.0) {
              logBuilder +=
                  String.format(
                      " E.-Pf. %.2f -> %.2f |", article.getSingleDeposit(), singleDeposit);
              article.setSingleDeposit(singleDeposit);
              changed = true;
            }
            if (containerDeposit > 0.0) {
              logBuilder +=
                  String.format(
                      " G.-Pf. %.2f -> %.2f |", article.getContainerDeposit(), containerDeposit);
              article.setContainerDeposit(containerDeposit);
              changed = true;
            }
            if (validateBarcode(c_barcode) && c_barcode != a_barcode) {
              logBuilder += String.format(" bc %s -> %s |", article.getBarcode(), c_barcode);
              article.setBarcode(c_barcode);
              changed = true;
            }
            break;
          case BARCODE_CHANGED:
            logBuilder +=
                " Neuer Barcode. Möglicherweise ist die Lieferanten-Artikelnummer neu vergeben worden?";
            break;
          case BARCODE_CONFLICT_SAME_SUPPLIER:
            conflictingArticle = difference.getConflictingArticle();
            logBuilder +=
                String.format(
                    " Derselbe Barcode,ist bereits bei dem %s-Artikel mit der KB-Artikelnummer %d vergeben. Ist einer der Artikel veraltet?",
                    conflictingArticle.getSupplier().getShortName(),
                    conflictingArticle.getKbNumber());
            break;
          case BARCODE_CONFLICT_OTHER_SUPPLIER:
            conflictingArticle = difference.getConflictingArticle();
            logBuilder +=
                String.format(
                    " Derselbe Barcode,ist bereits bei dem %s-Artikel mit der KB-Artikelnummer %d vergeben. Der Barcode sollte nur bei einem Lieferanten vergeben sein.",
                    conflictingArticle.getSupplier().getShortName(),
                    conflictingArticle.getKbNumber());
        }
        if (changed) {
          em.merge(article);
        }
        log.info(logBuilder);
        mergeLog.add(logBuilder);
      }

      em.flush();
      et.commit();
      return mergeLog;

    } catch (Exception e) {
      UnexpectedExceptionHandler.showUnexpectedErrorWarning(e);
      // TODO() code below this will never get called ^ throws
      et.rollback();
      log.error("Rolled back changes due to an exception");
      mergeLog.add(
          "Übernahme wurde mit Fehlern abgebrochen. Alle Änderungen wurden Rückgängig gemacht");
      return mergeLog;
    }
  }

  public static List<Article> getArticlesStateAtDate(Date date, List<Integer> articleIds) {
    @Cleanup EntityManager em = DBConnection.getEntityManager();

    if (articleIds.isEmpty()) {
      return Collections.emptyList();
    }
    AuditReader reader = AuditReaderFactory.get(em);
    Number revision = reader.getRevisionNumberForDate(date);
    AuditQuery query = reader.createQuery().forEntitiesAtRevision(Article.class, revision);
    query = query.add(AuditEntity.property("id").in(articleIds));

    return query.getResultList();
  }

  public static Article getArticleStateAtDate(Article article, Instant instant) {
    Date date = Date.from(instant);
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup(value = "commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    return AuditReaderFactory.get(em).find(Article.class, article.getId(), date);
  }

  public static Map<ArticleDeletionResult, List<Article>> prepareRemoval(
      Collection<Article> articles) {

    Map<ArticleDeletionResult, List<Article>> result = new HashMap<>();
    result.put(PREORDERED, new ArrayList<>());
    result.put(RECENTLY_TRADED, new ArrayList<>());
    result.put(RECENT_INVENTORY, new ArrayList<>());
    result.put(DISCONTINUE, new ArrayList<>());
    result.put(DELETE, new ArrayList<>());

    @Cleanup EntityManager em = DBConnection.getEntityManager();

    Collection<String> preorderedSupplierNumbers =
        QueryBuilder.select(PreOrder_.catalogEntry.child(CatalogEntry_.artikelNr))
            .where(
                and(
                    PreOrder_.delivery.isNull(),
                    PreOrder_.catalogEntry
                        .child(CatalogEntry_.artikelNr)
                        .in(
                            articles.stream()
                                .filter(a -> a.getSupplier().equals(KK_SUPPLIER))
                                .map(a -> Integer.toString(a.getSuppliersItemNumber()))
                                .toList())))
            .getResultList();

    Map<Integer, LocalDate> articleNumbersLastInventory =
        QueryBuilder.selectAll(ArticleStock.class)
            .where(ArticleStock_.article.in(articles))
            .getResultStream(em)
            .collect(
                Collectors.toMap(
                    s -> s.getArticle().getKbNumber(),
                    ArticleStock::getInventoryDate,
                    (s1, s2) -> s1.isBefore(s2) ? s2 : s1));

    em.clear();

    Instant inactivityThreshold =
        Instant.now().minus(Setting.INVENTORY_INACTIVE_ARTICLE.getIntValue(), ChronoUnit.DAYS);
    Collection<Integer> recentlyTradedArticleNumbers =
        QueryBuilder.select(ShoppingItem_.kbNumber)
            .where(
                and(
                    ShoppingItem_.kbNumber.in(
                        articles.stream().map(Article::getKbNumber).toList())),
                or(
                    greaterOrEq(ShoppingItem_.createDate, asExpression(inactivityThreshold)),
                    greaterOrEq(
                        ShoppingItem_.purchase.child(Purchase_.createDate),
                        asExpression(inactivityThreshold))))
            .getResultList();

    @Cleanup(value = "commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    for (Article article : articles) {
      ArticleDeletionResult deletionResult;
      Integer kbNumber = article.getKbNumber();
      LocalDate lastInventory = articleNumbersLastInventory.get(kbNumber);
      if (preorderedSupplierNumbers.contains(Integer.toString(article.getSuppliersItemNumber()))) {
        deletionResult = PREORDERED;
      } else if (recentlyTradedArticleNumbers.contains(kbNumber)) {
        deletionResult = RECENTLY_TRADED;
      } else if (lastInventory != null) {
        if (lastInventory.isBefore(
            LocalDate.ofInstant(inactivityThreshold, ZoneId.systemDefault()))) {
          deletionResult = DISCONTINUE;
        } else {
          deletionResult = RECENT_INVENTORY;
        }
      } else {
        deletionResult = DELETE;
      }
      result.get(deletionResult).add(article);
    }
    return result;
  }

  private static void cleanupArticleReferences(EntityManager em, Collection<Article> articles) {

    QueryBuilder.selectAll(Shelf.class)
        .getResultStream(em)
        .forEach(
            s -> {
              Shelf dbShelf = em.find(Shelf.class, s.getId());
              articles.forEach(dbShelf.getArticles()::remove);
              em.merge(dbShelf);
            });

    em.createQuery("DELETE FROM ArticlePrintPool p WHERE p.article in (:a)")
        .setParameter("a", articles)
        .executeUpdate();
  }

  public static void unlistArticles(EntityManager em, Collection<Article> articles) {

    EntityTransaction et = em.getTransaction();

    et.begin();
    cleanupArticleReferences(em, articles);
    et.commit();

    et.begin();
    for (Article a : articles) {
      Article dbArticle = em.find(Article.class, a.getId());
      if (a.getKbNumber() >= 0) {
        int newNumber = -100000 - a.getKbNumber();
        while (getByKbNumber(newNumber, false).isPresent()) {
          newNumber -= 10000;
        }
        dbArticle.setKbNumber(newNumber);
        dbArticle.setSuppliersItemNumber(newNumber);
      }
      dbArticle.setPriceList(null);
      dbArticle.setBarcode(null);
      dbArticle.setSurchargeGroup(UNLISTED_GROUP);
      dbArticle.setShopRange(DISCONTINUED);
      em.merge(dbArticle);
    }
    et.commit();
  }

  public static void removeArticles(EntityManager em, Collection<Article> articles) {
    EntityTransaction et = em.getTransaction();

    et.begin();
    cleanupArticleReferences(em, articles);
    et.commit();

    et.begin();
    em.createQuery("DELETE FROM PreOrder p WHERE p.article in (:a) AND p.delivery IS NOT NULL")
        .setParameter("a", articles)
        .executeUpdate();
    em.createQuery("DELETE FROM Article a WHERE a in (:a)")
        .setParameter("a", articles)
        .executeUpdate();
    et.commit();
  }

  public static void setSurchargeGroup(
      Collection<Article> articles, SurchargeGroup surchargeGroup) {
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup(value = "commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    for (Article a : articles) {
      Article dbArticle = em.find(Article.class, a.getId());
      if (dbArticle != null) {
        dbArticle.setSurchargeGroup(surchargeGroup);
        em.persist(dbArticle);
      }
    }
  }

  public static @NotNull Article createArticleFromLineContent(
      LineContent content, boolean ignoreBarcode, ShopRange shopRange) {
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup("commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    Supplier kkSupplier = KK_SUPPLIER;
    Article pattern = ArticleRepository.nextArticleTo(em, content.getKkNumber(), kkSupplier);
    Article article = new Article();
    article.setSupplier(kkSupplier);
    article.setName(content.getName());
    article.setNetPrice(content.getPriceKb());
    article.setMetricUnits(content.getUnit());
    article.setAmount(content.getAmount());
    article.setProducer(content.getProducer());
    if (!ignoreBarcode) article.setBarcode(content.getBarcode());
    article.setWeighable(content.isWeighableKb());
    article.setContainerSize(content.getContainerSize());
    article.setShopRange(shopRange);
    article.setSurchargeGroup(pattern.getSurchargeGroup());
    VAT vat = content.getVat();
    if (vat == null) {
      vat = pattern.getVat();
    }
    article.setVat(vat);
    article.setPriceList(ArticleRepository.getValidPriceList(em, pattern));
    article.setVerified(false);
    article.setKbNumber(ArticleRepository.nextFreeKBNumber(em));
    article.setSuppliersItemNumber(content.getKkNumber());
    article.setSingleDeposit(content.getSingleDeposit());
    article.setContainerDeposit(content.getContainerDeposit());
    em.persist(article);
    em.flush();
    return article;
  }

  public static @NotNull Article createArticleFromCatalogEntry(CatalogEntry entry) {
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup("commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    Supplier kkSupplier = KK_SUPPLIER;
    Article pattern = ArticleRepository.nextArticleTo(em, entry.getArtikelNrInt(), kkSupplier);
    Article article = new Article();
    article.setSupplier(kkSupplier);
    article.setName(entry.getBezeichnung());
    article.setNetPrice(entry.getPreis());
    article.setMetricUnits(entry.getMetricUnits());
    article.setAmount(entry.getAmountAsInt());
    article.setProducer(entry.getMarke());
    article.setBarcode(entry.getEanLadenEinheit());
    article.setWeighable(entry.getGewichtsartikel());
    article.setContainerSize(entry.getBestelleinheitsMenge());
    article.setSurchargeGroup(pattern.getSurchargeGroup());
    article.setVat(entry.getMwstKennung());
    article.setPriceList(ArticleRepository.getValidPriceList(em, pattern));
    article.setVerified(false);
    article.setKbNumber(ArticleRepository.nextFreeKBNumber(em));
    article.setSuppliersItemNumber(entry.getArtikelNrInt());
    article.setSingleDeposit(entry.getEinzelPfand());
    article.setContainerDeposit(entry.getGebindePfand());
    article.setShopRange(ShopRange.IN_RANGE);
    return article;
  }

  public static Optional<Article> getArticleFromCatalogEntry(CatalogEntry entry) {
    return QueryBuilder.selectAll(Article.class)
        .where(
            Article_.suppliersItemNumber.eq(entry.getArtikelNr()),
            Article_.supplier.eq(KK_SUPPLIER))
        .getSingleResultOptional();
  }

  private static Integer parseOrNull(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public static List<Integer> suppliersItemNumbersFromCatalog() {
    List<String> artikelNrStrings =
        QueryBuilder.select(CatalogEntry_.artikelNr)
            .where(CatalogEntry_.aenderungskennung.in("X", "V").not())
            .getResultList();
    List<Integer> result = new ArrayList<>(artikelNrStrings.size());
    for (String s : artikelNrStrings) {
      Integer i = parseOrNull(s);
      if (i != null) result.add(i);
    }
    return result;
  }

  public static Map<Integer, Boolean> kkItemNumberOffersFromArticles() {
    Map<Integer, Boolean> result = new HashMap<>();
    QueryBuilder.selectAll(Article.class)
        .where(Article_.supplier.eq(KK_SUPPLIER))
        .getResultList()
        .forEach(a -> result.put(a.getSuppliersItemNumber(), a.isOffer()));
    return result;
  }

  /**
   * Compares the article in the database with the supply line without changing anything. Price,
   * single deposit and container size changes are only applied after the user confirmed them, see
   * {@link #applyArticleChange(ArticleChangeRow)}.
   */
  public static List<ArticleChangeRow> findPendingArticleChanges(
      Supplier kkSupplier, LineContent content) {
    List<ArticleChangeRow> result = new ArrayList<>();
    Optional<Article> articleInDb = getBySuppliersItemNumber(kkSupplier, content.getKkNumber());
    if (articleInDb.isEmpty()) {
      return result;
    }
    Article article = articleInDb.get();
    double newPrice = content.getPriceKb();
    double newSingleDeposit = content.getSingleDeposit();
    double newContainerSize = content.getContainerSize();
    // Aktionsartikel behalten ihren Preis, der Lieferpreis wird nicht übernommen
    if (!article.isOffer() && Math.abs(article.getNetPrice() - newPrice) >= 0.01) {
      result.add(
          new ArticleChangeRow(ArticleChange.PRICE(article.getNetPrice(), newPrice), article));
    }
    if (newSingleDeposit > 0 && article.getSingleDeposit() != newSingleDeposit) {
      result.add(
          new ArticleChangeRow(
              ArticleChange.SINGLE_DEPOSIT(article.getSingleDeposit(), newSingleDeposit), article));
    }
    if (!article.isWeighable() && article.getContainerSize() != newContainerSize) {
      result.add(
          new ArticleChangeRow(
              ArticleChange.CONTAINER_SIZE(article.getContainerSize(), newContainerSize), article));
    }
    return result;
  }

  public static void applyArticleChange(ArticleChangeRow row) {
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup("commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    Article article = em.find(Article.class, row.article().getId());
    ArticleChange change = row.change();
    switch (change.getType()) {
      case ArticleChange.PRICE -> article.setNetPrice(change.getNewValue());
      case ArticleChange.SINGLE_DEPOSIT -> article.setSingleDeposit(change.getNewValue());
      case ArticleChange.CONTAINER_SIZE -> article.setContainerSize(change.getNewValue());
      default -> throw new IllegalArgumentException("unsupported change: " + change.log());
    }
    em.merge(article);
    log.info("Article [" + article.getSuppliersItemNumber() + "]: " + change.log());
  }

  public static Article updateOrCreateArticleFromLineContent(
      Supplier kkSupplier, LineContent content, boolean noBarcode) {
    Optional<Article> articleInDb = getBySuppliersItemNumber(kkSupplier, content.getKkNumber());
    ShopRange shopRange =
        (content.getContainerMultiplier() - Tools.ifNull(content.getUserPreorderCount(), 0) > 0
            ? ShopRange.PERMANENT_RANGE
            : ShopRange.IN_RANGE);
    if (articleInDb.isPresent()) {
      boolean dirty = false;
      @Cleanup EntityManager em = DBConnection.getEntityManager();
      @Cleanup("commit")
      EntityTransaction et = em.getTransaction();
      et.begin();
      Article article = em.find(Article.class, articleInDb.map(Article::getId).get());
      boolean newWeighable = content.isWeighableKb();
      String logInfo = "Article [" + article.getSuppliersItemNumber() + "]:";
      ShopRange articleShopRange = article.getShopRange();
      if (!articleShopRange.equals(ShopRange.PERMANENT_RANGE)) {
        if (!articleShopRange.equals(shopRange)) {
          article.setShopRange(shopRange);
          dirty = true;
          logInfo += " updated shop range [%s] -".formatted(article.getShopRange().toString());
        }
      }
      // price, single deposit and container size changes are applied after confirmation,
      // see findPendingArticleChanges and applyArticleChange
      if (article.isWeighable() != newWeighable) {
        dirty = true;
        logInfo += " weighable change [%b -> %b] -".formatted(article.isWeighable(), newWeighable);
        article.setWeighable(newWeighable);
      }
      // TODO roll back work around for weird catalog deposits
      /* if (article.getContainerDeposit() != newContainerDeposit) {
        dirty = true;
        change =
            ArticleChange.CONTAINER_DEPOSIT(article.getContainerDeposit(), newContainerDeposit);
        logInfo += change.log();
        article.setContainerDeposit(newContainerDeposit);
      }
      TODO up to here
       */
      if (dirty) {
        em.merge(article);
        log.info(logInfo.substring(0, logInfo.length() - 2));
      }
      return article;
    }
    return createArticleFromLineContent(content, noBarcode, shopRange);
  }
}
