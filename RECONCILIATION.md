# Onboarding redesign — test reconciliation record

The test drafts on this branch were written against the redesign
contract in parallel with the implementation
(`feat/onboarding-redesign-flow`), then rebased onto it and
reconciled. Everything below compiles and runs green against the real
code; this file records what had to change during reconciliation and
the few contract nuances worth knowing at review time. Safe to drop
once the PR text absorbs it.

Files:

- `app/src/androidTest/kotlin/app/onym/android/uitests/OnboardingWalkUITest.kt`
  — `smokeDefaultPathCompletesThenNeverAgain` + `customServicesPathWalk`
  replace the three tests the implementation `@Ignore`d; the live
  gate tests (back navigation, completed-at-boot, no-slot bypass)
  were kept from the implementation side.
- `app/src/androidTest/kotlin/app/onym/android/uitests/screens/OnboardingScreenObject.kt`
  — page object for the full redesign tag vocabulary.
- `modules/onboarding/src/test/kotlin/app/onym/android/onboarding/OnboardingFlowContractTest.kt`
  — pure-JVM contract suite (indicator coverage, gate matrix, seeded
  services, sub-state persistence), complementing the implementation's
  own `OnboardingFlowTest`.

## What the reconciliation changed (draft → implementation)

1. `RecoveryBackupState.NotStarted` → the implementation's
   `RecoveryBackupState.None` (`None`/`Revealed`/`Verified`, ordinal
   never-downgrade).
2. `requiresOutcomeToAdvance(Moderation)` with
   `moderationEnabled = false`: the draft asserted `false`; the
   implementation delegates to `isMandatory`, which fail-closed
   answers `true` while the probe is unresolved — deliberately
   meaningful only for steps in `OnboardingFlow.steps`. The test now
   asserts the step is out of the sequence instead of probing a
   query the flow never consults for a live step.
3. Published notary add: the row (`…published.<url>`) and its Add
   affordance (`…published.add.<url>`) are separate nodes — the
   draft tapped the row; the test now taps the button.
4. The random-strategy notary footnote is an UNTAGGED
   `SettingsFootnote` — asserted by its distinctive copy
   ("at random", substring) rather than a tag, and only after the
   first endpoint is added (the footnote renders under the
   configured list).
5. The recovery reveal walk uses the implementation's new
   `recovery.backup.{continue,reveal}` tags instead of the draft's
   text finders; leaving the backup surface goes through its top-bar
   back IconButton (content description "Back" — unambiguous, the
   walk frame's Back is a text button, and the frame carries no
   content descriptions).
6. Consent walk: an explicit `module_consent.offer.courier-free-v1`
   tap before Accept (idempotent when preselected).

Everything else in the draft matched the implementation as built:
step enum + tags, `Indicator(index, count)` / `indicator(step)`
(moderation counted as a third core step when enabled),
`StepOutcome.Unavailable`, seeded `Consented(null)` services outcome,
`ServicesChoice` / `recordServicesChoice`, `recordRecoveryBackup`,
only-recovery skippable, primary labels ("Create my identity" /
"I've written it down" / "Start messaging"), "Remind me later" skip,
seat back tags, catalog `onboarding.services.<seat>.catalog.<componentId>`,
configured/`published` row tags, directory TOFU
`.confirm/.fingerprint/.pin/.added`, `UITestRegistry.biometricAuthenticator`.

## Review-fix round (9dda816, bbc5ef1)

Rebased onto the implementation's review fixes; no test changes were
needed, verified rather than assumed:

- The backup overlay now scrubs its ViewModel on dispose (reset() →
  Intro). The walk's recorded outcome lives on the FLOW
  (`recordOutcome`/`recordRecoveryBackup`), not the VM, so exiting
  the overlay keeps the primary unlocked; any FUTURE test that
  re-enters the backup surface must walk intro → biometric → reveal
  again from scratch.
- DirectoryContent resets its add-phase UI on dispose — re-entry
  shows the live pinned state, not the "Provider confirmed" hero.
  The walk visits the seat once; a re-entry test should await the
  `.pinned` chip, not `.added`.
- The reworded hub use-recommended footnote and the consent
  BackHandler are unasserted (consent is dismissed via
  `module_consent.done_button`); the notary "at random" footnote
  string is untouched.

## Review-fix round 2 (36531f3)

- `outcomeSatisfiesGate`: a Skipped outcome no longer satisfies an
  outcome-gated step. Covered on BOTH layers:
  - contract test `skipThenBack_reLocksTheGate_untilConsentedOverwrites`
    (advance refused over surviving Skipped; skip-again allowed;
    Consented overwrite unlocks) — and `walkTo` now drives via the
    real predicate;
  - the instrumented smoke gained the end-to-end leg: "Remind me
    later" → Done → Back → assert the recovery primary is DISABLED
    again → skip again → Done.
- Step-scoped overlay visibility and the guarded hub records are
  behavior-neutral for these walks (each overlay is entered only on
  its own step).
- Rebase conflict in OnboardingWalkUITest.kt (the implementation
  deleted the gutted @Ignore stub this branch had already replaced)
  — resolved keeping this branch's rewrite; the @EmptyDiscoverySources
  registry seam upstream kept for future hub-loading tests is not
  yet used here.

## Review-fix round 3 (d833baa, 2f580b4)

No test changes needed; verified on device rather than assumed:

- `UITestRegistry.debugActive` (enabled && BuildConfig.DEBUG) now
  gates the security-weakening seams this suite uses
  (`biometricAuthenticator`, `identitySecretStore`,
  `discoveryClock`). Instrumented tests always run the debug build,
  so all three stay live — the biometric-fake reveal leg passed on
  device post-change. Release fails closed; nothing to adjust here.
- Hub NavController hoist + closeHub() popping to the hub root: the
  walks re-enter the hub between seats via the same pop path and
  stayed green (seat VM clearing is invisible to the assertions —
  each seat is visited once).
- `identityReady` as a required AppDependencies parameter: this
  suite never constructs AppDependencies directly (all wiring goes
  through `OnymApplication.rebuildDependenciesForTest()`), so no
  change.
- The new `:onboarding` lint gate runs as part of `:onboarding:test`
  builds — green over the contract suite.

## Review-fix round 4 (8d50e4f)

- PIN-ON-ACCEPT, asserted end-to-end in the smoke test. The harness
  registers the fixture discovery fetcher + the pinned clock, so the
  recommended path's programmatic TOFU deterministically SUCCEEDS:
  the smoke asserts nothing is pinned while ON the services step,
  polls the store for the seeded source's operator key after
  advancing past it (the pin runs async in the host scope), and then
  asserts the Done summary's honest PINNED state — fingerprint
  detail present, "Not confirmed" absent
  (`assertDoneDirectoryPinned`). The unpinned/"Not confirmed" branch
  is intentionally not exercised here (it would need a fetcher-less
  or failing-fetch variant); `assertDoneDirectoryNotConfirmed` is in
  the page object for that future test. The custom walk asserts the
  same pinned summary after its manual hub pin.
- No drift: the tests never referenced `chipColor`, the
  `showSkipProgress` scaffold slot, `addRelayerEndpoint`, or the
  seat-VM factory signatures (all wiring goes through
  `rebuildDependenciesForTest()`); the new
  `onboarding.services.stays_configured` footnote is Custom-path
  copy the walks don't assert.

## Review-fix round 5 (83468dd)

- Rebase conflict: only the old walk test's rewritten KDoc — resolved
  keeping this branch's file (its KDoc describes the merged suite).
- Hub-Done pin trigger: in the custom walk the Directory seat is
  interactively pinned first (deliberately, so the other seats'
  catalogs populate), so hub-Done's unconditional pinner call reads
  AlreadyPinned and no-ops — the walk's store + Done-summary
  assertions are unchanged and stayed green. The interesting new
  branch (custom setup that LEAVES the Directory seat alone still
  ends pinned) is NOT instrumented here — it would cost a third full
  walk — and is covered at the unit level by
  RecommendedDirectoryPinnerTest's hub-Done cases.
- addKnown(onAdded:) callback consent: the group-integrity leg
  awaits the configured row (UI state), not the outcome record —
  no timing assumption to adjust; green on device.
- ServicesCard selectable/RadioButton semantics: the page object
  addresses the cards by test tag, and `selectable`'s descendant
  merging doesn't affect tag lookup — no change needed.

## Lint-annotation commit (not authored by the tests branch)

While this branch was being reconciled, a local commit ("Lint:
allow-annotate recoveryPhrase test-tag literals in the page object")
was added to it from the shared repo (worktrees share branches —
presumably the implementation side keeping `scripts/lint-secrets.py`
green across branches). Verified legitimate: `onym:allow-secret-read`
is the sanctioned suppression marker consumed by the default-deny
secret-read lint, the four annotated sites are UI test-tag string
literals (comment-only change, zero behavior), the annotations mirror
the implementation's own in OnboardingHost.kt, and the lint passes.
The commit is kept as its own commit with its original message.

## Notes for the record

- Honest-copy assertions verified against the implementation: Nostr
  configured rows pass `chip = null` explicitly (no PRIMARY/BACKUP);
  the media ACTIVE chip sits on index 0 only; the notary chip/footnote
  switch on the ACTUAL `RelayerConfiguration.selectUrl` resolution
  (never a PRIMARY chip under the default RANDOM strategy).
- Grandfathering ("existing user → no walk") still has no
  instrumented coverage through the real probe wiring — by design;
  see the walk test's KDoc.
