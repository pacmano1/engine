# The Ant-to-Gradle Migration and How to Verify It

**The short version:**

- The build system changed from Ant to Gradle. **The product it builds
  did not change**, and that claim is checkable: the output of both
  builds was compared archive by archive, file by file.
- 374 of the 419 library jars that used to be stored in the repository
  are now downloaded from Maven Central instead, and each one was
  accepted only after proving it is **the exact same file, bit for
  bit** (same SHA-1 fingerprint). The other 45 stay in the repository,
  each with a documented reason (listed in the appendix).
- Every check on the build's output ships in the repository, so any
  reviewer can re-run the verification rather than trust this
  document. The recipe is at the bottom.
- Substantial parts of this work were produced with AI assistance as
  a process experiment. The controls that made that safe, and the
  errors those controls caught, are described below.

## Why one change instead of piecemeal

None of the direction here is new. Nico Piel proposed the move to a
dependency-managed build (#52) and drove three successive Gradle
attempts (#54, #100, #214); jessesaga built a fourth (#243). In those
reviews Mitch Gaffigan named the bar a one-step replacement would
have to clear, byte-identical output, and he wrote the verification
toolkit this branch is cross-checked with. The three-commit structure
follows the reviewable-commit standard Tony Germano held the
project's last large dependency change (#146) to. This branch is
built on that groundwork.

The incremental path (wrap the old build first, convert one piece at
a time) is the default for a reason: replacing a build system in one
step is reckless **when you can't prove the output is the same**.

This branch takes the other path because the risk became measurable.
The bar is met, and here is exactly what "identical" means, including
everything that is *not* identical:

- **Every archive in the distribution, 490 of 490, has identical
  contents** to an Ant build of this branch's parent commit. Four
  things differ, all of them build-tool fingerprints rather than
  product content. (1) 121 archives carry a label inside saying which
  Ant version built them, which Gradle doesn't write; (2) file
  timestamps inside those same 121 archives, the ones the build itself
  writes, differ, because both tools stamp a fixed fake date for
  reproducibility and they chose different dates (Ant:
  1999-01-01, Gradle: its own constant), while third-party jars pass
  through byte-identical; (3) one small
  file (`version.properties`) embeds the build timestamp, so it
  differs between any two builds, including two Ant builds run back
  to back; (4) two jars contain one extra folder *entry* (not a file).
  Every class file, resource, script, and configuration byte is the
  same. The comparison tool classifies each of these categories
  itself; nothing was waved through by hand.
- **All 654 tests pass, and the list of test classes that ran is
  identical** to the Ant build's, class for class. One heads-up: the
  test counter shown in CI will drop to roughly half its historical
  "runs" number. That's because the old build accidentally wrote a
  duplicate results file that made CI count every test twice. The true
  count was always 654.
- **The already-published v4.6.0-rc1 release was cross-checked** against
  a local Ant build of the same source code: 473 of 490 archives are
  bit-for-bit identical. The 17 that differ are all explained by the
  release pipeline itself, not by compilation. Sixteen are third-party jars
  that the release's signing step repackaged (the packaging tool
  rewrites one bookkeeping file, `module-info.class`, when it touches
  a jar), and one (`PDFRenderer.jar`) gained a manifest line in the
  same step. None of the 17 differ in compiled code. The release file
  also contains installer files added after the build by the installer
  tool, and signature files, which can never match between any two
  builds because each signing run gets a fresh timestamp from a
  timestamp authority.
- **The signing path works end to end**: all 216 jars that ship signed
  were signed by the new build and pass Java's signature verifier
  (`jarsigner -verify`). The verifier's strict mode adds a warning
  about the certificate being self-signed, which is a property of the
  development keystore in the repository, not of the build; the old
  build's output warns identically.
- **The built product boots and runs.** A human boot test of the
  Gradle-built `server/setup` started the server and ran it normally,
  a quick functional check, not a full regression pass (an earlier
  automated attempt was discarded as contaminated; see the error
  ledger). With every archive proven content-identical, this is a
  sanity check on top of the archive comparison.

Once equivalence is proven, piecemeal is the riskier path: months of
running two build systems side by side, kept in sync by hand, with no
moment at which "they produce the same thing" is ever demonstrated. A
verified cutover is one reviewable event with the proof attached, and
it is still split into three commits, file moves, build swap,
dependency swap, so each concern can be reviewed on its own.

## What changed

1. **File moves** (commit 1): 1,806 source files moved to the standard
   directory layout that Gradle and most modern Java tools expect
   (`src/main/java`, `src/test/java`, and so on). git's rename detection
   confirms every one is a pure move, zero lines added, zero removed:
   `git show --stat -M100%` on the commit.
2. **The build swap** (commit 2): the new Gradle build, using a
   "wrapper" script committed to the repository, so contributors don't
   install Gradle; the wrapper downloads a pinned, checksum-verified
   copy. Everything lands where it always did: the runnable product in
   `server/setup`, the extension zips in `server/dist`. The Ant entry
   points (`server/build.xml`, `server/mirth-build.xml`) are replaced
   by stubs that print "this moved to ./gradlew" if anyone runs them;
   the subproject Ant files they drove are deleted. CI, the Dockerfile, and the contributor docs all
   use the new build. The developer conveniences that used to live in
   Eclipse project files are now build commands (`devRun`,
   `devLauncher`, `devClient`, `createDerbyDb`).
3. **The dependency swap** (commit 3): the 374 verified jars come from
   Maven Central; the 45 unverifiable files (32 distinct libraries)
   stay in the repository with per-jar reasons recorded: thirteen are
   content-identical to public copies but repackaged, three are
   genuinely modified locally, and sixteen were never published to
   Maven Central at all, one of them built in-house (the appendix
   lists every one). Three safeguards come with it: every download is
   checked against a recorded fingerprint on every build; the build
   fails loudly if a jar would silently go missing from the shipped
   product; and the helper-library automation is kept off so the set
   of shipped files cannot drift (see Limitations).

## What the distribution looks like

The shape of `server/setup`, identical from either build system
(counts generated from the built tree):

```
server/setup/
  cli-lib/                   55 files, 55 jars
  client-lib/                91 files, 91 jars
  conf/                       8 files
  docs/                     102 files
  extensions/               176 files, 125 jars, 40 extensions
  logs/                       0 files
  public_api_html/           53 files
  public_html/                3 files
  server-launcher-lib/        0 files
  server-lib/               217 files, 217 jars
  configure-from-env
  mirth-cli-launcher.jar
  mirth-server-launcher.jar
  oieserver
  oieserver.ps1
  oieserver.vmoptions
  oieservice.vmoptions
```

The diff between the Ant-built and Gradle-built trees, both built the
same day from the same commit:

```bash
$ diff <(cd ant-setup && find . -type f | sort) \
       <(cd gradle-setup && find . -type f | sort)
$            # empty: same files, same paths, same names

$ python3 tools/build-parity/compare_builds.py ant-setup gradle-setup
Common files: 712 (490 jars/zips)
Byte-identical files: 591
Jars/zips with fully identical entry contents: 490 / 490
Manifests differing only in Ant-Version/Created-By: 121
version.properties differing only in timestamp comment: 3

Directory-entry-only differences (2):
  client-lib/mirth-client.jar: dir entries ant-only=[] gradle-only=['com/mirth/connect/connectors/']
  server-lib/mirth-server.jar: dir entries ant-only=[] gradle-only=['com/mirth/connect/connectors/']

REAL differences (0):
```

The complete inventory of what is not byte-identical, and why each
category exists:

| What differs | Where | Why |
|---|---|---|
| A "built by Ant version X" label inside archives | 121 archives | Ant stamps its tool version into archives; Gradle doesn't |
| Timestamps on files inside archives | the same 121 archives the build writes | both tools stamp a fixed fake date for reproducibility; they picked different dates; third-party jars pass through untouched |
| The build date in `version.properties` | 3 copies of one file | it embeds the day of the build; two same-day **Ant** builds differ here too |
| One extra folder entry | 2 jars | Gradle records a parent-folder entry Ant omitted |


## How mistakes are caught before they ship

An obvious failure mode in a build with several coordinated files
(the dependency list, the placement map, the fingerprint file): one
gets updated and another forgotten. The design rule
used everywhere: **a mismatch must stop the build with a clear
message, never surface later as a broken product.**

- Update a library version but forget to refresh the fingerprints? The
  build fails before compiling anything, naming the library.
- A downloaded jar that has no assigned place in the shipped product?
  The build fails and names it, instead of quietly shipping without it.
- The startup files that list specific jar names are generated from
  the actual resolved versions, so they can't go stale (the old build
  had them hardcoded, and they *could*).
- Changing a library version is two steps, and forgetting either one
  is a build failure with instructions: edit one line in
  `gradle/libs.versions.toml`, then run
  `./gradlew --write-verification-metadata sha256 help`.

For changes to the build logic itself, CONTRIBUTING documents a
five-minute habit: fingerprint the built product before your change
and after (`./gradlew snapshotDistribution`), diff the two, and
confirm that only what you intended to change actually changed.

## How this was built

Substantial parts of this migration were produced with AI assistance:
the Gradle build scripts, the comparison and provenance tooling in
`tools/build-parity/`, the dependency audit, and the drafts of this
document. The experiment: can AI-driven work of this size be held to
machine-checked claims rather than taken on trust? The controls:

1. **The pass/fail bar was set before the work began**: the new build's
   output must match the old one, verified by machine. Every aggressive
   step (deleting 374 jars, moving 1,806 files, removing the old build
   system) happened behind that gate, and the gate caught real mistakes
   during development; the ledger below lists them.
2. **Machine claims were treated as unproven until re-executed.** This
   caught real errors.
3. **Every internal review ran its findings through a second,
   adversarial pass** whose job was to disprove them. Both passes were
   AI sessions; the second was started fresh, with none of the first
   one's context. The second pass re-ran the commands instead of
   trusting the first report. The ledger below shows one at work: the
   disproven "zero deprecation warnings" claim.
4. **Independent tooling.** The primary comparison tool
   (`tools/build-parity/compare_builds.py`) was itself written as part
   of this AI-assisted work, so passing it is partly self-grading. For
   that reason the output was additionally verified with
   [oie-release-verifier](https://github.com/mgaffigan/oie-release-verifier),
   the release-checking toolkit Mitch Gaffigan wrote for a different
   purpose (details in `tools/build-parity/README.md`).
5. **A human approved every irreversible step.** Reversible steps were
   automated; pushes, publications, and deletions required a person
   confirming the exact command. Approving commands is not code
   review, and neither is the output-equivalence proof. The build
   logic itself still needs human eyes, and the three-commit structure
   exists for that: the build swap is one readable unit, sized to be
   understood line by line before it merges.
6. **Discretionary improvements were kept out, even provably safe
   ones.** This change rests on one claim, "nothing changed", and
   every optional improvement folded in makes that claim harder to
   verify. Example: thirteen vendored jars
   turned out to be content-identical to their public copies and could
   safely become downloaded dependencies (see the appendix), and they
   were still left untouched here. Improvements like that are queued as
   small follow-ups behind the verification tooling this change leaves
   in the repository, where each one is independently checkable.

## The error ledger

These are the mistakes made during this work, and what caught each
one:

- An early wrong guess by the AI blamed a compiler path setting for
  output differences; the real cause was a compiler feature being
  silently disabled by Gradle's defaults. The output comparison
  (control 1) caught the difference; experiments found the true cause.
- The AI wrongly classified three libraries as "not available on Maven
  Central." Re-execution (control 2) caught it, and the cause matters:
  Maven Central's *search index* is missing fingerprint
  records for some files, so "no match found" from the search must be
  double-checked against the actual download server before being
  believed.
- An AI refactor accidentally dropped eleven historically renamed jars
  from the shipped product. The output comparison (control 1) flagged
  all eleven on the next run.
- An internal AI review claimed the build produced "zero deprecation
  warnings." A later cold review (a fresh AI session, given no context
  and told to verify everything) re-ran the command and proved that
  claim false, the build used a pattern that the next major Gradle
  version removes. The build was restructured; running it with
  `--warning-mode all` now shows none of the patterns the next major
  version removes (one further-out item remains, listed under
  limitations).
- The AI's first boot-test of the built server ran while another
  server instance was live on the same machine, so its "it boots and
  responds" result could not be attributed to the new build. A human
  caught that one (no automated control did), the claim was withdrawn,
  and runtime testing moved to a human-run boot, which succeeded: the
  server started and ran normally in a quick functional check.
- Project lore (repeated by the AI in early drafts of this document)
  held that the HAPI 2.3 jars were locally patched. A human question
  prompted an entry-by-entry comparison: they are content-identical to
  the public copies, only repackaged. The same check found the search
  API's version list silently omits older versions, which had caused
  one jar to be wrongly labeled "never published."

## Known limitations

- **Adding a new library takes more manual work than in a typical
  Gradle project.** Modern builds automatically fetch the helper
  libraries each library needs. That automation is switched off here:
  this build downloads exactly the 374 files the old build
  shipped, nothing extra, so the product provably could not change.
  The cost shows up only when someone adds a brand-new library: they
  must list its helpers by hand, and the build tells them when one is
  missing. The way out is gradual: whenever a library is upgraded
  anyway, switch the automation on for just that library and check the
  result with the comparison tool. (Technical name: non-transitive
  resolution.)
- **The build logic is centralized and plain, not idiomatic.** It is
  a few large Groovy files that transcribe the old Ant build
  one-to-one, with no unit tests of their own; correctness is guarded
  by the output comparison and the fingerprint habit above. Gradle
  idiom would restructure this into tested convention plugins,
  possibly in the Kotlin DSL. That restructuring is left for its own
  change, after this one proves the outputs.
- **One optional Gradle speed feature doesn't work with this build
  yet.** Gradle can cache its own startup work to make repeat builds
  faster (the "configuration cache"). Some of our build steps use an
  older style that this feature rejects, and one of those calls is
  slated for removal in Gradle 10, which is years away. Today's build
  pins Gradle 8.14.1 and is unaffected. This is cleanup for a future
  change, and it is tracked.
- **Signed release builds are about five minutes slower.** The old
  build signed four jars at a time; this one signs them one at a time.
  Only release builds with signing enabled notice; everyday development
  builds skip signing entirely.
- **Of the three commits, only the last one builds.** The middle
  commit (the build-system swap) refers to the dependency files the
  third commit introduces. Anyone using git's per-commit testing
  (bisect) will hit that one commit; it is split this way so each part
  can be *read* in isolation, which mattered more for review.
- **A few test classes have never run, before or after.** Classes
  whose names end in "Tests" (plural) don't match the test pattern,
  which only picks up names ending in "Test". That was true under Ant
  and is preserved unchanged here so this change alters nothing.
  Fixing it is a separate, simple follow-up.
- **The equivalence proof covers the cutover day only.** It proves the
  new build produced the same product as the old one at handover. It
  cannot prove anything about future changes.
  What protects every day after: each downloaded library is checked
  against a recorded fingerprint on every build; the build fails if a
  file would silently go missing from the distribution; the 654 tests
  run on every build; and CONTRIBUTING documents the five-minute
  fingerprint habit for anyone changing build logic.
- **Output claims are verifiable; the process claims are not.** Every claim
  about the build's output can be verified by running the commands
  below. The claims about how the work was done (the controls, the
  error ledger) cannot be checked: there are no records to run, so
  that part is take-the-author's-word. It also doesn't matter: even a
  reader who ignores the process story entirely can verify the build
  the same way.

## Appendix: the 45 jars that stay in the repository

Generated from the committed audit record
(`tools/build-parity/jar-provenance.json`), which records each jar's
fingerprint, size, status, and the reason it stays. Jars present in
more than one module are listed once with all locations.

### Same contents as the public copy, different wrapper

A jar is a zip container. Each of these was unpacked and compared
file by file against its Maven Central copy: every file inside is
identical. The
jar file itself still fingerprints differently, because zip containers
also record packing order, timestamps, and compression choices. The
replacement rule in this migration was strict, the whole
downloaded file had to be bit-for-bit identical to the vendored one,
so these stayed in the repository.

Follow-up for this group: since these jars are
proven content-identical to their public copies, they could safely
move to downloaded dependencies later, the proof that it changes
nothing already exists. The repository would shrink accordingly; the
HAPI set alone is stored twice today (client and server keep separate
copies, 18 files), and would become nine references fetched once and
placed into both distribution directories at build time. They stay
vendored in this change only because the adoption rule was strict
file-level identity, and this change promises to alter nothing. The
thirteen:

| Jar | Module(s) |
|---|---|
| `autocomplete-2.5.4.jar` | client |
| `hapi-base-2.3.jar` | client+server |
| `hapi-structures-v21-2.3.jar` | client+server |
| `hapi-structures-v22-2.3.jar` | client+server |
| `hapi-structures-v23-2.3.jar` | client+server |
| `hapi-structures-v231-2.3.jar` | client+server |
| `hapi-structures-v24-2.3.jar` | client+server |
| `hapi-structures-v25-2.3.jar` | client+server |
| `hapi-structures-v251-2.3.jar` | client+server |
| `hapi-structures-v26-2.3.jar` | client+server |
| `jtds-1.3.1.jar` | donkey+server |
| `rsyntaxtextarea-2.5.6.jar` | client |
| `sqlite-jdbc-3.43.2.1.jar` | server |

### Genuinely changed from the public copy

These three were unpacked and compared the same way, and the
differences are real: compiled code inside differs from the copy on
Maven Central (114 classes in
`javaparser`, 60 in `zip4j`, 2 in `not-going-to-be-commons-ssl`).
These are local forks; replacing them with a download would change the
product.

The follow-up these need is archaeology, not packaging: recover why
each was changed, then either carry the change forward onto a current
version, get it upstreamed, or replace the library. Until someone does
that, they must stay vendored. The three:

| Jar | Module(s) |
|---|---|
| `javaparser-1.0.8.jar` | client |
| `not-going-to-be-commons-ssl-0.3.18.jar` | server |
| `zip4j_1.3.3.jar` | client+server |

### Nothing on Maven Central to replace them with

Some of these never appeared on Maven Central at all; for the rest,
every version Maven Central has ever offered under the same name was
fingerprint-checked and none matches our bytes. The check was scoped
to Maven Central: copies hosted in other repositories (or
shown by aggregator sites like mvnrepository.com, which index far more
than Central) were never candidates, because this build resolves from
Maven Central and nowhere else. The per-version sweep was a one-time
manual effort; `tools/build-parity/sweep_provenance.py
--classify-none` re-probes it, subject to the search-index caveat
documented in `tools/build-parity/README.md`.

There is no mechanical follow-up here: moving any of these to a
download would mean switching to a different version with different
bytes, which is an upgrade decision, not a packaging
cleanup. The ten:

| Jar | Module(s) |
|---|---|
| `PDFRenderer.jar` | server |
| `backport-util-concurrent-Java60-3.1.jar` | server |
| `istack-commons-runtime-3.0.6.jar` | client+server |
| `jai_imageio.jar` | client+server |
| `language_support.jar` | client |
| `looks-2.3.1.jar` | client |
| `openjfx.jar` | client |
| `webdavclient4j-core-0.92.jar` | server |
| `wizard.jar` | client |
| `wsdl4j-1.6.2-fixed.jar` | server |

### Known origins outside Maven Central

The five dcm4che jars came from the dcm4che.org project site, which
never published this series
to Maven Central; `mirth-vocab.jar` is built in-house by this
repository's own generator module.

The dcm4che set is in the same position as the group above: vendored
until someone chooses an upgrade. `mirth-vocab.jar` has a cleaner
follow-up, since this repository can already build it from source; the
open question is only whether a rebuild reproduces the shipped jar
exactly. The six:

| Jar | Module(s) |
|---|---|
| `dcm4che-core-2.0.29.jar` | server |
| `dcm4che-filecache-2.0.29.jar` | server |
| `dcm4che-net-2.0.29.jar` | server |
| `dcm4che-tool-dcmrcv-2.0.29.jar` | server |
| `dcm4che-tool-dcmsnd-2.0.29.jar` | server |
| `mirth-vocab.jar` | server |

32 distinct jars, 45 files in total across modules.

## How to re-verify

The complete check needs JDK 17, Apache
Ant (the acceptance run used 1.10.14), Python 3, and network access
for the first Gradle run, which downloads Gradle itself and the 374
dependencies, all checksum-verified; the Ant baseline builds offline
from the vendored jars. Run it from the root of this branch:

```bash
# Ant baseline at the migration's parent commit (this branch is three
# commits; its parent is 8c1111ba3)
git worktree add /tmp/oie-ant 8c1111ba3
(cd /tmp/oie-ant/server && ant -f mirth-build.xml -DdisableSigning=true -DdisableTests=true build)

# Gradle build of this branch. Use the same JDK build for both builds
# (compiled classes differ across JDK builds) and the same calendar
# day (version.properties embeds the build date).
./gradlew clean build dist -DdisableSigning=true

# Compare every archive, entry by entry
python3 tools/build-parity/compare_builds.py /tmp/oie-ant/server/setup server/setup

# Cleanup (also clears a failed earlier attempt)
git worktree remove --force /tmp/oie-ant
```

Expected: `REAL differences (0)`; the comparison classifies the known
tool-fingerprint deltas itself. For scale: the Ant baseline builds in
seconds; that is normal, not a short-circuit. The Gradle build takes a
few minutes with warm caches and runs the full test suite. The
worktree costs about a gigabyte of disk until removed.

One expected wrinkle: a successful Gradle build ends with Gradle's
stock banner about deprecated features being "incompatible with
Gradle 9.0." Running with `--warning-mode all` shows what it actually
refers to: the one disclosed item under Known limitations, a call
scheduled for removal in Gradle 10, not 9; the banner's text is
generic.

The dependency audit is re-checkable with
`python3 tools/build-parity/sweep_provenance.py --verify-exact`, which
re-downloads every matched jar's checksum from Maven Central; expect
`0 mismatches`. Every build also re-verifies fingerprints and
placements as a side effect of building. The remaining evidence
routes, the independent release-verifier toolkit, the signed-build
check, and the published-rc1 cross-check, each have their own recipe
in `tools/build-parity/README.md`.
