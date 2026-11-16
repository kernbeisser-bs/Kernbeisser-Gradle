package kernbeisser.CustomComponents;

import com.formdev.flatlaf.ui.FlatFileChooserUI;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import javax.swing.filechooser.FileView;
import javax.swing.plaf.ComponentUI;
import lombok.extern.log4j.Log4j2;

/**
 * FlatLaf file chooser UI that tolerates missing Windows system icons.
 *
 * <p>On Windows the JDK's {@code Win32ShellFolder2} throws a {@link NullPointerException} when
 * Windows returns no icon for a folder or drive (e.g. a disconnected network or removable drive).
 * FlatLaf asks for these icons whenever the file chooser is laid out or its folder combo box is
 * opened, so every file dialog fails with an exception. This UI falls back to the look and feel's
 * default file and folder icons in that case.
 *
 * <p>Registered for all file choosers via {@link #install()}.
 */
@Log4j2
public class SafeFileChooserUI extends FlatFileChooserUI {

  private FileView safeFileView;

  public SafeFileChooserUI(JFileChooser fileChooser) {
    super(fileChooser);
  }

  public static ComponentUI createUI(JComponent c) {
    return new SafeFileChooserUI((JFileChooser) c);
  }

  /**
   * Makes all {@link JFileChooser}s use this UI. The value is stored in the developer defaults of
   * the {@link UIManager}, so it stays active when the look and feel is changed later on.
   */
  public static void install() {
    UIManager.put("FileChooserUI", SafeFileChooserUI.class.getName());
  }

  @Override
  public FileView getFileView(JFileChooser fc) {
    if (safeFileView == null) {
      safeFileView = new SafeFileView(super.getFileView(fc));
    }
    return safeFileView;
  }

  @Override
  public void clearIconCache() {
    super.clearIconCache();
    if (safeFileView instanceof SafeFileView view) {
      view.fallbackIcons.clear();
    }
  }

  private static class SafeFileView extends FileView {

    private final FileView delegate;
    private final Map<File, Icon> fallbackIcons = new HashMap<>();

    private SafeFileView(FileView delegate) {
      this.delegate = delegate;
    }

    @Override
    public Icon getIcon(File f) {
      Icon fallback = fallbackIcons.get(f);
      if (fallback != null) {
        return fallback;
      }
      try {
        return delegate.getIcon(f);
      } catch (NullPointerException e) {
        log.debug("no system icon available for {}, using default icon", f, e);
        fallback = defaultIcon(f);
        fallbackIcons.put(f, fallback);
        return fallback;
      }
    }

    private Icon defaultIcon(File f) {
      boolean directory = f != null && f.isDirectory();
      return UIManager.getIcon(directory ? "FileView.directoryIcon" : "FileView.fileIcon");
    }

    @Override
    public String getName(File f) {
      return delegate.getName(f);
    }

    @Override
    public String getDescription(File f) {
      return delegate.getDescription(f);
    }

    @Override
    public String getTypeDescription(File f) {
      return delegate.getTypeDescription(f);
    }

    @Override
    public Boolean isTraversable(File f) {
      return delegate.isTraversable(f);
    }
  }
}
