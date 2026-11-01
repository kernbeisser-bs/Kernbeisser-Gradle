# Kernbeisser

Desktop application (Java 21 / Swing) for running the Kernbeisser food co-op store: checkout,
articles, members, inventory and reports. Data is stored in MariaDB via Hibernate.

## Quickstart

Requirements: Docker (with the Compose plugin) and `make`. A local JDK 21 is optional: without
one, Gradle runs in Docker automatically.

```sh
cp docker/.env.example docker/.env   # optional: adjust ports and passwords
make db-up                           # start MariaDB (localhost:3306, db/user/password: kernbeisser)
make db-demo                         # load the demo data (asks before replacing the database)
make gui                             # build the app and run it in Docker
```

Then open <http://localhost:6080/vnc.html?autoconnect=1&resize=remote>; the app runs in the browser
tab. Log in as `Admin` with the password `password`. A Java debugger can attach to `localhost:5005`
at any time.

On a machine with a desktop and JDK 21 you can run the app natively instead:

```sh
make run      # build and start the app against the dev database
make debug    # same, with a debugger port on localhost:5005 (SUSPEND=y waits for the debugger)
```

Run `make` for the list of all targets.

### Demo data

`make db-demo` replaces the dev database with the demo data from
`docker/demo/kernbeisser-demo.sql.gz`: fictional German data with about 2,200 articles, a
wholesale catalog, 12 regional suppliers, surcharge groups, price lists and a few transactions,
plus 17 users with different roles. **Every user's password is `password`.** See
[docker/demo/README.md](docker/demo/README.md) for how the articles and suppliers were generated.

| User          | Roles (permissions)                                     |
|---------------|---------------------------------------------------------|
| `Admin`       | Administrator                                           |
| `super.a`     | Administrator and full member                           |
| `ricken.p`    | Cashier, store service lead                             |
| `marie.c`     | Cashier                                                 |
| `marigona.u`  | Full member, accounting                                 |
| `christoph.s`, `john.d` | Full member, board                            |
| `emma.r`      | Full member, member administration                      |
| `rosa.l`      | Full member, account service                            |
| `luis.m`, `marie.j` | Trial members                                     |
| `laura.r`     | Basic access only                                       |

The remaining users are full members with various jobs. `kernbeisser` is the store's internal
account, not a person.

`make db-demo` moves all transaction and purchase dates so that the newest purchase is from today.
The app marks users without a purchase in the last 180 days as inactive, so most demo users are
active, while `haile.s`, `shirin.e`, `marie.j` and `laura.r` only shopped longer ago and appear
under *nur Inaktive*. Importing the dump in another way (e.g. via `docker/initdb/`) skips the date
shift; run `make db-import DUMP=docker/demo/shift-dates.sql` afterwards.

The demo database is at the current database version. When the code adds a new one
(`kernbeisser.VersionIntegrationTools.Version`), the app updates the database on start as it does
in production, so the demo file keeps working without changes.

#### Your own data

On an empty database the app creates its tables on first start, but there is no user to log in
with. To work with another dump, put it into `docker/initdb/` *before* the first `make db-up`
(it is imported automatically when the database volume is created), or load it later:

```sh
make db-import DUMP=path/to/dump.sql   # into the running database
# or start over:
make db-reset && make db-up            # deletes the volume and re-imports docker/initdb/
```

Dumps in `docker/initdb/` are ignored by git, so real member data is not committed by accident.

## Development

### Build and test

| Command        | What it does                                                        |
|----------------|---------------------------------------------------------------------|
| `make build`   | Builds `build/deploy/` (fat jar, `security.jar`, start scripts, reports) |
| `make test`    | Runs the unit tests; report in `build/reports/tests/test/index.html` |
| `make check`   | Formatting check (spotless) and tests                               |
| `make format`  | Formats all Java sources with google-java-format                    |
| `make clean`   | Deletes all build output                                            |

`make` uses a local JDK 21 if `java` is version 21, otherwise `docker/gradle-in-docker.sh`
(the Gradle cache is kept in the Docker volume `kernbeisser-gradle-cache`). Force either with
`USE_DOCKER=1` or `USE_DOCKER=0`. Without `make`, the wrapper also works directly:
`java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain shadowJar`.

> **Known issue:** many unit tests currently fail (they need a display, a database connection or
> the FontAwesome font). They had never actually run under Gradle before, because the test task
> used the JUnit 4 runner for JUnit 5 tests. `make build` therefore does not run the tests.

### Debugging

Both ways of running the app open a JDWP debug port (default `5005`, set `DEBUG_PORT` to change).
Attach with the run configuration `Attach debugger (localhost:5005)`, which exists in both IntelliJ
IDEA and VS Code (see [IDE setup](#ide-setup)).

To debug startup code, make the JVM wait for the debugger: `make debug SUSPEND=y` or
`make gui SUSPEND=y`.

The app must always be started with `-javaagent:security.jar` (the permission system is
implemented as a Java agent); the Makefile, the GUI container and the IDE run configurations take care
of that.

### IDE setup

Both IDEs launch `kernbeisser.Main` from source. Before each launch they run `make dev-setup`,
which starts the dev database, creates `.dev/config.json` and builds `security.jar` (needed for
`-javaagent`). A JDK 21 must be installed and configured in the IDE.

**IntelliJ IDEA:** open the project folder and let IntelliJ import the Gradle project. Shared run
configurations:

| Run configuration                  | What it does                                                   |
|------------------------------------|----------------------------------------------------------------|
| `Kernbeisser (dev DB)`             | Runs or debugs the app from source against the dev database    |
| `Attach debugger (localhost:5005)` | Attaches to `make debug` or `make gui`                         |
| `Kernbeisser in Docker (make gui)` | Starts the app in the noVNC container                          |
| `Tests (Gradle)`                   | Runs all unit tests                                            |
| `Dev setup (make dev-setup)`       | Runs automatically before `Kernbeisser (dev DB)`               |

**VS Code:** open the folder and install the recommended extensions when asked (Java Extension
Pack, Gradle, Docker, EditorConfig). *Run and Debug* has `Kernbeisser (dev DB)` and
`Attach debugger (localhost:5005)`; *Terminal → Run Task* lists the `make` targets. If the Java
extension reports missing `*_` classes (e.g. `Article_`), run `make dev-setup` once and then
*Java: Clean Java Language Server Workspace*.

`.editorconfig` sets the indentation that google-java-format expects (2 spaces). Run
`make format` before committing; spotless fails the build on formatting errors.

### Database

| Command           | What it does                                                 |
|-------------------|--------------------------------------------------------------|
| `make db-up`      | Start MariaDB and wait until it is ready                      |
| `make db-down`    | Stop it (data is kept in the Docker volume)                   |
| `make db-shell`   | MariaDB shell on the dev database                             |
| `make db-logs`    | Follow the database log                                       |
| `make db-demo`    | **Replace** the dev database with the demo data               |
| `make db-import DUMP=…` | Import a `.sql` or `.sql.gz` dump into the running database |
| `make db-dump [DUMP=…]` | Write the dev database to a dump file                 |
| `make db-reset`   | **Delete** the database volume                                |
| `make adminer`    | Database web UI on <http://localhost:8081> (server `db`)      |
| `make down`       | Stop all containers                                           |

The app reads its connection settings from a `config.json`. For `make run`/`make debug` it is
generated as `.dev/config.json` (delete it to regenerate after changing `docker/.env`); the GUI
container writes its own on every start.

### Remote machines

All ports are bound to `127.0.0.1` only. When the environment runs on a remote host, forward the
ports over SSH, e.g. `ssh -L 6080:localhost:6080 -L 5005:localhost:5005 -L 3306:localhost:3306 host`.

### Troubleshooting

- **Building the GUI image fails in a `RUN` step with "permission denied"** (seen with
  AppArmor-confined rootless Docker): set `DOCKER_BUILDKIT=0` in `docker/.env`.
- **Port already in use:** change `DB_PORT`, `NOVNC_PORT`, `DEBUG_PORT`, … in `docker/.env`.
- **GUI container exits immediately:** check `docker compose -f docker/compose.yaml logs gui`;
  usually `make build` has not been run, or the database is not reachable.

## Project layout

| Path           | Content                                                              |
|----------------|----------------------------------------------------------------------|
| `app/`         | The application (`kernbeisser.Main`)                                 |
| `security/`    | Java agent enforcing the permission system (`security.jar`)          |
| `kbmetamodel/` | Annotation processor generating the JPA metamodel classes (`Article_` …) |
| `reports/`     | JasperReports templates (`*.jrxml`)                                  |
| `production/`  | Start scripts and splash screen shipped in `build/deploy/`           |
| `docker/`      | Development environment (database, GUI container, Gradle in Docker)  |

## License

See [LICENSE](LICENSE).
