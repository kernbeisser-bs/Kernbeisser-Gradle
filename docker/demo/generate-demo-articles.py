#!/usr/bin/env python3
"""Replaces suppliers, articles and the wholesale catalog of the demo database with fictional data
and adds purchases, so most users are active and some inactive.

Prints SQL to stdout; apply it to a database that contains the demo dump (it can be applied again
to its own output):

    python3 docker/demo/generate-demo-articles.py | mariadb kernbeisser

Keeps the suppliers and articles the application relies on (short names KK, AWB, AWO, AWK, PF,
SoZ, CP, UNB and the articles with negative kbNumber) and the users, permissions and
transactions. The output is deterministic (fixed random seed).
"""

import math
import random
from datetime import datetime, timedelta

rnd = random.Random(20241031)
NOW = "2024-07-31 12:00:00"
NOW_MILLIS = 1722420000000

# Suppliers the code looks up by short name (kernbeisser.DBEntities.Supplier) or that only mark a
# kind of article. KK is the wholesaler whose catalog is imported.
SYSTEM_SUPPLIERS = ("KK", "AWB", "AWO", "AWK", "PF", "SoZ", "CP", "UNB")
WHOLESALER_NAME = "Naturkost-Großhandel Nord"

KEEPERS = ["Marigona Uka", "Luis Morago", "Haile Selassi", "Christoph Schott", "Ricken Patel",
           "Marie Curie", "Rosa Luxemburg", "Shirin Ebadi", "Wangari Maathai", "Emma Ruby-Sachs"]

# (name, short name, default surcharge, categories)
SUPPLIERS = [
    ("Hof Mooswiesen", "MOO", 0.13, ["eggs", "veg_box"]),
    ("Imkerei Lindenblüte", "IML", 0.18, ["honey"]),
    ("Käserei am Bergbach", "KBB", 0.28, ["cheese_local"]),
    ("Mühle Haselbach", "MHB", 0.18, ["flour_local"]),
    ("Weingut Sonnenhang", "WSH", 0.33, ["wine"]),
    ("Fleischerei Kleeblatt", "FKL", 0.28, ["meat_local"]),
    ("Ölmühle Rapsfeld", "ORF", 0.18, ["oil_local"]),
    ("Rösterei Bohnenglück", "RBG", 0.23, ["coffee_local"]),
    ("Weltladen-Import Fairbund", "FWI", 0.23, ["fair"]),
    ("Seifensiederei Birkengrund", "SSB", 0.28, ["soap"]),
    ("Obsthof Apfelgarten", "OAG", 0.18, ["juice_local"]),
    ("Schäferei Heidegrund", "SHG", 0.28, ["lamb"]),
]

# Price lists and wholesaler surcharge groups named after real companies get generic names.
PRICE_LIST_RENAMES = {
    58: "Hoffleisch", 92: "Fleisch + Wurst regional", 107: "Wein vom Großhandel",
    108: "Wurst regional (andere Lieferanten)", 147: "Lamm aus der Region",
}
SURCHARGE_GROUP_RENAMES = {
    398: "Bio-Brauerei Lindenhain", 399: "Brauhaus am Mühlbach", 400: "Klosterbrauerei Sankt Gerold",
    401: "Hafenbrauerei Nordkamp", 417: "Ayurvedische Tees",
    433: "Frische Fertigprodukte vom Biohof",
}

G, KG, L, ML, PCS = 0, 1, 2, 3, 4  # kernbeisser.Enums.MetricUnits ordinals
LOW, HIGH = 0, 1  # kernbeisser.Enums.VAT ordinals

# key: (price list, wholesaler surcharge group, vat, unit, amounts, net price range,
#       container sizes, deposit, names, variants)
CATEGORIES = {
    "pasta": ("Nudeln", "Pastaprodukte", LOW, G, [250, 500, 1000], (0.9, 3.2), [6, 12], 0,
              ["Spaghetti", "Penne", "Fusilli", "Fusilli", "Lasagneplatten", "Spirelli", "Tagliatelle",
               "Hörnchen", "Farfalle", "Makkaroni", "Bandnudeln", "Suppennudeln"],
              ["Hartweizen", "Dinkel", "Dinkel Vollkorn", "Vollkorn", "Linsen", "Kichererbsen",
               "Emmer", "Hartweizen hell"]),
    "cereal": ("Müsli, Cornflakes & Co", "Müsli", LOW, G, [375, 500, 750, 1000], (1.6, 5.5), [6, 8],
               0, ["Früchtemüsli", "Basismüsli", "Schokomüsli", "Knuspermüsli", "Cornflakes",
                   "Dinkelflakes", "Porridge", "Hafer-Knusper", "Schokoknusper"],
               ["ohne Zucker", "mit Beeren", "Nuss", "Apfel-Zimt", "Hafer", "Dinkel", "Kakao",
                "Tropenfrucht", "Klassisch"]),
    "grain": ("Getreide abgepackt + Getreideprodukte", "Getreide & Reis", LOW, G,
              [500, 1000, 2500], (1.1, 4.8), [6, 10], 0,
              ["Basmatireis", "Rundkornreis", "Langkornreis", "Hirse", "Quinoa", "Buchweizen",
               "Couscous", "Bulgur", "Polenta", "Haferflocken", "Dinkelflocken", "Grünkern"],
              ["hell", "Vollkorn", "Kleinblatt", "Großblatt", "weiß", "rot", "natur", "fein",
               "grob"]),
    "flour": ("Getreide abgepackt + Getreideprodukte", "Mehle", LOW, G, [1000, 2500], (1.2, 4.0),
              [6, 10], 0, ["Weizenmehl", "Dinkelmehl", "Roggenmehl", "Buchweizenmehl", "Maismehl"],
              ["Type 550", "Type 630", "Type 1050", "Type 1150", "Vollkorn"]),
    "legumes": ("Hülsenfrüchte", "Ölsaaten, Hülsenfrüchte, Keimsaaten", LOW, G, [500, 1000],
                (1.5, 4.5), [6, 10], 0,
                ["Linsen", "Kichererbsen", "Bohnen", "Erbsen", "Mungbohnen", "Azukibohnen"],
                ["rot", "grün", "Beluga", "schwarz", "weiß", "Kidney", "gelb geschält", "Berg"]),
    "nuts": ("Nüsse", "Nusskerne", LOW, G, [100, 200, 500], (1.8, 9.5), [6, 10], 0,
             ["Mandeln", "Haselnusskerne", "Walnusskerne", "Cashewkerne", "Erdnüsse",
              "Studentenfutter", "Pinienkerne", "Paranüsse", "Sonnenblumenkerne", "Kürbiskerne"],
             ["natur", "geröstet", "blanchiert", "gehackt", "gesalzen", "ganz", "gemahlen"]),
    "dried_fruit": ("Trockenobst", "Nusskerne, Trockenfrüchte", LOW, G, [100, 200, 500],
                    (1.6, 7.5), [6, 10], 0,
                    ["Datteln", "Feigen", "Aprikosen", "Rosinen", "Cranberries", "Pflaumen",
                     "Apfelringe", "Mangostreifen", "Sultaninen", "Gojibeeren"],
                    ["entsteint", "getrocknet", "ungeschwefelt", "weich", "Natur", "gesüßt"]),
    "sweets": ("Schokolade&Co", "Süßwaren", HIGH, G, [40, 100, 125, 200], (0.8, 3.9), [10, 12], 0,
               ["Vollmilchschokolade", "Zartbitterschokolade", "Weiße Schokolade", "Nougatriegel",
                "Fruchtgummi", "Lakritz", "Schokoriegel", "Pralinen", "Waffeln", "Kekse"],
               ["70%", "Haselnuss", "Mandel", "Orange", "Minze", "Chili", "Meersalz", "Kokos",
                "Himbeer", "vegan"]),
    "biscuits": ("Kekse", "Süßwaren, Waffeln, Gebäck", LOW, G, [125, 150, 200, 250], (1.0, 3.5),
                 [10, 12], 0, ["Butterkekse", "Dinkelkekse", "Hafertaler", "Schokokekse",
                               "Zwieback", "Knäckebrot", "Reiswaffeln", "Salzgebäck"],
                 ["Vollkorn", "Sesam", "Kakao", "Kokos", "Zitrone", "Schoko", "Natur"]),
    "spreads": ("Aufstriche süß", "Süße Brot- & Fruchtaufstriche", LOW, G, [200, 250, 400],
                (1.6, 4.8), [6], 0,
                ["Erdbeer Fruchtaufstrich", "Aprikosen Fruchtaufstrich", "Kirsch Fruchtaufstrich",
                 "Himbeer Fruchtaufstrich", "Nuss-Nougat-Creme", "Pflaumenmus", "Apfelkraut",
                 "Haselnussmus", "Mandelmus", "Erdnussmus"],
                ["fein", "stückig", "zuckerfrei", "mit Agave", "weiß", "braun", "knusprig"]),
    "savoury": ("Aufstriche pikant", "Würzige und süße Brotaufstriche, Nussmuse", LOW, G,
                [125, 180, 200], (1.3, 3.2), [6], 0,
                ["Brotaufstrich", "Hummus", "Pastete", "Linsenaufstrich", "Paprikacreme"],
                ["Kräuter", "Tomate-Basilikum", "Paprika", "Curry", "Bärlauch", "Olive",
                 "Rote Bete", "Pilz"]),
    "canned": ("Pikante Konserven (Gemüse, ...)", "Gemüse-, Obst- & Wurstkonserven", LOW, G,
               [340, 400, 680], (0.9, 3.5), [6, 12], 0,
               ["Kichererbsen", "Kidneybohnen", "Mais", "Tomaten gehackt", "Tomaten passiert",
                "Gewürzgurken", "Rote Bete", "Sauerkraut", "Artischockenherzen", "Linseneintopf"],
               ["im Glas", "in der Dose", "natur", "mit Kräutern", "fein", "Kleinpackung"]),
    "sauces": ("Würzsoßen & -pasten", "Pasta- & Pizzasaucen", LOW, ML, [200, 330, 500],
               (1.2, 4.2), [6], 0,
               ["Tomatensauce", "Pesto", "Sojasauce", "Ketchup", "Senf", "Gemüsebrühe",
                "Currypaste", "Balsamico", "Apfelessig", "Grillsauce"],
               ["Basilikum", "Arrabbiata", "mild", "scharf", "mittelscharf", "rot", "grün",
                "Klassik", "süß-sauer"]),
    "oil": ("Speiseöle", "Speiseöle", LOW, ML, [250, 500, 750, 1000], (2.5, 12.0), [6], 0,
            ["Olivenöl", "Sonnenblumenöl", "Rapsöl", "Leinöl", "Kokosöl", "Sesamöl", "Walnussöl"],
            ["nativ extra", "kaltgepresst", "desodoriert", "geröstet", "zum Braten", "mild"]),
    "spices": ("Trockengewürze", "Salz, Backzutaten, Süssmittel", LOW, G, [25, 50, 100, 500],
               (0.9, 6.5), [6, 10], 0,
               ["Pfeffer", "Paprika", "Curry", "Kurkuma", "Zimt", "Oregano", "Kräutersalz",
                "Meersalz", "Kreuzkümmel", "Muskat", "Chili", "Ingwer"],
               ["gemahlen", "ganz", "geschrotet", "edelsüß", "rosenscharf", "fein", "grob",
                "Nachfüllbeutel"]),
    "tea": ("Kräuter&Früchtetees", "Tee, Kaffee, Kakao", LOW, G, [20, 40, 50, 100], (1.4, 5.5), [6, 10], 0,
            ["Pfefferminztee", "Kamillentee", "Rooibostee", "Früchtetee", "Fencheltee",
             "Ingwertee", "Grüner Tee", "Schwarzer Tee", "Kräutertee", "Gewürztee"],
            ["lose", "im Beutel", "Zitrone", "Vanille", "Orange", "Earl Grey", "Sencha",
             "Assam", "Wintertraum"]),
    "coffee": ("Kaffee & Kakao", "Tee, Kaffee und Kakao", HIGH, G, [250, 500, 1000],
               (3.0, 14.0), [6], 0,
               ["Röstkaffee", "Espresso", "Kakao", "Trinkschokolade", "Getreidekaffee"],
               ["ganze Bohne", "gemahlen", "entkoffeiniert", "kräftig", "mild", "stark entölt"]),
    "juice": ("Saft", "Fruchtsäfte", HIGH, ML, [200, 330, 700, 1000], (0.9, 3.2), [6, 12], 0.15,
              ["Apfelsaft", "Orangensaft", "Traubensaft", "Multivitaminsaft", "Karottensaft",
               "Rote-Bete-Saft", "Apfelschorle", "Johannisbeernektar", "Mangosaft"],
              ["naturtrüb", "klar", "Direktsaft", "rot", "weiß", "mit Fruchtfleisch"]),
    "soft_drinks": ("Limonade", "Limonade & Co.", HIGH, ML, [330, 500, 750], (0.6, 1.8), [12, 24],
                    0.15, ["Zitronenlimonade", "Orangenlimonade", "Cola", "Ingwerlimonade",
                           "Holunderblütenlimonade", "Mate", "Eistee", "Mineralwasser"],
                    ["klassisch", "naturtrüb", "zuckerfrei", "still", "medium", "spritzig",
                     "Pfirsich"]),
    "plant_milk": ("Sojamilch, Drinks und Mineralwasser", "Milchersatzprodukte - Sojadrink & Co.",
                   HIGH, ML, [1000], (0.9, 2.6), [8, 12], 0,
                   ["Haferdrink", "Sojadrink", "Mandeldrink", "Reisdrink", "Dinkeldrink",
                    "Kokosdrink"], ["natur", "Calcium", "für Kaffee", "Vanille", "ungesüßt",
                                    "Schoko"]),
    "beer": ("Bier", "Bier & Biermixgetränke", HIGH, ML, [330, 500], (0.8, 1.6), [20, 24], 0.08,
             ["Pils", "Helles", "Weizenbier", "Dunkles", "Radler", "Alkoholfreies Bier",
              "Kellerbier"], ["naturtrüb", "Flasche", "Bügelflasche", "hell", "dunkel"]),
    "wine": ("Deutscher Wein", "Wein und Spirituosen", HIGH, ML, [750], (3.5, 12.0), [6], 0,
             ["Riesling", "Grauburgunder", "Weißburgunder", "Spätburgunder", "Dornfelder",
              "Müller-Thurgau", "Rosé", "Secco"], ["trocken", "halbtrocken", "feinherb",
                                                   "lieblich", "Gutswein", "Ortswein"]),
    "yoghurt": ("Kühlware: Joghurt & Co (Milchprodukte)", "Joghurt", LOW, G, [150, 500],
                (0.6, 2.3), [6, 10], 0,
                ["Joghurt", "Quark", "Skyr", "Kefir", "Fruchtjoghurt", "Schmand", "Saure Sahne"],
                ["natur", "3,8%", "1,5%", "Erdbeere", "Vanille", "Heidelbeere", "griechische Art",
                 "Magerstufe"]),
    "milk": ("Kühlware: Milchprodukte (ohne Käse, Jogh.,Dessert)", "Milch", LOW, ML, [500, 1000],
             (0.8, 1.9), [6, 10], 0,
             ["Vollmilch", "Fettarme Milch", "Schlagsahne", "Buttermilch", "Kakao-Milch"],
             ["3,8%", "1,5%", "frisch", "länger haltbar", "Weidemilch", "30%"]),
    "plant_yoghurt": ("Kühlware: Joghurt & Co (keine Tiermilchprodukte)",
                      "pflanzliche Alternativen zu Joghurt", LOW, G, [150, 400, 500], (0.9, 2.8),
                      [6], 0, ["Sojajoghurt", "Haferjoghurt", "Kokosjoghurt", "Mandeljoghurt"],
                      ["natur", "Vanille", "Kirsche", "Mango", "ungesüßt", "Blaubeere"]),
    "tofu": ("Tofu-Produkte", "Seitan & Co.", LOW, G, [200, 250, 400], (1.5, 3.9), [6], 0,
             ["Tofu", "Räuchertofu", "Seitan", "Tempeh", "Tofu-Würstchen", "Bratlinge"],
             ["natur", "Kräuter", "Basilikum", "Mandel-Sesam", "geräuchert", "Rosso"]),
    "cheese": ("Käse überregional mit fester Packungsgröße", "Schnittkäse", LOW, G,
               [125, 150, 200], (1.6, 4.5), [6, 8], 0,
               ["Gouda", "Bergkäse", "Emmentaler", "Camembert", "Brie", "Feta", "Mozzarella",
                "Frischkäse", "Ziegenfrischkäse", "Butterkäse"],
               ["jung", "mittelalt", "alt", "in Scheiben", "am Stück", "Kräuter", "natur",
                "Bockshornklee"]),
    "cheese_weighed": ("Käse überregional zum Auswiegen", "Hartkäse", LOW, G, [3000, 4500, 6000],
                       (12.0, 28.0), [1], 0,
                       ["Gouda", "Bergkäse", "Tilsiter", "Emmentaler", "Parmesan-Art", "Hirtenkäse"],
                       ["jung", "mittelalt", "alt", "mit Kräutern", "Bockshornklee", "vom Laib"]),
    "meat": ("Wurst und Aufschnitt vom Großhandel", "Wurstwaren", LOW, G, [80, 100, 150, 200],
             (1.4, 4.5), [6, 8], 0,
             ["Salami", "Kochschinken", "Leberwurst", "Bierschinken", "Mortadella", "Lyoner",
              "Wiener Würstchen", "Bratwurst"], ["geschnitten", "Rind", "Pute", "fein", "grob",
                                                 "luftgetrocknet", "geräuchert"]),
    "frozen": ("Tiefkühlkost (ohne Fleisch und Fisch)", "Tiefkühlkost", LOW, G, [300, 400, 750],
               (1.8, 6.5), [6, 10], 0,
               ["Pizza", "Spinat", "Erbsen", "Beerenmischung", "Pommes", "Gemüsepfanne",
                "Eis", "Flammkuchen"], ["Margherita", "Gemüse", "rahmig", "fein", "klassisch",
                                        "Vanille", "Schoko"]),
    "detergent": ("Waschmittel", "Reinigungsmittel", HIGH, ML, [500, 1000, 1500], (1.8, 8.5),
                  [6, 10], 0, ["Spülmittel", "Allzweckreiniger", "Waschmittel", "Badreiniger",
                               "Glasreiniger", "Essigreiniger", "Weichspüler"],
                  ["Zitrone", "sensitiv", "Konzentrat", "Lavendel", "Orange", "Nachfüllpack"]),
    "cosmetics": ("Körperpflege + Kosmetik", "Kosmetik & Ätherische Öle", HIGH, ML,
                  [50, 75, 200, 250], (1.8, 9.5), [4, 6], 0,
                  ["Shampoo", "Duschgel", "Bodylotion", "Handcreme", "Zahncreme", "Deo",
                   "Sonnencreme", "Lippenbalsam", "Gesichtscreme"],
                  ["Rose", "Lavendel", "Calendula", "Mandel", "Minze", "sensitiv", "Granatapfel",
                   "LSF 30", "Aloe Vera"]),
}

# Categories of the regional suppliers (not in the wholesale catalog).
LOCAL_CATEGORIES = {
    "eggs": ("Kühlware: Milchprodukte (ohne Käse, Jogh.,Dessert)", LOW, PCS, [6, 10], (1.8, 4.2),
             0, ["Eier"], ["Freiland", "Bio", "Größe M", "Größe L", "Bruderhahn"]),
    "veg_box": ("Saisonartikel (Ostern, Weihnachten)", LOW, PCS, [1], (8.0, 22.0), 0,
                ["Gemüsekiste"], ["klein", "mittel", "groß", "Rohkost", "Familie"]),
    "honey": ("Honig", LOW, G, [250, 500], (3.8, 8.5), 0,
              ["Blütenhonig", "Waldhonig", "Rapshonig", "Lindenhonig", "Akazienhonig"],
              ["cremig", "flüssig", "Sommertracht", "Frühtracht"]),
    "cheese_local": ("Käse regional", LOW, G, [150, 200, 250], (2.4, 5.8), 0,
                     ["Bergkäse", "Frischkäse", "Weichkäse", "Schnittkäse", "Quark"],
                     ["Heublume", "Schnittlauch", "Pfeffer", "natur", "würzig", "Rotschmiere"]),
    "flour_local": ("Getreide abgepackt + Getreideprodukte", LOW, G, [1000, 2500], (1.6, 5.2), 0,
                    ["Dinkelmehl", "Roggenmehl", "Weizenmehl", "Haferflocken"],
                    ["Type 630", "Type 1150", "Vollkorn", "Kleinblatt", "steingemahlen"]),
    "wine": ("Deutscher Wein", HIGH, ML, [750, 1000], (4.5, 14.0), 0,
             ["Riesling", "Silvaner", "Spätburgunder", "Regent", "Cuvée", "Traubensaft"],
             ["trocken", "halbtrocken", "Kabinett", "Spätlese", "Literflasche"]),
    "meat_local": ("Fleisch + Wurst regional", LOW, G, [100, 150, 200, 400], (1.9, 6.5), 0,
                   ["Leberwurst", "Rotwurst", "Bratwurst", "Schinken", "Mettwurst", "Sülze"],
                   ["im Glas", "geräuchert", "grob", "fein", "nach Hausmacherart"]),
    "oil_local": ("Speiseöle", LOW, ML, [250, 500], (3.5, 9.5), 0,
                  ["Rapsöl", "Leinöl", "Senföl", "Hanföl", "Sonnenblumenöl"],
                  ["kaltgepresst", "frisch gepresst", "nativ"]),
    "coffee_local": ("Kaffee & Kakao", HIGH, G, [250, 500, 1000], (4.5, 15.0), 0,
                     ["Röstkaffee", "Espresso", "Filterkaffee"],
                     ["ganze Bohne", "gemahlen", "Hausmischung", "Hochland", "dunkel geröstet"]),
    "fair": ("Aktionsartikel, Schreibwaren", HIGH, PCS, [1], (1.2, 12.0), 0,
             ["Kerze", "Notizbuch", "Einkaufsnetz", "Gewürzmischung", "Schokolade", "Kaffee"],
             ["aus Recyclingpapier", "Bienenwachs", "Baumwolle", "fair gehandelt", "Geschenk"]),
    "soap": ("Körperpflege Selbstabfüllung", HIGH, G, [80, 100, 120], (2.5, 6.5), 0,
             ["Seife", "Haarseife", "Rasierseife", "Duschstück"],
             ["Lavendel", "Ziegenmilch", "Rosmarin", "Olive", "unparfümiert", "Heilerde"]),
    "juice_local": ("Regionaler Apfelsaft", HIGH, ML, [1000, 5000], (1.4, 7.5), 0.15,
                    ["Apfelsaft", "Apfel-Birne-Saft", "Apfel-Quitte-Saft", "Apfelschorle"],
                    ["naturtrüb", "Saftbox 5 l", "Streuobst", "sortenrein"]),
    "lamb": ("Lamm aus der Region", LOW, G, [1000], (14.0, 32.0), 0,
             ["Lammkeule", "Lammkotelett", "Lammhack", "Lammbratwurst", "Lammschulter"],
             ["frisch", "tiefgekühlt", "küchenfertig", "mit Knochen"]),
}

KK_SURCHARGE = 0.18  # recorded on generated shopping items of wholesaler articles
KK_ARTICLES = 1900  # articles from the wholesaler; the catalog contains them and more
CATALOG_ONLY = 1600  # catalog entries without an article
LOCAL_ARTICLES = 260


def q(v):
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, (int, float)):
        return repr(round(v, 4)) if isinstance(v, float) else str(v)
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def ean13():
    # Prefix 20-29 is reserved for in-store numbering, so no real product has these barcodes.
    while True:
        digits = [2, rnd.randint(0, 9)] + [rnd.randint(0, 9) for _ in range(10)]
        check = (10 - sum(d * (3 if i % 2 else 1) for i, d in enumerate(digits)) % 10) % 10
        code = int("".join(map(str, digits + [check])))
        if code not in used_barcodes:
            used_barcodes.add(code)
            return code


def unique(pool, low, high):
    while True:
        n = rnd.randint(low, high)
        if n not in pool:
            pool.add(n)
            return n


def unit_label(amount, unit):
    if unit == G:
        return f"{amount / 1000:g} kg".replace(".", ",") if amount >= 1000 else f"{amount} g"
    if unit == ML:
        return f"{amount / 1000:g} l".replace(".", ",") if amount >= 1000 else f"{amount} ml"
    return f"{amount} St"


def price(low, high):
    return round(rnd.uniform(low, high), 2)


used_barcodes, used_kb_numbers, used_item_numbers = set(), set(), set()
out = []
sql = out.append

sql("SET FOREIGN_KEY_CHECKS=0;")
sql("SET @system_suppliers = '" + ",".join(SYSTEM_SUPPLIERS) + "';")

# --- remove the old data --------------------------------------------------------------------
for table in ("IgnoredDifference", "ArticleStock", "ArticlePrintPool", "Shelf_Article", "PreOrder"):
    sql(f"DELETE FROM `{table}`;")
sql("DELETE FROM CatalogEntry;")
sql("DELETE FROM Article WHERE kbNumber >= 0;")
sql("DELETE FROM SurchargeGroup WHERE supplier_id IN (SELECT id FROM Supplier "
    "WHERE NOT FIND_IN_SET(shortName, @system_suppliers));")
sql("DELETE FROM Supplier WHERE NOT FIND_IN_SET(shortName, @system_suppliers);")
for table in ("Article_AUD", "Supplier_AUD", "SurchargeGroup_AUD", "PriceLists_AUD", "REVINFO"):
    sql(f"DELETE FROM `{table}`;")
sql("ALTER TABLE REVINFO AUTO_INCREMENT = 1;")

# --- generic names for what stays -----------------------------------------------------------
sql(f"UPDATE Supplier SET name = {q(WHOLESALER_NAME)}, keeper = 'Team Einkauf', email = '', "
    "phoneNumber = '', street = '', location = '', fax = '' WHERE shortName = 'KK';")
sql("UPDATE Supplier SET keeper = '' WHERE shortName <> 'KK';")
sql("UPDATE SurchargeGroup g JOIN Supplier s ON s.id = g.supplier_id "
    "SET g.name = CONCAT('@', s.name, ' Standard Aufschlag') "
    "WHERE g.parent_id IS NULL AND g.name LIKE '@% Standard Aufschlag';")
for gid, name in SURCHARGE_GROUP_RENAMES.items():
    sql(f"UPDATE SurchargeGroup SET name = {q(name)} WHERE id = {gid};")
for pid, name in PRICE_LIST_RENAMES.items():
    sql(f"UPDATE PriceLists SET name = {q(name)} WHERE id = {pid};")
sql("UPDATE Article SET name = 'Benutzerdefinierter Artikel', "
    "supplier_id = (SELECT id FROM Supplier WHERE shortName = 'CP'), "
    "surchargeGroup_id = (SELECT id FROM SurchargeGroup WHERE parent_id IS NULL AND supplier_id = "
    "(SELECT id FROM Supplier WHERE shortName = 'CP') LIMIT 1) WHERE kbNumber = -5;")
sql("UPDATE SettingValue SET value = 'BNN;3;0;Naturkost-Grosshandel Nord;T;Preisliste Juli 2024;"
    "EUR;20240701;2024071;20240625;1335;1' WHERE setting = 'INFO_LINE_LAST_CATALOG';")

# --- suppliers --------------------------------------------------------------------------------
supplier_ids = {}
next_supplier_id, next_group_id = 1000, 1000
for i, (name, short, surcharge, _) in enumerate(SUPPLIERS):
    slug = short.lower()
    sql("INSERT INTO Supplier (id, createDate, defaultSurcharge, email, fax, keeper, location, name, "
        "phoneNumber, shortName, street, updateDate) VALUES "
        f"({next_supplier_id}, {q(NOW)}, {surcharge}, {q(f'bestellung@{slug}.example')}, '', "
        f"{q(KEEPERS[i % len(KEEPERS)])}, {q(f'{rnd.randint(10000, 99999)} Musterdorf')}, {q(name)}, "
        f"{q(f'0531 555{rnd.randint(1000, 9999)}')}, {q(short)}, {q(f'Feldweg {rnd.randint(1, 60)}')}, "
        f"{q(NOW)});")
    sql(f"INSERT INTO SurchargeGroup (id, name, surcharge, parent_id, supplier_id) VALUES "
        f"({next_group_id}, {q('@' + name + ' Standard Aufschlag')}, {surcharge}, NULL, "
        f"{next_supplier_id});")
    supplier_ids[short] = (next_supplier_id, next_group_id)
    next_supplier_id += 1
    next_group_id += 1


# --- articles and catalog -------------------------------------------------------------------
def pricelist(name):
    return f"(SELECT id FROM PriceLists WHERE name = {q(name)} ORDER BY id LIMIT 1)"


def kk_group(name):
    return ("(SELECT id FROM SurchargeGroup WHERE supplier_id = (SELECT id FROM Supplier WHERE "
            f"shortName = 'KK') AND name = {q(name)} ORDER BY id LIMIT 1)")


article_rows, catalog_rows = [], []
next_article_id, next_catalog_id = 1, 1
day = datetime(2024, 6, 25)


sellable = []  # articles the generated purchases are made of


def add_article(name, unit, amount, net, vat, container, deposit, weighable, price_list, supplier,
                group, item_no, barcode, short=None, surcharge=None):
    global next_article_id
    shop_range = rnd.choices([0, 1, 2, 3], weights=[35, 35, 25, 5])[0]
    active = shop_range in (1, 2) and rnd.random() < 0.8
    kb_number = unique(used_kb_numbers, 1, 59999)
    article_rows.append(
        f"({next_article_id}, {q(active)}, NULL, {amount}, {q(barcode)}, 0, {container}, '', "
        f"{kb_number}, {unit}, {q(name)}, {net}, NULL, 0, NULL, "
        f"{shop_range}, 0, {deposit}, {item_no}, {q(NOW)}, {vat}, {q(rnd.random() < 0.7)}, "
        f"{q(weighable)}, {price_list}, {supplier}, {group}, 1, 1, 0)")
    if active and not weighable and short:
        sellable.append(dict(id=next_article_id, name=name, unit=unit, amount=amount, net=net,
                             vat=vat, kb=kb_number, item_no=item_no, short=short,
                             surcharge=surcharge))
    next_article_id += 1


def add_catalog_entry(name, unit, amount, net, vat, container, deposit, weighable, item_no,
                      barcode):
    global next_catalog_id
    shop_unit = "1 kg" if weighable else unit_label(amount, unit)
    order_unit = f"{amount / 1000:g} kg".replace(".", ",") if weighable else \
        f"{container} x {shop_unit}"
    retail = round(net * (1.07 if vat == LOW else 1.19) * 1.35, 2)
    catalog_rows.append(
        f"({next_catalog_id}, {q(day.strftime('%Y-%m-%d'))}, NULL, 'A', 0, NULL, NULL, NULL, "
        f"{q(str(item_no))}, NULL, 'T', {q(order_unit)}, {container}, {q(name)}, '', '', NULL, "
        f"{q(barcode)}, {q(barcode)}, {deposit}, {retail}, NULL, NULL, '', "
        f"{round(deposit * container, 2)}, {round(amount / 1000 * container, 3) if unit == G else 'NULL'}, "
        f"NULL, {q(weighable)}, NULL, 1, '', {q(rnd.choice(['D', 'D', 'D', 'IT', 'ES', 'AT', 'NL']))}, "
        f"'', NULL, NULL, NULL, 'DE-ÖKO-000', {q(shop_unit)}, NULL, NULL, "
        f"{rnd.choice([30, 90, 180, 365])}, 'Hausmarke', NULL, 1, 1, {vat}, '', '', {net}, 'kbA', 1, 1, 1, "
        f"1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, "
        f"NULL, NULL, NULL, NULL, NULL, NULL, {rnd.randint(100, 999)}, "
        f"{rnd.randint(1, 30)}, NULL)")
    next_catalog_id += 1


def make_kk_product(key):
    (price_list, group, vat, unit, amounts, (low, high), containers, deposit, names,
     variants) = CATEGORIES[key]
    weighable = key == "cheese_weighed"
    name = f"{rnd.choice(names)} {rnd.choice(variants)}"
    amount = rnd.choice(amounts)
    scale = 1 if weighable else max(0.6, (amount / amounts[len(amounts) // 2]) ** 0.8)
    net = round(price(low, high) * scale, 2)
    return (name, unit, amount, net, vat, rnd.choice(containers), deposit, weighable, price_list,
            group)


keys = list(CATEGORIES)
for i in range(KK_ARTICLES + CATALOG_ONLY):
    (name, unit, amount, net, vat, container, deposit, weighable, price_list,
     group) = make_kk_product(rnd.choice(keys))
    item_no = unique(used_item_numbers, 1000, 99999)
    barcode = None if weighable else ean13()
    add_catalog_entry(name, unit, amount, net, vat, container, deposit, weighable, item_no, barcode)
    if i < KK_ARTICLES:
        # a few articles differ from the catalog, as after a price change at the wholesaler
        article_net = net if rnd.random() > 0.05 else round(net * rnd.uniform(0.9, 1.1), 2)
        add_article(name, unit, amount, article_net, vat, container, deposit, weighable,
                    pricelist(price_list), "(SELECT id FROM Supplier WHERE shortName = 'KK')",
                    kk_group(group), item_no, barcode, "KK", KK_SURCHARGE)

for _ in range(LOCAL_ARTICLES):
    name_, short, surcharge, categories = rnd.choice(SUPPLIERS)
    supplier, group = supplier_ids[short]
    price_list, vat, unit, amounts, (low, high), deposit, names, variants = \
        LOCAL_CATEGORIES[rnd.choice(categories)]
    weighable = price_list == "Lamm aus der Region"
    add_article(f"{rnd.choice(names)} {rnd.choice(variants)}", unit, rnd.choice(amounts),
                price(low, high), vat, rnd.choice([1, 6, 10]), deposit, weighable,
                pricelist(price_list), supplier, group, unique(used_item_numbers, 1, 999),
                None if weighable else ean13(), short, surcharge)

# The demo purchase refers to four articles; give them generated counterparts with the same values.
PURCHASED = [  # shopping item id, name, kk, amount, unit, net price, vat, weighable, price list, group
    (1, "Joghurt natur 3,8%", True, 500, G, 1.13, LOW, False,
     "Kühlware: Joghurt & Co (Milchprodukte)", "Joghurt"),
    (3, "Weingelee rot", False, 250, ML, 2.78, HIGH, False, "Aufstriche süß", None),
    (4, "Pfefferminztee lose", False, 50, G, 2.46, LOW, False, "Kräuter&Früchtetees", None),
    (5, "Gouda jung vom Laib", True, 4500, G, 9.69, LOW, True,
     "Käse überregional zum Auswiegen", "Hartkäse"),
]
for item_id, name, kk, amount, unit, net, vat, weighable, price_list, group in PURCHASED:
    item_no = unique(used_item_numbers, 1000, 99999) if kk else unique(used_item_numbers, 1, 999)
    barcode = None if weighable else ean13()
    if kk:
        supplier, group_sql, short = ("(SELECT id FROM Supplier WHERE shortName = 'KK')",
                                      kk_group(group), "KK")
        add_catalog_entry(name, unit, amount, net, vat, 1, 0, weighable, item_no, barcode)
    else:
        short = "IML" if item_id == 3 else "FWI"
        supplier, group_sql = supplier_ids[short]
    article_id = next_article_id
    add_article(name, unit, amount, net, vat, 1, 0, weighable, pricelist(price_list), supplier,
                group_sql, item_no, barcode)
    sql(f"UPDATE ShoppingItem SET articleId = {article_id}, name = {q(name)}, "
        f"suppliersItemNumber = {item_no}, suppliersShortName = {q(short)} WHERE id = {item_id};")

ARTICLE_COLUMNS = ("id, active, activeStateChange, amount, barcode, containerDeposit, containerSize, "
                   "info, kbNumber, metricUnits, name, netPrice, obsoleteSurcharge, offer, producer, "
                   "shopRange, showInShop, singleDeposit, suppliersItemNumber, updateDate, vat, "
                   "verified, weighable, priceList_id, supplier_id, surchargeGroup_id, "
                   "catalogPriceFactor, labelCount, labelPerUnit")
CATALOG_COLUMNS = (
    "id, aenderungsDatum, aenderungsZeit, aenderungskennung, aktionspreis, aktionspreisGueltigAb, "
    "aktionspreisGueltigBis, artikelBioId, artikelNr, artikelVariant, artikelart, bestelleinheit, "
    "bestelleinheitsMenge, bezeichnung, bezeichnung2, bezeichnung3, breite, eanBestellEinheit, "
    "eanLadenEinheit, einzelPfand, empfVk, empfVkAktion, empfVkGH, ersatzArtikelNr, gebindePfand, "
    "gewichtBestelleinheit, gewichtLadeneinheit, gewichtsartikel, grundpreisEinheit, "
    "grundpreisFaktor, handelsklasse, herkunft, hersteller, herstellerId, hoehe, katalogGueltigBis, "
    "kontrollstelle, ladeneinheit, lieferbarAb, lieferbarBis, mHDRestlaufzeit, marke, markenId, "
    "mengenfaktor, minBestellMenge, mwstKennung, pfandNrBestelleinheit, pfandNrLadeneinheit, preis, "
    "qualitaet, rabattfaehig, rabattfaehig1, rabattfaehig2, rabattfaehig3, rabattfaehig4, "
    "rabattfaehig5, skontierfaehig, skontierfaehig1, skontierfaehig2, skontierfaehig3, "
    "skontierfaehig4, skontierfaehig5, staffelMenge1, staffelMenge2, staffelMenge3, staffelMenge4, "
    "staffelMenge5, staffelPreis1, staffelPreis2, staffelPreis3, staffelPreis4, staffelPreis5, "
    "tiefe, vkFestpreis, wgBnn, wgGh, wgIfh")


def insert_batches(table, columns, rows, size=500):
    for start in range(0, len(rows), size):
        sql(f"INSERT INTO {table} ({columns}) VALUES\n" + ",\n".join(rows[start:start + size]) + ";")


insert_batches("Article", ARTICLE_COLUMNS, article_rows)
insert_batches("CatalogEntry", CATALOG_COLUMNS, catalog_rows)
sql("UPDATE ShoppingItem i JOIN Article a ON a.id = i.articleId SET i.kbNumber = a.kbNumber;")

# --- purchases --------------------------------------------------------------------------------
# The app marks users without a purchase in the last DAYS_BEFORE_INACTIVITY (180) days as inactive
# on start. `make db-demo` moves all dates so that the newest purchase is from today; the users in
# ACTIVE_USERS shop in the weeks before, the others only more than 180 days before.
ANCHOR = datetime(2025, 6, 30, 17, 30)
SHOP_USER = 53  # the store's own account ("kernbeisser"), counterpart of every purchase
CASHIERS = [183, 195]  # ricken.p, marie.c
ACTIVE_USERS = [168, 172, 181, 183, 186, 190, 195, 199, 215, 238, 251]
INACTIVE_USERS = [176, 202, 253, 257]  # haile.s, shirin.e, marie.j, laura.r

events = []  # (date, kind, user)
for user in ACTIVE_USERS + INACTIVE_USERS:
    if user in ACTIVE_USERS:
        dates = [ANCHOR - timedelta(days=rnd.uniform(0, 75), minutes=rnd.randint(0, 600))
                 for _ in range(rnd.randint(2, 5))]
    else:
        dates = [ANCHOR - timedelta(days=rnd.uniform(220, 420), minutes=rnd.randint(0, 600))
                 for _ in range(rnd.randint(1, 2))]
    dates.sort()
    events.append((dates[0] - timedelta(days=rnd.randint(1, 5)), "PAYIN", user))
    events += [(d, "PURCHASE", user) for d in dates]
events.append((ANCHOR, "PURCHASE", ACTIVE_USERS[0]))  # the newest purchase sets the time shift
events.sort()

# purchases generated by an earlier run
sql("DELETE FROM ShoppingItem WHERE id >= 1000;")
sql("DELETE FROM Purchase WHERE id >= 1000;")
sql("DELETE FROM SaleSession WHERE id >= 1000;")
sql("DELETE FROM Transaction WHERE id >= 2000;")
sql("SET @next_seq = (SELECT COALESCE(MAX(seqNo), -1) + 1 FROM Transaction);")
sql("SET @next_bon = (SELECT COALESCE(MAX(bonNo), 0) + 1 FROM Purchase);")
transaction_id, session_id, purchase_id, item_id = 2000, 1000, 1000, 1000
item_rows = []


def transaction(date, kind, value, created_by, from_user, to_user, info):
    sql("INSERT INTO Transaction (id, date, info, transactionType, value, createdBy_id, fromUser_id, "
        "fromUserGroup_id, toUser_id, toUserGroup_id, accountingReportNo, seqNo) SELECT "
        f"{transaction_id}, {q(date.strftime('%Y-%m-%d %H:%M:%S'))}, {q(info)}, {q(kind)}, {value}, "
        f"{created_by}, f.id, f.userGroup_id, t.id, t.userGroup_id, NULL, @next_seq "
        f"FROM User f, User t WHERE f.id = {from_user} AND t.id = {to_user};")
    sql("SET @next_seq = @next_seq + 1;")


# Plan the purchases first, so every user pays in enough to keep a positive balance.
plans, spent = [], {}
for date, kind, user in events:
    if kind == "PAYIN":
        plans.append(None)
        continue
    seller = rnd.choice(CASHIERS) if rnd.random() < 0.4 else user
    items, total = [], 0.0
    for article in rnd.sample(sellable, rnd.randint(2, 9)):
        vat_value = 0.07 if article["vat"] == LOW else 0.19
        retail = round(article["net"] * (1 + article["surcharge"]) * (1 + vat_value), 2)
        multiplier = rnd.choices([1, 2, 3], weights=[70, 22, 8])[0]
        items.append((article, vat_value, retail, multiplier))
        total += round(retail * multiplier, 2)
    plans.append((seller, items, round(total, 2)))
    spent[user] = spent.get(user, 0) + total

for (date, kind, user), plan in zip(events, plans):
    if kind == "PAYIN":
        amount = 50 * math.ceil((spent[user] + 100) / 50)
        transaction(date, "PAYIN", amount, CASHIERS[0], SHOP_USER, user, "Einzahlung")
        transaction_id += 1
        continue
    seller, items, total = plan
    created = date.strftime("%Y-%m-%d %H:%M:%S")
    for index, (article, vat_value, retail, multiplier) in enumerate(items):
        item_rows.append(
            f"({item_id}, {article['amount']}, {article['id']}, 1, 0, {q(created)}, 0, "
            f"{multiplier}, {article['net']}, {retail}, {article['kb']}, {article['unit']}, "
            f"{q(article['name'])}, {index}, {article['item_no']}, {q(article['short'])}, "
            f"{article['surcharge']}, {article['vat']}, {vat_value}, 0, {purchase_id}, NULL)")
        item_id += 1
    transaction(date, "PURCHASE", total, seller, user, SHOP_USER,
                f"Einkauf vom {date.strftime('%d.%m.%Y')}")
    sql(f"INSERT INTO SaleSession (id, sessionType, customer_id, secondSeller_id, seller_id, "
        f"transaction_id) VALUES ({session_id}, {0 if seller != user else 1}, {user}, NULL, "
        f"{seller}, {transaction_id});")
    sql(f"INSERT INTO Purchase (id, createDate, userSurcharge, session_id, bonNo) VALUES "
        f"({purchase_id}, {q(created)}, 0, {session_id}, @next_bon);")
    sql("SET @next_bon = @next_bon + 1;")
    transaction_id, session_id, purchase_id = transaction_id + 1, session_id + 1, purchase_id + 1

insert_batches("ShoppingItem", "id, amount, articleId, articleRev, containerDiscount, createDate, "
               "discount, itemMultiplier, itemNetPrice, itemRetailPrice, kbNumber, metricUnits, "
               "name, shoppingCartIndex, suppliersItemNumber, suppliersShortName, surcharge, vat, "
               "vatValue, weighAble, purchase_id, orderNo", item_rows)
sql("UPDATE UserGroup g SET value = ROUND("
    "COALESCE((SELECT SUM(value) FROM Transaction WHERE toUserGroup_id = g.id), 0) - "
    "COALESCE((SELECT SUM(value) FROM Transaction WHERE fromUserGroup_id = g.id), 0), 2);")
sql("UPDATE User SET active = id IN (" + ", ".join(map(str, ACTIVE_USERS)) + ");")

# --- history: one revision with the current state, which past purchases refer to --------------
sql(f"INSERT INTO REVINFO (REV, REVTSTMP) VALUES (1, {NOW_MILLIS});")
sql("UPDATE ShoppingItem SET articleRev = 1;")
for table, columns in (
        ("Article", ARTICLE_COLUMNS),
        ("Supplier", "id, createDate, defaultSurcharge, email, fax, keeper, location, name, "
                     "phoneNumber, shortName, street, updateDate"),
        ("SurchargeGroup", "id, name, surcharge, parent_id, supplier_id"),
        ("PriceLists", "id, createDate, lastPrint, name, updateDate, superPriceList_id")):
    sql(f"INSERT INTO {table}_AUD ({columns}, REV, REVTYPE) SELECT {columns}, 1, 0 FROM {table};")

# Hibernate allocates ids in blocks of 50; start the sequences behind the ids used here.
for seq, next_id in (("Supplier_SEQ", next_supplier_id), ("SurchargeGroup_SEQ", next_group_id),
                     ("Transaction_SEQ", transaction_id), ("SaleSession_SEQ", session_id),
                     ("ShoppingItem_SEQ", item_id)):
    sql(f"SELECT SETVAL(`{seq}`, {next_id + 50}, 0);")

sql("SET FOREIGN_KEY_CHECKS=1;")
print("\n".join(out))
