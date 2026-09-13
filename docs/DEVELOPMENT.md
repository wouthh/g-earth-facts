# Development notes

`scripts/bootstrap.py` downloads the immutable G-Earth source archive, applies
the bounded public API frame-reader correction used by the companion projects,
and installs only `G-Earth-Api` into `.build/m2`. The host application is never
built or modified by the project.

The main JAR is shaded so the folder extension can run from its own working
directory. `packaging/assembly.xml` creates a G-Earth folder ZIP containing the
JAR, `command.txt`, README, license, notices and runtime dependency licenses.
Build output and the private Maven/API cache are ignored. The package checker
also runs synthetic Steam-view install, collision and rollback tests without
opening a game connection.

The scheduler owns a single generation-bound scheduled executor. HTTP futures,
multipart parts and room transitions all re-check that generation before doing
work. Tests inject fake clients and short durations; no test uses elapsed ten
minutes or a real provider.
