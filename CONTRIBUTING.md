# Contributing to Weft

Thanks for helping. A few rules keep the project readable and trustworthy.

## Explain the code in plain words

Weft's source should be readable by someone who is curious but doesn't write
Android apps. Whenever you add or change code:

1. **Every Kotlin file opens with a description.** After the package and
   imports, a `/** … */` comment says what the file is for, in plain language:
   what it does for the person typing, then how it fits with the rest.
2. **Every class, object, interface and enum, and every top-level function,
   gets a description** right above it. Functions inside a class get one too
   unless their name already says everything.
3. **Comments explain why, not what.** `// add one to i` helps nobody. "The
   app can take seconds to answer while a page loads, so this runs after the
   keyboard is on screen" does.
4. **Keep descriptions true.** If you change what code does, change its
   description in the same commit. A wrong comment is worse than none.
5. **Keep the map up to date.** When a file is added, removed or changes its
   job, update [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) (and the layout
   in the README if a folder changed).

Good descriptions use everyday words, give an example where one helps
("teh" → "the"), and say what is deliberately *not* done and why.

The build checks rules 1 and 2:

```sh
python3 .github/scripts/check-docs.py
```

Scripts under `tools/` and `.github/` follow the same idea: each starts with
a comment or docstring saying what it does and how to run it.

## Tests

- Behavior changes come with a test in `app/src/test/`.
- Anything that affects suggestions, autocorrect or swipe decoding must not
  make the benchmarks worse without saying so: check the "Benchmark" and
  "Swipe accuracy" lines in the build summary before and after. "Real words
  wrongly changed" must stay at 0.00%.

## FOSS rules

See [README › FOSS rules](README.md#foss-rules-for-this-repo): only
OSI-licensed dependencies, no Google Play Services or analytics, no network
access, and data only from sources whose licenses allow it (no share-alike or
copyleft data in the app). Every data source is credited in
[NOTICE](NOTICE).

## Commits and releases

- Write commit messages that say what changed for the user and why.
- Releases are made by adding `release-notes/vX.Y.Z.md`; the build tags the
  commit and publishes the APK (see README › Releasing).
