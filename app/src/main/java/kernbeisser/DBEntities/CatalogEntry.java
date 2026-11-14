package kernbeisser.DBEntities;

import static kernbeisser.DBConnection.PredicateFactory.between;

import com.google.common.collect.ImmutableMap;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kernbeisser.DBConnection.DBConnection;
import kernbeisser.DBConnection.PredicateFactory;
import kernbeisser.DBConnection.QueryBuilder;
import kernbeisser.DBEntities.Converters.CatalogUnitParser;
import kernbeisser.Enums.MetricUnits;
import kernbeisser.Enums.VAT;
import kernbeisser.Exeptions.handler.UnexpectedExceptionHandler;
import kernbeisser.Tasks.Catalog.BoolValues;
import kernbeisser.Useful.ActuallyCloneable;
import lombok.*;
import org.apache.commons.lang3.StringUtils;

@Data
@Table(
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UX_artikelNr_aktionspreis",
          columnNames = {"artikelNr", "aktionspreis"})
    })
@Entity
@Setter(AccessLevel.NONE)
@Getter(AccessLevel.PUBLIC)
public class CatalogEntry implements ActuallyCloneable {
  private String artikelNr;

  // CatalogChange.identifier
  private String aenderungskennung;
  private Instant aenderungsDatum;
  private Instant aenderungsZeit;
  private Long eanLadenEinheit;
  private Long eanBestellEinheit;
  private String bezeichnung;
  private String bezeichnung2;
  private String bezeichnung3;

  // roman Number
  private String handelsklasse;

  // BNN-Markenkürzel
  private String marke;

  // deprecated Herstellerkürzel
  private String hersteller;

  // car plate country code
  private String herkunft;

  // according to BNN-IK list
  private String qualitaet;
  private String kontrollstelle;

  // days
  private Integer mHDRestlaufzeit;

  // wg* trade group properties
  private Integer wgBnn;
  private Integer wgIfh;
  private Integer wgGh;

  // is delivered if article is not on stock
  private String ersatzArtikelNr;
  private Double minBestellMenge;
  private String bestelleinheit;
  private Double bestelleinheitsMenge;
  private String ladeneinheit;
  private Double mengenfaktor;

  @BoolValues(trueValue = "J", falseValue = "N")
  private Boolean gewichtsartikel;

  private String pfandNrLadeneinheit;
  private String pfandNrBestelleinheit;
  private Double gewichtLadeneinheit;
  private Double gewichtBestelleinheit;

  // size in cm
  private Integer breite;
  private Integer hoehe;
  private Integer tiefe;
  private VAT mwstKennung;
  private Double vkFestpreis;
  private Double empfVk;
  private Double empfVkGH;
  private Double preis;

  @BoolValues(trueValue = "J", falseValue = "N")
  private Boolean rabattfaehig;

  @BoolValues(trueValue = "J", falseValue = "N")
  private Boolean skontierfaehig;

  private Double staffelMenge1;
  private Double staffelPreis1;

  @BoolValues(trueValue = "J", falseValue = "N")
  private Boolean rabattfaehig1;

  @BoolValues(trueValue = "J", falseValue = "N")
  private Boolean skontierfaehig1;

  private Double staffelMenge2;
  private Double staffelPreis2;

  @BoolValues(trueValue = "J", falseValue = "N")
  private Boolean rabattfaehig2;

  @BoolValues(trueValue = "J", falseValue = "N")
  private Boolean skontierfaehig2;

  private Double staffelMenge3;
  private Double staffelPreis3;

  @BoolValues(trueValue = "J", falseValue = "N")
  private Boolean rabattfaehig3;

  @BoolValues(trueValue = "J", falseValue = "N")
  private Boolean skontierfaehig3;

  private Double staffelMenge4;
  private Double staffelPreis4;

  @BoolValues(trueValue = "J", falseValue = "N")
  private Boolean rabattfaehig4;

  @BoolValues(trueValue = "J", falseValue = "N")
  private Boolean skontierfaehig4;

  private Double staffelMenge5;
  private Double staffelPreis5;

  @BoolValues(trueValue = "J", falseValue = "N")
  private Boolean rabattfaehig5;

  @BoolValues(trueValue = "J", falseValue = "N")
  private Boolean skontierfaehig5;

  private String artikelart;

  @BoolValues(trueValue = "A")
  private Boolean aktionspreis = false;

  private Instant aktionspreisGueltigAb;
  private Instant aktionspreisGueltigBis;
  private Double empfVkAktion;

  // should be but isn't in bnn
  // excluded from equals: older imports could not parse this field and left it empty, so
  // comparing it would treat every unchanged catalog entry as modified on re-import
  @EqualsAndHashCode.Exclude private MetricUnits grundpreisEinheit;
  private Double grundpreisFaktor;
  private Instant lieferbarAb;
  private Instant lieferbarBis;
  private String artikelBioId;
  private Integer artikelVariant;
  private String markenId;
  private String herstellerId;
  @Setter private Instant katalogGueltigBis;
  @Setter private double einzelPfand = 0.0;
  @Setter private double gebindePfand = 0.0;

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  @Setter(AccessLevel.PUBLIC)
  private long id;

  public static final ImmutableMap<String, String> CATALOG_ENTRY_STATES =
      ImmutableMap.<String, String>builder()
          .put("A", "Änderung")
          .put("N", "Neu")
          .put("R", "Restbestand")
          .put("V", "Vorübergehend ausgelistet")
          .put("W", "Wiedergelistet")
          .put("X", "Ausgelistet")
          .build();

  public CatalogEntry() {}

  public boolean isActive() {
    return !"|X|V|".contains(aenderungskennung);
  }

  public int getArtikelNrInt() throws NumberFormatException {
    return Integer.parseInt(artikelNr);
  }

  public String getUXString() {
    String result = artikelNr;
    if (aktionspreis) result += "A";
    return result;
  }

  public boolean isOutdatedOffer() {
    if (aktionspreisGueltigBis == null) {
      return false;
    }
    return aktionspreisGueltigBis.isBefore(Instant.now());
  }

  public boolean isOffer() {
    return aktionspreis;
  }

  public static List<CatalogEntry> getByArticleNo(
      String articleNo, boolean withOffers, boolean withInactive) {
    var queryBuilder =
        QueryBuilder.selectAll(CatalogEntry.class).where(CatalogEntry_.artikelNr.eq(articleNo));
    if (!withInactive) {
      queryBuilder.where(CatalogEntry_.aenderungskennung.in("V", "X").not());
    }
    if (withOffers) {
      queryBuilder
          .where(
              PredicateFactory.or(
                  CatalogEntry_.aktionspreis.eq(false),
                  between(
                      Instant.now(),
                      CatalogEntry_.aktionspreisGueltigAb,
                      CatalogEntry_.aktionspreisGueltigBis)))
          .orderBy(CatalogEntry_.aktionspreis.desc());
    } else {
      queryBuilder.where(CatalogEntry_.aktionspreis.eq(false));
    }
    return queryBuilder.getResultList();
  }

  public static List<CatalogEntry> getByArticleNo(String ArticleNo) {
    return getByArticleNo(ArticleNo, true, true);
  }

  public static Optional<CatalogEntry> getByBarcode(String barcode) {
    return QueryBuilder.selectAll(CatalogEntry.class)
        .where(CatalogEntry_.eanLadenEinheit.eq(barcode))
        .getSingleResultOptional();
  }

  public static List<CatalogEntry> getCatalog() {
    return QueryBuilder.selectAll(CatalogEntry.class).getResultList();
  }

  public static void clearCatalog() {
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup(value = "commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    em.createQuery(em.getCriteriaBuilder().createCriteriaDelete(CatalogEntry.class))
        .executeUpdate();
  }

  public boolean matches(String s) {
    return StringUtils.containsIgnoreCase(bezeichnung, s)
        || artikelNr.startsWith(s)
        || Objects.toString(eanLadenEinheit).endsWith(s);
  }

  public String getInfo() {
    return String.join("\n", bezeichnung2, bezeichnung3);
  }

  public MetricUnits getMetricUnits() {
    return new CatalogUnitParser(ladeneinheit).getMetricUnits();
  }

  public Double getAmount() {
    Double result =
        new CatalogUnitParser(
                gewichtsartikel != null && gewichtsartikel ? bestelleinheit : ladeneinheit)
            .getAmount();
    if (result == null) {
      result = new CatalogUnitParser(bestelleinheit).getAmount();
    }
    return result;
  }

  public Integer getAmountAsInt() {
    Double amount = getAmount();
    if (amount == null) {
      return null;
    }
    return (int) Math.round((getAmount()));
  }

  @Override
  public CatalogEntry clone() {
    try {
      return (CatalogEntry) super.clone();
    } catch (CloneNotSupportedException e) {
      throw UnexpectedExceptionHandler.showUnexpectedErrorWarning(e);
    }
  }
}
