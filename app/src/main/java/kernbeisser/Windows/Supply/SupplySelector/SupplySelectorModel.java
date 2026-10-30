package kernbeisser.Windows.Supply.SupplySelector;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import kernbeisser.DBEntities.Article;
import kernbeisser.DBEntities.ShoppingItem;
import kernbeisser.Windows.MVC.IModel;
import lombok.Getter;

public class SupplySelectorModel implements IModel<SupplySelectorController> {

  @Getter private final BiConsumer<Supply, Collection<ShoppingItem>> consumer;

  public SupplySelectorModel(BiConsumer<Supply, Collection<ShoppingItem>> consumer) {
    this.consumer = consumer;
  }
}
