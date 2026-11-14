package kernbeisser.Windows.CatalogImport;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kernbeisser.DBConnection.DBConnection;
import kernbeisser.DBConnection.QueryBuilder;
import kernbeisser.DBEntities.Article;
import kernbeisser.DBEntities.CatalogEntry;
import kernbeisser.DBEntities.Purchase_;
import kernbeisser.DBEntities.Repositories.ArticleRepository;
import kernbeisser.DBEntities.ShoppingItem_;
import kernbeisser.Enums.Setting;
import kernbeisser.Exeptions.CatalogImportErrorException;
import kernbeisser.Exeptions.CatalogImportWarningException;
import kernbeisser.Exeptions.UnknownFileFormatException;
import kernbeisser.Tasks.Catalog.CatalogImportError;
import kernbeisser.Tasks.Catalog.CatalogImporter;
import kernbeisser.Useful.Date;
import kernbeisser.Windows.MVC.IModel;
import lombok.Cleanup;
import lombok.Getter;

public class CatalogImportModel implements IModel<CatalogImportController> {
  @Getter private CatalogImporter catalogImporter;
  @Getter private Instant lastCatalogCreationDate;
  @Getter private Instant lastCatalogValidDate;

  public CatalogImportModel() {
    refreshLastCatalogInfo();
  }

  public List<CatalogImportError> readCatalog(Path bnnFile) throws UnknownFileFormatException {
    catalogImporter = new CatalogImporter(bnnFile);
    return catalogImporter.getReadErrors();
  }

  static Instant tryParseInstant(String s, Instant defaultDate) {
    try {
      return Date.parseInstantDate(s, Date.INSTANT_CATALOG_DATE);
    } catch (DateTimeParseException e) {
      return defaultDate;
    }
  }

  public void refreshLastCatalogInfo() {
    Instant defaultDate = Instant.parse("2000-01-01T00:00:00Z");
    String[] lastCataloginfo =
        Setting.INFO_LINE_LAST_CATALOG.getStringValue().split(CatalogImporter.DELIMITER);
    if (lastCataloginfo.length > 8) {
      lastCatalogCreationDate = tryParseInstant(lastCataloginfo[9], defaultDate);
      lastCatalogValidDate = tryParseInstant(lastCataloginfo[8], defaultDate);
    } else {
      lastCatalogCreationDate = defaultDate;
      lastCatalogValidDate = defaultDate;
    }
  }

  private boolean checkImport(CatalogEntry source, CatalogEntry target)
      throws CatalogImportErrorException, CatalogImportWarningException {
    String sourceUpdateType = source.getAenderungskennung();
    String targetUpdateType = "ZZ";
    Instant sourceUpdateDate = source.getAenderungsDatum();
    Instant targetUpdateDate = Instant.MIN;
    String sourceDesignation = source.getBezeichnung();
    boolean exists = target != null;
    if (exists) {
      if (source.equals(target)) {
        return false;
      }
      targetUpdateType = target.getAenderungskennung();
      targetUpdateDate = target.getAenderungsDatum();
    }
    if ("X;V".contains(sourceUpdateType)) {
      if (!exists) {
        throw new CatalogImportErrorException(
            "Der Artikel wurde übersprungen, weil er nicht mehr gelistet ist");
      }
      if (!sourceUpdateType.equals(targetUpdateType)) {
        throw new CatalogImportWarningException(
            String.format(
                "Der Artikel \"%1s\" ist %2s nicht mehr gelistet",
                sourceDesignation, (sourceUpdateType.equals("V") ? "vorübergehend " : "")));
      }
    }
    Instant sourceActionValidTo = source.getAktionspreisGueltigBis();
    if (sourceActionValidTo != null && sourceActionValidTo.isBefore(Instant.now())) {
      throw new CatalogImportErrorException(
          "\"Der Aktions-Artikel wurde übersprungen, weil das Angebot bereits abgelaufen ist");
    }
    String sourceArticleNo = source.getArtikelNr();
    if (sourceArticleNo == null) {
      throw new CatalogImportErrorException(
          String.format(
              "Der Artikel \"%s\" wurde übersprungen, weil er keine Artikelnummer hat",
              sourceDesignation));
    }
    if (sourceArticleNo.length() == 6 && sourceArticleNo.startsWith("7")) {
      throw new CatalogImportErrorException(
          String.format(
              "Der Artikel \"%1s\" wurde übersprungen, weil es sich um Frischware handelt",
              sourceDesignation));
    }
    if (!targetUpdateDate.isBefore(sourceUpdateDate)) {
      throw new CatalogImportErrorException(
          "Der Artikel wurde übersprungen, weil er nicht aktueller ist, als der vorhandene");
    }
    return true;
  }

  public void clearCatalog() {
    CatalogEntry.clearCatalog();
  }

  public boolean isCompleteCatalog() {
    return catalogImporter.getScope().equals("V");
  }

  public List<CatalogImportError> applyChanges() {
    Map<String, CatalogEntry> existingCatalog = new HashMap<>();
    for (CatalogEntry e : CatalogEntry.getCatalog()) {
      existingCatalog.put(e.getUXString(), e);
    }

    List<CatalogImportError> importErrors = new ArrayList<>();
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup(value = "commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    for (CatalogEntry source : catalogImporter.getCatalog()) {
      CatalogEntry existing = existingCatalog.get(source.getUXString());
      if (existing != null) {
        source.setId(existing.getId());
      }
      try {
        if (checkImport(source, existing)) {
          em.merge(source);
        }
      } catch (CatalogImportWarningException | CatalogImportErrorException e) {
        importErrors.add(new CatalogImportError(source.getArtikelNr(), e));
        if (e.getClass().equals(CatalogImportWarningException.class)) {
          em.merge(source);
        }
      }
    }
    Setting.INFO_LINE_LAST_CATALOG.changeValue(catalogImporter.getInfoLine());
    refreshLastCatalogInfo();
    return importErrors;
  }

  List<CatalogEntry> getCatalogEntriesByDeposit(String artikelNr) {
    return catalogImporter.getCatalog().stream()
        .filter(
            e ->
                e.getPfandNrBestelleinheit().equals(artikelNr)
                    || e.getPfandNrLadeneinheit().equals(artikelNr))
        .toList();
  }

  Optional<Article> getKkArticle(CatalogEntry entry) {
    try {
      return ArticleRepository.getByKkItemNumber(entry.getArtikelNrInt());
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** Dates of all purchases containing the article. */
  List<Instant> getSaleDates(Article article) {
    return QueryBuilder.select(ShoppingItem_.purchase.child(Purchase_.createDate))
        .where(ShoppingItem_.articleId.eq((long) article.getId()))
        .getResultList();
  }
}
