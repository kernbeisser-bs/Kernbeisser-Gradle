package kernbeisser.Windows.Supply.SupplySelector;

import kernbeisser.DBEntities.Article;

/** A pending change of one article, shown to the user before it is applied. */
public record ArticleChangeRow(ArticleChange change, Article article) {}
