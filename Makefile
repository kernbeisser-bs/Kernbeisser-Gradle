# Kernbeisser development tasks. Run `make` (or `make help`) for an overview.

-include docker/.env

DB_NAME          ?= kernbeisser
DB_USER          ?= kernbeisser
DB_PASSWORD      ?= kernbeisser
DB_PORT          ?= 3306
DEBUG_PORT       ?= 5005
NOVNC_PORT       ?= 6080
ADMINER_PORT     ?= 8081

# Set DOCKER_BUILDKIT=0 in docker/.env where BuildKit cannot run RUN steps (restricted rootless Docker).
ifdef DOCKER_BUILDKIT
export DOCKER_BUILDKIT
endif

# Suspend the JVM until a debugger attaches: make debug SUSPEND=y
SUSPEND          ?= n

COMPOSE          := docker compose -f docker/compose.yaml
DEPLOY_DIR       := build/deploy
DEV_DIR          := .dev
DEV_CONFIG       := $(abspath $(DEV_DIR))/config.json

# Use a local JDK 21 if there is one, otherwise run Gradle in Docker. Force with USE_DOCKER=1/0.
JAVA_VERSION     := $(shell java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
ifeq ($(JAVA_VERSION),21)
USE_DOCKER       ?= 0
else
USE_DOCKER       ?= 1
endif

ifeq ($(USE_DOCKER),1)
GRADLE           := docker/gradle-in-docker.sh
else
GRADLE           := java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain
endif

JAVA_RUN_OPTS    := -Xmx1024m -splash:KernbeisserSplash.png -javaagent:security.jar
JDWP_OPTS        := -agentlib:jdwp=transport=dt_socket,server=y,suspend=$(SUSPEND),address=localhost:$(DEBUG_PORT)

.DEFAULT_GOAL := help
.PHONY: help build test check format clean dev-config dev-setup run debug \
        db-up db-down db-logs db-shell db-import db-demo db-dump db-reset \
        gui gui-down adminer down

help: ## Show this help
	@echo "Gradle runs $(if $(filter 1,$(USE_DOCKER)),in Docker (no local JDK 21 found),with the local JDK $(JAVA_VERSION))."
	@echo
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

## --- Build --------------------------------------------------------------

build: ## Build the deployable app into build/deploy (no tests)
	$(GRADLE) shadowJar

test: ## Run the unit tests (report: build/reports/tests/test/index.html)
	$(GRADLE) test

check: ## Run formatting check and tests
	$(GRADLE) check

format: ## Format the Java sources (google-java-format via spotless)
ifeq ($(USE_DOCKER),1)
	$(GRADLE) --export app/src spotlessApply
else
	$(GRADLE) spotlessApply
endif

clean: ## Delete all build output
	rm -rf build kbmetamodel/build security/build

## --- Run locally (needs JDK 21 and a display) ---------------------------

dev-config: $(DEV_CONFIG)

dev-setup: db-up dev-config ## Prepare for running from the IDE (DB, .dev/config.json, security.jar)
	$(GRADLE) :security:jar :kbmetamodel:jar

$(DEV_CONFIG):
	@mkdir -p $(DEV_DIR)
	@printf '%s\n' \
	  '{' \
	  '  "dbAccess": {' \
	  '    "url": "jdbc:mariadb://localhost:$(DB_PORT)/$(DB_NAME)",' \
	  '    "username": "$(DB_USER)",' \
	  '    "password": "$(DB_PASSWORD)",' \
	  '    "encoding": "UTF-8"' \
	  '  },' \
	  '  "reports": {' \
	  '    "reportDirectory": { "path": "reports" },' \
	  '    "outputDirectory": { "path": "$(abspath $(DEV_DIR))/output" }' \
	  '  }' \
	  '}' > $@
	@echo "Created $@ (edit it or delete it to regenerate)"

run: build dev-config db-up ## Build and start the app locally against the dev database
	cd $(DEPLOY_DIR) && java $(JAVA_RUN_OPTS) -jar kernbeisser.jar -configFile:$(DEV_CONFIG)

debug: build dev-config db-up ## Like run, with a debugger port (DEBUG_PORT=5005, SUSPEND=n|y)
	@echo "Attach your debugger to localhost:$(DEBUG_PORT)"
	cd $(DEPLOY_DIR) && java $(JDWP_OPTS) $(JAVA_RUN_OPTS) -jar kernbeisser.jar -configFile:$(DEV_CONFIG)

## --- Run in Docker (browser via noVNC, works on headless machines) -------

gui: build ## Build and start the app in Docker; open it in the browser, debug on port 5005
	docker build -q -t kernbeisser-gui docker/gui
	JDWP_SUSPEND=$(SUSPEND) $(COMPOSE) --profile gui up -d --force-recreate gui
	@echo "App:      http://localhost:$(NOVNC_PORT)/vnc.html?autoconnect=1&resize=remote"
	@echo "Debugger: localhost:$(DEBUG_PORT)    Logs: $(COMPOSE) logs -f gui"

gui-down: ## Stop the GUI container
	$(COMPOSE) --profile gui stop gui

## --- Database -----------------------------------------------------------

db-up: ## Start the MariaDB container and wait until it is ready
	$(COMPOSE) up -d --wait db

db-down: ## Stop the database (data is kept)
	$(COMPOSE) stop db

db-logs: ## Follow the database logs
	$(COMPOSE) logs -f db

db-shell: ## Open a MariaDB shell on the dev database
	$(COMPOSE) exec db sh -c 'exec mariadb -u"$$MARIADB_USER" -p"$$MARIADB_PASSWORD" "$$MARIADB_DATABASE"'

db-import: ## Import a dump into the running database: make db-import DUMP=file.sql[.gz]
	@test -n "$(DUMP)" || { echo "usage: make db-import DUMP=file.sql[.gz]"; exit 1; }
	$(if $(filter %.gz,$(DUMP)),gunzip -c,cat) "$(DUMP)" | \
	  $(COMPOSE) exec -T db sh -c 'exec mariadb -uroot -p"$$MARIADB_ROOT_PASSWORD" "$$MARIADB_DATABASE"' >/dev/null

db-demo: db-up ## REPLACE the dev database with the demo data (every user's password: password)
	@read -p "This replaces all data in the dev database with the demo data. Continue? [y/N] " a && [ "$$a" = y ]
	$(COMPOSE) exec -T db sh -c 'exec mariadb -uroot -p"$$MARIADB_ROOT_PASSWORD" \
	  -e "DROP DATABASE IF EXISTS \`$$MARIADB_DATABASE\`; CREATE DATABASE \`$$MARIADB_DATABASE\`"'
	$(MAKE) --no-print-directory db-import DUMP=docker/demo/kernbeisser-demo.sql.gz
	$(MAKE) --no-print-directory db-import DUMP=docker/demo/shift-dates.sql
	@echo "Demo data loaded. Log in as Admin (or any other user) with the password: password"

db-dump: ## Dump the dev database to a file: make db-dump [DUMP=file.sql]
	$(COMPOSE) exec -T db sh -c 'exec mariadb-dump -uroot -p"$$MARIADB_ROOT_PASSWORD" --single-transaction --routines "$$MARIADB_DATABASE"' \
	  > "$(or $(DUMP),kernbeisser-$(shell date +%Y%m%d-%H%M%S).sql)"

db-reset: ## DELETE the database volume (re-imports docker/initdb on next db-up)
	@read -p "This deletes all data in the dev database. Continue? [y/N] " a && [ "$$a" = y ]
	$(COMPOSE) down -v

adminer: ## Start Adminer (database web UI) on port 8081
	$(COMPOSE) --profile tools up -d adminer
	@echo "Adminer: http://localhost:$(ADMINER_PORT)  (server: db, user: $(DB_USER))"

down: ## Stop all containers (data is kept)
	$(COMPOSE) --profile gui --profile tools down
