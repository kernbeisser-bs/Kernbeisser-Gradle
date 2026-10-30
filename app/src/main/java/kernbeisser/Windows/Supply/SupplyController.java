package kernbeisser.Windows.Supply;

import jakarta.persistence.NoResultException;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;
import kernbeisser.CustomComponents.BarcodeCapture;
import kernbeisser.CustomComponents.KeyCapture;
import kernbeisser.DBEntities.*;
import kernbeisser.DBEntities.Repositories.ArticleRepository;
import kernbeisser.Enums.*;
import kernbeisser.Forms.ObjectForm.Exceptions.CannotParseException;
import kernbeisser.Useful.Tools;
import kernbeisser.Windows.MVC.Controller;
import kernbeisser.Windows.PrintLabels.PrintLabelsController;
import kernbeisser.Windows.PrintLabels.PrintLabelsModel;
import kernbeisser.Windows.Supply.SupplySelector.LineContent;
import kernbeisser.Windows.Supply.SupplySelector.ResolveStatus;
import kernbeisser.Windows.Supply.SupplySelector.SupplySelectorController;
import kernbeisser.Windows.ViewContainers.SubWindow;
import rs.groump.Key;
import rs.groump.PermissionKey;

public class SupplyController extends Controller<SupplyView, SupplyModel> {
  public final KeyCapture keyCapture;
  public final BarcodeCapture barcodeCapture;

  @Key(PermissionKey.ACTION_OPEN_SUPPLY)
  public SupplyController() {
    super(new SupplyModel());
    model.setPrintPoolBefore(ArticlePrintPool.getPrintPoolAsMap());
    keyCapture = new KeyCapture();
    barcodeCapture = new BarcodeCapture(this::processBarcode);
  }

  public void editPrintPool(ShoppingItem item) {
    Integer newValue =
        Tools.integerInputDialog(getView().getContent(), model.getPrintNumber(item), i -> i >= 0);
    if (newValue == null) {
      return;
    }
    model.setPrintNumber(item, newValue);
    getView().refreshRow(item);
  }

  public void editItemMultiplier(ShoppingItem item) {
    Integer newValue =
        Tools.integerInputDialog(
            getView().getContent(), -(int) Math.round(item.getContainerCount()));
    if (newValue == null) {
      return;
    }
    model.setContainerMultiplier(item, newValue);
    model.setPrintNumber(item, model.calculatePrintNumberFromItem(item));
    getView().refreshRow(item);
  }

  private void preparePrint() {
    model.print();
    getView().repaintTable();
  }

  @Override
  public void fillView(SupplyView supplyView) {
    var view = getView();
    view.setSuppliers(model.getAllSuppliers());
    keyCapture.addF2ToF8NumberActions(getView()::setAmount);
  }

  void searchShoppingItem(Supplier supplier, int supNr) {
    if (supNr == 0) {
      getView().noArticleFound();
      return;
    }
    try {
      getView()
          .getObjectForm()
          .setSource(
              model.findBySuppliersItemNumber(supplier, supNr).orElseThrow(NoResultException::new));
      getView().setAddAvailable(true);
    } catch (NoResultException noResultException) {
      getView().noArticleFound();
    }
  }

  public void processBarcode(String s) {
    try {
      getView().getObjectForm().setSource(PrintLabelsModel.getByBarcode(s));
      getView().addItem();
    } catch (NoResultException e) {
      Tools.noArticleFoundForBarcodeWarning(getView().getContent(), s);
    }
  }

  private void checkInput() throws NoResultException, CannotParseException {
    if (!model.articleExists(getView().getSelected(), getView().getSuppliersItemNumber())) {
      throw new NoResultException();
    }
  }

  public void addItem(double amount) throws CannotParseException, NoResultException {
    checkInput();
    Article article = getView().getObjectForm().getData(null);
    if (!article.equals(getView().getObjectForm().getOriginal())) {
      if (!getView().confirmChanges()) {
        getView().getObjectForm().setSource(getView().getObjectForm().getOriginal());
        return;
      }
      getView().getObjectForm().setShowSuccessDialog(false);
      getView().getObjectForm().applyMode(Mode.EDIT);
    }
    ShoppingItem item = new ShoppingItem(article, 0, false);
    model.setContainerMultiplier(item, amount);
    model.addShoppingItem(item);
    setPrintNumber(item);
    getView().noArticleFound();
    recalculateTotal();
    getView().setShoppingItems(model.getShoppingItems());
  }

  void commit() {
    model.commit(getView()::messageSupplyExistsInDB);
    if (!model.getShoppingItems().isEmpty()) {
      model.print();
      new PrintLabelsController().openIn(new SubWindow(getView().traceViewContainer()));
    }
    model.clearShoppingItems();
    recalculateTotal();
    getView().back();
  }

  @Override
  protected boolean commitClose() {
    if (model.isPrintSelected() && getView().shouldPrintLabels()) model.print();
    return model.getShoppingItems().isEmpty() || getView().commitClose();
  }

  public void remove(ShoppingItem selectedObject) {
    model.removeShoppingItem(selectedObject);
    recalculateTotal();
  }

  public void recalculateTotal() {
    double items =
        model.getShoppingItems().stream()
            .mapToDouble(
                e ->
                    Math.abs(
                        e.getItemNetPrice()
                            * (e.isWeighAble()
                                ? (e.getItemMultiplier() / 1000.)
                                : e.getItemMultiplier())))
            .sum();
    double produce = model.getAppendedProducePrice();
    getView().setTotal(produce + items);
    getView().setProduce(produce);
  }

  public void setPrintNumber(ShoppingItem item) {
    model.setPrintNumber(item, model.calculatePrintNumberFromItem(item));
    getView().repaintTable();
  }

  public void increaseItemPrintNumber(ShoppingItem item) {
    model.setPrintNumber(item, model.getPrintNumber(item) + 1);
    getView().refreshRow(item);
  }

  public int getPrintNumber(ShoppingItem item) {
    return model.getPrintNumber(item);
  }

  public boolean isOffer(ShoppingItem item) {
    return item.getArticleNow().map(Article::isOffer).orElse(false);
  }

  public void openImportSupplyFile() {
    Dimension viewSize = getView().getSize();
    Dimension size =
        new Dimension(
            (int) viewSize.getWidth(),
            (int) (viewSize.getHeight() * Setting.SUBWINDOW_SIZE_FACTOR.getFloatValue()));
    new SupplySelectorController(
            (supply, shoppingItems) -> {
              for (ShoppingItem item : shoppingItems) {
                model.addShoppingItem(item);
                setPrintNumber(item);
              }
              getView().setShoppingItems(model.getShoppingItems());
              getModel().setSupply(supply);
              getModel()
                  .setAppendedProducePrice(
                      supply.getAllLineContents().stream()
                          .filter(e -> e.getStatus() == ResolveStatus.PRODUCE)
                          .mapToDouble(LineContent::getTotalPrice)
                          .sum());
              recalculateTotal();
            })
        .openIn(new SubWindow(getView().traceViewContainer()).withSize(size));
  }

  public static boolean shouldBecomeShoppingItem(LineContent content) {
    return !(content.getStatus() == ResolveStatus.IGNORE
        || content.getStatus() == ResolveStatus.PRODUCE);
  }

  public static ShoppingItem createShoppingItem(
      Supplier kkSupplier,
      LineContent content,
      int orderNo,
      boolean ignoreBarcode) {
    ShoppingItem shoppingItem =
        new ShoppingItem(
            ArticleRepository.updateOrCreateArticleFromLineContent(
                kkSupplier, content, ignoreBarcode),
            0,
            false);
    double rawItemMultiplier =
        (shoppingItem.isWeighAble()
                ? getAsItemMultiplierAmount(content)
                : content.getContainerMultiplier() * content.getContainerSize())
            * -1;
    SupplyModel.checkFractionalItemMultiplier(rawItemMultiplier, content.getKkNumber());
    shoppingItem.setItemMultiplier((int) Math.round(rawItemMultiplier));
    shoppingItem.setOrderNo(orderNo);
    return shoppingItem;
  }

  private static double getAsItemMultiplierAmount(LineContent content) {
    return content
        .getUnit()
        .inUnit(
            MetricUnits.GRAM,
            content.getContainerMultiplier() * content.getContainerSize() * content.getAmount());
  }

  @Override
  protected boolean processKeyboardInput(KeyEvent e) {
    return keyCapture.processKeyEvent(e) || barcodeCapture.processKeyEvent(e);
  }

  public int getPreorderCount(ShoppingItem t) {
    return model.getPreorderCount(t.getSuppliersItemNumber());
  }
}
