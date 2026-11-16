package kernbeisser.Windows.CashierShoppingMask;

import java.util.List;
import kernbeisser.Config.Config;
import kernbeisser.DBEntities.Repositories.TransactionRepository;
import kernbeisser.DBEntities.SaleSession;
import kernbeisser.DBEntities.Transaction;
import kernbeisser.DBEntities.User;
import kernbeisser.Enums.SaleSessionType;
import kernbeisser.Exeptions.NoTransactionsFoundException;
import kernbeisser.Exeptions.handler.UnexpectedExceptionHandler;
import kernbeisser.Reports.AccountingReport;
import kernbeisser.Windows.LogIn.LogInModel;
import kernbeisser.Windows.MVC.IModel;
import lombok.Data;

@Data
public class CashierShoppingMaskModel implements IModel<CashierShoppingMaskController> {
  private boolean openLock = false;

  private static void markTransactionsAsReported(
      List<Transaction> transactions, String fileName, long no) {
    if (!transactions.isEmpty()) {
      if (Config.getConfig()
          .getReports()
          .getCloudOutputDirectory()
          .toPath()
          .resolve(fileName + ".pdf")
          .toFile()
          .exists()) {
        TransactionRepository.writeAccountingReportNo(transactions, no);
      }
    }
  }

  public static int printAccountingReports() {
    List<Transaction> unreportedTransactions = TransactionRepository.getUnreportedTransactions();
    if (unreportedTransactions.stream().noneMatch(TransactionRepository::isPurchase)) {
      return 0;
    }
    try {
      long no = TransactionRepository.getLastReportNo() + 1;
      AccountingReport accountingReport = AccountingReport.latest(no, unreportedTransactions, true);
      accountingReport.exportPdfToCloudAndThen(
          "Erstelle Buchhaltungsbericht",
          UnexpectedExceptionHandler::showUnexpectedErrorWarning,
          () ->
              markTransactionsAsReported(
                  unreportedTransactions, accountingReport.getSafeOutFileName(), no));
      return 0;
    } catch (NoTransactionsFoundException e) {
      return 0;
    } catch (Exception e) {
      return unreportedTransactions.size();
    }
  }

  public SaleSession createSaleSession(
      SaleSessionType saleSessionType, User customer, User secondSeller) {
    SaleSession saleSession = new SaleSession(saleSessionType);
    saleSession.setCustomer(customer);
    saleSession.setSeller(LogInModel.getLoggedIn());
    if (!secondSeller.toString().equals("Keiner")) {
      saleSession.setSecondSeller(secondSeller);
    }
    return saleSession;
  }
}
