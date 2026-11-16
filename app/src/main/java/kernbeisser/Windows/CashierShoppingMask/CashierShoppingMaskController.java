package kernbeisser.Windows.CashierShoppingMask;

import jakarta.persistence.NoResultException;
import java.util.List;
import java.util.stream.Collectors;
import kernbeisser.CustomComponents.ObjectTable.Columns.Columns;
import kernbeisser.CustomComponents.SearchBox.Filters.UserFilter;
import kernbeisser.CustomComponents.SearchBox.SearchBoxController;
import kernbeisser.CustomComponents.SearchBox.SearchBoxView;
import kernbeisser.DBEntities.Post;
import kernbeisser.DBEntities.SaleSession;
import kernbeisser.DBEntities.User;
import kernbeisser.Enums.*;
import kernbeisser.Exeptions.NoSelectionException;
import kernbeisser.Exeptions.NotEnoughCreditException;
import kernbeisser.Useful.Users;
import kernbeisser.Windows.LogIn.LogInModel;
import kernbeisser.Windows.MVC.Controller;
import kernbeisser.Windows.MVC.Linked;
import kernbeisser.Windows.PostPanel.PostPanelController;
import kernbeisser.Windows.ShoppingMask.ShoppingMaskController;
import kernbeisser.Windows.UserInfo.UserInfoController;
import kernbeisser.Windows.ViewContainers.SubWindow;
import org.jetbrains.annotations.NotNull;
import rs.groump.Key;
import rs.groump.PermissionKey;

public class CashierShoppingMaskController
    extends Controller<CashierShoppingMaskView, CashierShoppingMaskModel> {

  @Linked private final SearchBoxController<User> searchBoxController;
  private final UserFilter userFilter =
      new UserFilter(this::changeFilter, UserFilter.FILTER_ACTIVE);

  private final PostContext POST_ON_CLOSE = PostContext.ON_SALE_SESSION_CLOSE;

  private List<User> searchable(String s, int max) {
    return userFilter.searchable(s, max).stream()
        .filter(u -> !u.isTestOnly())
        .collect(Collectors.toList());
  }

  @Key(PermissionKey.ACTION_OPEN_CASHIER_SHOPPING_MASK)
  public CashierShoppingMaskController() {
    super(new CashierShoppingMaskModel());
    this.searchBoxController =
        new SearchBoxController<>(
            this::searchable,
            Columns.create("Vorname", User::getFirstName),
            Columns.create("Nachname", User::getSurname),
            Columns.create("Benutzername", User::getUsername),
            Columns.create("Mitgliedschaft", Users::getMembership));
    searchBoxController.addLostSelectionListener(() -> selectUser(null));
    searchBoxController.addSelectionListener(this::selectUser);
    searchBoxController.addDoubleClickListener(e -> openMaskWindow());
    searchBoxController.addExtraComponents(userFilter.createFilterUIComponents());
  }

  public void changeFilter() {
    searchBoxController.invokeSearch();
  }

  private void selectUser(User tableSelection) {
    CashierShoppingMaskView view = getView();
    if (tableSelection != null) {
      view.setOpenShoppingMaskEnabled(!model.isOpenLock());
      view.setUserInfoEnabled(true);
      view.setStartFor(tableSelection.getFirstName(), tableSelection.getSurname());
    } else {
      view.setOpenShoppingMaskEnabled(false);
      view.setUserInfoEnabled(false);
    }
  }

  public void openMaskWindow() {
    try {
      if (model.isOpenLock()) {
        getView().messageShoppingMaskAlreadyOpened();
        return;
      }
      boolean allowMultiple =
          UserSetting.ALLOW_MULTIPLE_SHOPPING_MASK_INSTANCES.getBooleanValue(
              LogInModel.getLoggedIn());
      SaleSession saleSession =
          model.createSaleSession(
              SaleSessionType.ASSISTED,
              searchBoxController.getSelectedObject().get(),
              getView().getSecondSeller());
      if (!allowMultiple) {
        new ShoppingMaskController(saleSession).withCloseEvent(() -> setAllowOpen(true)).openTab();
        setAllowOpen(false);
      } else new ShoppingMaskController(saleSession).openTab();
    } catch (NotEnoughCreditException e) {
      getView().notEnoughCredit();
    } catch (NoResultException ignored) {
    }
  }

  public void openUserInfo() throws NoSelectionException {
    new UserInfoController(
            searchBoxController.getSelectedObject().orElseThrow(NoSelectionException::new))
        .openIn(new SubWindow(getView().traceViewContainer()));
  }

  private void setAllowOpen(boolean v) {
    model.setOpenLock(!v);
    getView().setOpenShoppingMaskEnabled(v);
  }

  public void close() {
    CashierShoppingMaskView view = getView();
    if (Post.getByContext(POST_ON_CLOSE).getActive()) {
      openPostOnClose();
    }
    view.back();
  }

  @Override
  protected boolean commitClose() {
    if (!getView().commitClose()) return false;
    handleResult(CashierShoppingMaskModel.printAccountingReports());
    return true;
  }

  private void handleResult(int unreportedTransactions) {
    if (unreportedTransactions > 0) {
      if (unreportedTransactions > 40) {
        getView().messageDoPanic(unreportedTransactions);
      } else {
        getView().messageDontPanic();
      }
    }
  }

  public void openPostOnClose() {
    new PostPanelController(POST_ON_CLOSE).openIn(new SubWindow(getView().traceViewContainer()));
  }

  public SearchBoxView<User> getSearchBoxView() {
    return searchBoxController.getView();
  }

  @Override
  public @NotNull CashierShoppingMaskModel getModel() {
    return model;
  }

  @Override
  public void fillView(CashierShoppingMaskView cashierShoppingMaskView) {
    cashierShoppingMaskView.setAllSecondarySellers(User.getAllUserFullNames(false, false));
    cashierShoppingMaskView.setEditPostVisible(Post.getByContext(POST_ON_CLOSE).isWriteable());
  }
}
