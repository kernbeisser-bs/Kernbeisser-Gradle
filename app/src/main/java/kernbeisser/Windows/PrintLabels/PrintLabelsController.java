package kernbeisser.Windows.PrintLabels;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import kernbeisser.CustomComponents.BarcodeCapture;
import kernbeisser.CustomComponents.ObjectTable.Columns.Columns;
import kernbeisser.CustomComponents.ObjectTable.ObjectTable;
import kernbeisser.DBEntities.Article;
import kernbeisser.DBEntities.ArticlePrintPool;
import kernbeisser.DBEntities.Repositories.ArticleRepository;
import kernbeisser.Enums.Setting;
import kernbeisser.Useful.Tools;
import kernbeisser.Windows.CollectionView.CollectionController;
import kernbeisser.Windows.CollectionView.CollectionView;
import kernbeisser.Windows.MVC.Controller;
import kernbeisser.Windows.MVC.Linked;
import kernbeisser.Windows.ViewContainer;
import kernbeisser.Windows.ViewContainers.SubWindow;
import rs.groump.AccessDeniedException;
import rs.groump.Key;
import rs.groump.PermissionKey;

public class PrintLabelsController extends Controller<PrintLabelsView, PrintLabelsModel> {

  @Linked private final CollectionController<Article> articles;

  private final JButton printButton = new JButton();

  private final JLabel printSheetInfo = new JLabel();
  private final BarcodeCapture barcodeCapture;

  @Key(PermissionKey.ACTION_OPEN_PRINT_LABELS)
  public PrintLabelsController() throws AccessDeniedException {
    super(new PrintLabelsModel());
    model.setPrintPoolBefore(ArticlePrintPool.getPrintPoolAsMap());
    articles = PrintLabelsModel.getArticleSource();
    barcodeCapture = new BarcodeCapture((s) -> getView().processBarcode(s));
  }

  private void editPrintPool(Article article) {
    Integer newAmount =
        Tools.integerInputDialog(getView().getContent(), model.getPrintPool(article));
    if (newAmount == null) {
      return;
    }
    model.setPrintPool(article, newAmount);
    getView().refreshChosenArticle(article);
    refreshPrintButton();
  }

  public static void setLabelPrintText(JButton button) {
    boolean pendingLabels = ArticleRepository.getArticlePrintPoolSize() > 0;
    button.setText("Etiketten drucken" + (pendingLabels ? " *" : ""));
  }

  public static void refreshLaunchButton(JButton launchButton) {
    Executors.newSingleThreadScheduledExecutor()
        .schedule(() -> setLabelPrintText(launchButton), 1000, TimeUnit.MILLISECONDS);
  }

  public static JButton getLaunchButton(
      ViewContainer targetComponent, Runnable preparePrintAction) {
    JButton launchButton =
        new JButton() {
          @Override
          public void setEnabled(boolean b) {
            super.setEnabled(b);
          }
        };
    launchButton.setIcon(IconFontSwing.buildIcon(FontAwesome.PRINT, 20, Color.BLUE));
    setLabelPrintText(launchButton);
    launchButton.setToolTipText("Öffnet das Fenster für den Etikettendruck");
    launchButton.addActionListener(
        e -> {
          preparePrintAction.run();
          new PrintLabelsController()
              .withCloseEvent(() -> refreshLaunchButton(launchButton))
              .openIn(new SubWindow(targetComponent));
        });
    return launchButton;
  }

  public static JButton getLaunchButton(ViewContainer targetComponent) {
    return getLaunchButton(targetComponent, () -> {});
  }

  public void refreshPrintButton() {
    int labelsPerPage = Setting.LABELS_PER_PAGE.getIntValue();
    long labelCount = model.getArticlePrintPoolSize();
    long pages = (labelCount - 1) / labelsPerPage + 1;
    long emptyLabels = pages * labelsPerPage - labelCount;
    String infoText =
        labelCount == 0
            ? "Keine Etiketten zu drucken"
            : pages
                + " Seite"
                + (pages == 1 ? " wird" : "n werden")
                + " benötigt, es ist noch Platz für "
                + emptyLabels
                + " Etikett"
                + (emptyLabels == 1 ? "" : "en");
    printButton.setEnabled(labelCount > 0);
    printButton.setText("Ausdruck starten (" + labelCount + ")");
    printSheetInfo.setFont(
        printSheetInfo.getFont().deriveFont(labelCount > 0 ? Font.BOLD : Font.PLAIN));
    printSheetInfo.setText(infoText);
  }

  private void print() {
    int threshold = Setting.LABEL_COUNT_WARNING_THRESHOLD.getIntValue();
    Map<Article, Integer> manyLabels = model.getArticlesWithManyLabels(articles, threshold);
    if (!manyLabels.isEmpty() && !getView().confirmManyLabels(manyLabels, threshold)) return;
    model.print(articles);
    if (getView().confirmPrintSuccess()) {
      articles.selectAllChosen();
      persistPrintPoolIfNecessary(false);
    }
  }

  private void persistPrintPoolIfNecessary(boolean confirm) {
    Map<Article, Integer> newPrintPool = model.getPrintPoolMap();
    if (newPrintPool.equals(model.getPrintPoolMapBefore())) return;
    if (confirm && !(articles.getModel().isSaveChanges() || getView().confirmChanges())) return;
    ArticlePrintPool.setPrintPoolFromMap(newPrintPool);
    model.setPrintPoolBefore(newPrintPool);
  }

  private void onObjectsSelected(Collection<Article> articles, Boolean chosen) {
    articles.forEach(e -> model.setPrintPool(e, chosen ? 1 : 0));
  }

  @Override
  public void fillView(PrintLabelsView printLabelsView) {
    articles.addCollectionModifiedListener(this::refreshPrintButton);
    articles.addObjectsListener(this::onObjectsSelected);
    printButton.setIcon(IconFontSwing.buildIcon(FontAwesome.PRINT, 20, Color.BLUE));
    printButton.addActionListener((e) -> print());
    articles
        .getView()
        .getChosen()
        .addColumnAtIndex(
            0,
            Columns.create("Anzahl", model::getPrintPool)
                .withLeftClickConsumer(this::editPrintPool));
    ObjectTable<Article> available = articles.getView().getAvailable();
    available.addColumnAtIndex(
        0,
        Columns.create("Lieferant", PrintLabelsModel::getArticleSupplierName).withDefaultFilter());
    available.addColumn(Columns.create("Barcode", Article::getBarcode).withDefaultFilter());
    available.addColumn(Columns.create("Preisliste", Article::getPriceList).withDefaultFilter());
    articles.addControls(printButton, printSheetInfo);
    articles.setLoadedAndSource(ArticleRepository.getPrintPool(), PrintLabelsModel::getAllArticles);
    articles.getView().addSearchbox(CollectionView.BOTH);
  }

  @Override
  protected void closed() {
    persistPrintPoolIfNecessary(true);
  }

  @Override
  protected boolean processKeyboardInput(KeyEvent e) {
    return barcodeCapture.processKeyEvent(e);
  }
}
