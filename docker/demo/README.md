# Demo database

`kernbeisser-demo.sql.gz` contains fictional demo data in German (MariaDB dump without `CREATE DATABASE`, so
it can be imported into a database of any name). Load it with `make db-demo`; every user's
password is `password`. See the main README for the users and their roles.

Changes compared with the original demo dump (`kernbeisser_demo`, 2024-07-31, DB version
`HIBERNATE_6_ID_SEQUENCE`):

- Updated to DB version `CATALOG_ACTIONPRICE_DEFAULT` by the app's own migrations
  (`REMOVE_TABLE_OFFER`, `ADD_SHARED_CONTAINER_TRANSACTION`, `CATALOG_ACTIONPRICE_DEFAULT`).
- Dropped `Article.printPool` and `Article_AUD.printPool`: removed from the entity in 5ead41fa, but
  the column was `NOT NULL` without a default, so creating an article failed.
- Dropped tables that no entity uses anymore: `CatalogDataSource`, `Inventory`, `InventoryState`,
  `hibernate_sequence`, `Offer_SEQ`.
- Moved every `*_SEQ` sequence past the highest id of its table (e.g. `Transaction_SEQ` was at 51
  with ids up to 575), so new rows don't get existing ids.
- Set all passwords to `password` (bcrypt, cost 12).
- Replaced the suppliers, articles and the wholesale catalog, which came from real data, with
  generated ones (`generate-demo-articles.py`, see below). The history tables (`*_AUD`) were
  rebuilt to match, with a single revision.

## Generated articles and suppliers

`generate-demo-articles.py` prints SQL that replaces suppliers, articles and the wholesale catalog
with fictional data, adds purchases and leaves users and permissions as they are. It can be applied
again to its own output:

- 12 regional suppliers with invented names, addresses (`Musterdorf`), phone numbers and
  `.example` e-mail addresses. The suppliers the application looks up by short name (wholesaler
  `KK`, `AWB`, `AWO`, `PF`, `SoZ`, `CP`, …) are kept; the wholesaler is renamed to
  "Naturkost-Großhandel Nord".
- About 1,900 wholesaler articles in the existing price lists and surcharge groups, and a wholesale
  catalog of 3,500 entries (the articles plus entries without an article). About 5% of the
  articles differ in price from the catalog, as after a price change.
- About 260 articles of the regional suppliers.
- Deposits and purchases for the members, with shopping items from these articles: most users
  shop in the 75 days before the newest purchase, `haile.s`, `shirin.e`, `marie.j` and `laura.r`
  only more than 220 days before, so they are inactive. Balances are recomputed from all
  transactions.

`shift-dates.sql` moves all transaction and purchase dates so that the newest purchase is from an
hour ago; `make db-demo` runs it after the import. Without it the demo users would all become
inactive at the next start, because the dates in the dump get older.
- Barcodes start with 2, the EAN prefix reserved for in-store use, so they never match a real
  product.

The output is the same on every run (fixed random seed). To regenerate the data, apply it to a
database with the demo data and dump that database, on a MariaDB server with case-sensitive table
names (the dev database lowercases them):

```sh
python3 docker/demo/generate-demo-articles.py | mariadb -u root -p kernbeisser
```

Table names keep their original case, so the dump also works on servers with case-sensitive table
names (`lower_case_table_names=0`, the Linux default).
