package kernbeisser;

import java.awt.*;
import java.io.*;
import java.net.URISyntaxException;
import java.util.Locale;
import javax.swing.*;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import kernbeisser.Config.Config;
import kernbeisser.CustomComponents.SafeFileChooserUI;
import kernbeisser.DBConnection.DBConnection;
import kernbeisser.DBEntities.*;
import kernbeisser.Enums.Setting;
import kernbeisser.Enums.Theme;
import kernbeisser.StartUp.SplashScreenHandler;
import kernbeisser.Useful.UiTools;
import kernbeisser.VersionIntegrationTools.Version;
import kernbeisser.Windows.LogIn.SimpleLogIn.SimpleLogInController;
import kernbeisser.Windows.TabbedPane.TabbedPaneModel;
import lombok.extern.log4j.Log4j2;
import rs.groump.Access;
import rs.groump.Agent;

@Log4j2
public class Main {

  /**
   * sets the Look and Feel to Windows standard, sets the Image path, checks all needed Tables and
   * PriceLists and as least shows the LogIn Window
   */
  private static void logUncaughtException(Thread t, Throwable e) {
    log.error(e.toString(), e);
  }

  public static void main(String[] args) throws UnsupportedLookAndFeelException {
    if (!Agent.agentInitialized) {
      System.out.println("cannot run, security.jar is not linked as a java-agent");
      System.exit(-1);
    }
    Thread.setDefaultUncaughtExceptionHandler(Main::logUncaughtException);
    Config.safeFile(args);
    Locale.setDefault(Locale.GERMAN);
    log.info("Free memory at start " + Runtime.getRuntime().freeMemory() / 1048576 + "MB");
    // Runs the jar with more memory if not enough is reserved
    checkRequiredMemory(args);
    SplashScreenHandler splashScreenHandler =
        new SplashScreenHandler(140, 345, 14, Color.DARK_GRAY);
    splashScreenHandler.setSplashComment("Stelle Verbindung zur Datenbank her");
    DBConnection.getEntityManager();
    splashScreenHandler.setSplashComment("Initialisiere UI");
    buildEnvironment();
    splashScreenHandler.setSplashComment("Prüfe Datenbankversion");
    checkVersion();
    SwingUtilities.invokeLater(() -> openLogIn());
  }

  public static String getPath() {
    try {
      return Main.class
          .getProtectionDomain()
          .getCodeSource()
          .getLocation()
          .toURI()
          .getPath()
          .replace('/', File.separator.charAt(0))
          .substring(1);
    } catch (URISyntaxException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  public static void checkRequiredMemory(String[] args) {
    if (args.length == 0 && Runtime.getRuntime().maxMemory() / 1024 / 1024 < 980) {
      restart("-Xmx1024m");
    }
  }

  public static void restart(String arg) {
    try {
      Main.log.info("Restarting Jar...");
      Process proc =
          Runtime.getRuntime()
              .exec("java " + (arg == null ? "" : arg) + " -jar " + getPath() + " restart");

      BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));

      BufferedReader stdError = new BufferedReader(new InputStreamReader(proc.getErrorStream()));

      String s;
      while ((s = stdInput.readLine()) != null) {
        System.out.println(s);
      }

      while ((s = stdError.readLine()) != null) {
        System.out.println(s);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    System.exit(-1);
  }

  public static void checkVersion() {
    Version.checkAndUpdateVersion(log);
  }

  public static void buildEnvironment() throws UnsupportedLookAndFeelException {
    Access.runUnchecked(
        () -> {
          log.info("setting look and feel");
          setSettingLAF();
          SafeFileChooserUI.install();
          log.info("register FontAwesome");
          IconFontSwing.register(FontAwesome.getIconFont());
          try {
            UiTools.scaleFonts(Float.parseFloat(Setting.LABEL_SCALE_FACTOR.getStringValue()));
          } catch (NumberFormatException ignored) {
          }
        });
  }

  public static void setSettingLAF() throws UnsupportedLookAndFeelException {
    UIManager.setLookAndFeel(Setting.DEFAULT_THEME.getEnumValue(Theme.class).getLookAndFeel());
  }

  private static void openLogIn() {
    ((Frame) TabbedPaneModel.getMainPanel().getContainer())
        .setTitle(Setting.STORE_NAME.getStringValue() + " (Login)");
    new SimpleLogInController().openTab();
  }
}
