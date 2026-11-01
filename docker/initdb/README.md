# Database seed

For the demo data use `make db-demo` instead (see `docker/demo/`). For other data, put a
MariaDB/MySQL dump here (`*.sql` or `*.sql.gz`). The MariaDB container imports every
`*.sql`, `*.sql.gz` and `*.sh` file in this folder in alphabetical order, **but only when the
database volume is created for the first time**.

To re-import after changing the files here:

```sh
make db-reset    # deletes the database volume
make db-up       # recreates it and imports the dump
```

Alternatively import a dump into the running database without resetting: `make db-import DUMP=path/to/dump.sql`.

Dumps are ignored by git (see `.gitignore`), so real data never gets committed by accident.
