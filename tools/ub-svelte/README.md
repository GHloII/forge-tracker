# Svelte script support for UB tools

These patches extend pinned upstream versions of Crapper, Dryer, and Mutator.
They discover `.svelte` files and analyze inline instance and module scripts as
TypeScript. The adapter masks markup and styles while preserving every newline
and UTF-8 byte offset. Mutation edits therefore target the original component,
including when non-ASCII text appears before its script. Worker dependency
discovery includes component imports without confusing `Component.svelte` with
`Component.ts`.

Template expressions, inline template event handlers, CSS, and external scripts
are excluded. Function selection follows each tool's existing TypeScript rules.
This is script support, not a full Svelte compiler or template analysis.
Crapper still needs source-mapped LCOV to report coverage and CRAP scores;
without coverage, use `--no-coverage` for complexity only. A mutation scan finds
candidates; it does not prove the tests kill them.

Install into an existing dedicated Python environment (Python 3.11+, Git and uv):

```powershell
./tools/ub-svelte/install.ps1 -Python /path/to/venv/Scripts/python.exe
```

The installer clones the three public repositories into `.tools/ub-svelte`,
checks pinned revisions, applies patches idempotently, and installs normal
packages into the supplied environment. It refuses a different checkout revision
or a patch conflict. It does not modify a system Python or publish upstream.
Patches include regression tests and can also be applied to separate checkouts
using `git apply`.

Validation: new Svelte tests cover discovery, original source locations, Unicode
mutation offsets, module and instance scripts, ignored markup, duplicate
comparison with TypeScript, and transitive component dependencies. Windows has
existing upstream test failures involving POSIX commands, shell quoting, and
symlinks; those are separate from Svelte support.
