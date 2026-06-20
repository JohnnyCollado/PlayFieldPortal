---
name: kotlin-platform-app-links-and-deep-links
description: Use when designing, implementing, or reviewing Android deep links, web links, and App Links in KMP projects — intent-filter design, host verification, manifest scope, and assetlinks.json configuration.
license: Apache-2.0
metadata:
  author: Mariano Miani
  version: "1.1.0"
---

# Android App Links and Deep Links

Use this skill when designing, implementing, or reviewing deep-linking behavior in a Kotlin Multiplatform project with Android support.

This skill is intentionally strict. Its purpose is to keep Android deep links, web links, and App Links structurally correct, verified where appropriate, compatible with realistic navigation behavior, and separated cleanly from shared route interpretation logic.

## Primary goals

The deep-linking design should optimize for:

- clear distinction between deep links, web links, and verified App Links
- manifest declarations that match intended URL ownership
- host verification that is reliable and maintainable
- realistic navigation and back-stack behavior after external entry
- separation between Android registration/verification and shared route handling
- minimal ambiguity about which URLs should open the app
- support for server-side refinement where Dynamic App Links are used

Do not treat all incoming URLs as equivalent.
Treat verified App Links as a distinct architectural surface.

---

## Official defaults to prefer

Unless the project has a strong reason not to, prefer:

- Android App Links for owned HTTPS web domains
- custom URI schemes only when web-link verification is not the right tool
- explicit intent filters with `VIEW`, `DEFAULT`, and `BROWSABLE`
- separate intent filters for unique URL combinations instead of relying on merged `<data>` semantics
- broad manifest host scope with finer path-level refinement in `assetlinks.json` when using Dynamic App Links on Android 15+
- Android registration and verification in platform code
- shared route interpretation in shared/navigation code

---

## Link-type distinctions

### 1. Custom deep links
Examples:
- `myapp://product/123`

Use when:
- links come from controlled sources
- you need non-web routing
- website ownership/verification is not the main requirement

Risks:
- not standard web links
- can still trigger chooser/disambiguation issues if other apps claim the same custom scheme

### 2. Web links
Examples:
- `https://example.com/product/123`

These are standard web URLs. Without App Link verification, Android may still show disambiguation or route to the browser depending on app/user/system state.

### 3. Android App Links
These are verified HTTP(S) web links associated with your website. They provide the strongest user-trust and ownership model for opening app content from your web domains.

Review expectation:
- use App Links for domains the app genuinely owns and wants to claim as app entry points

---

## Review dimensions

### 1. URL ownership and link-type choice

Check whether the proposal uses the right link type for the job.

Prefer:
- App Links for owned website domains
- web URLs where website fallback matters
- custom schemes only when they are truly appropriate

Flag as a concern when:
- custom schemes are used where verified HTTPS ownership would be better
- App Links are declared for domains the team does not fully control
- multiple unrelated link types are mixed with no clear reason

### 2. Intent-filter correctness

For Android deep links and App Links, review whether the manifest filters are structurally correct.

Expected pieces:
- `android.intent.action.VIEW`
- `android.intent.category.DEFAULT`
- `android.intent.category.BROWSABLE`

Check whether:
- filters declare the intended scheme, host, and optional path scope
- filters are as clear as possible
- each filter corresponds to a coherent URL family

Important review rule:
- do not casually combine unrelated `<data>` elements in one filter, because Android merges them and may create unintended URL combinations

Flag as a concern when:
- categories or action are incomplete
- one filter accidentally matches more URLs than intended
- path scoping is implicit and hard to reason about
- filter merging creates accidental combinations

### 3. App Link verification model

Review whether host verification is designed correctly.

Check whether:
- host verification is expected and supported for every declared App Link host
- the team understands that verification behavior differs by Android version
- multi-host declarations are intentional

Important platform behavior:
- on Android 12+, if multiple hosts are declared, the system attempts to verify each one independently, and any verified host can become the default handler for that host
- on Android 11 and lower, verification can fail for the whole set if one declared host cannot be verified

Flag as a concern when:
- many hosts are bundled casually into one design without operational ownership
- verification assumptions ignore Android-version differences
- a failed host can silently undermine the intended UX

### 4. Manifest scope strategy

Review whether static manifest rules are scoped well.

For Dynamic App Links, Google recommends:
- broad manifest scope, such as scheme + domain only
- finer refinement on the server side in `assetlinks.json`

Check whether:
- static manifest rules are broad enough to support future refinement
- static rules are not overly narrow if server-driven path changes are expected
- static rules still avoid claiming unrelated domains or schemes

Flag as a concern when:
- manifest rules are too narrow for expected evolution
- manifest rules are too broad for domains the app should not own
- the manifest tries to encode all path logic when server-side refinement is intended

### 5. assetlinks.json correctness

Review website association files carefully.

Check whether:
- each supported host serves its own `/.well-known/assetlinks.json`
- the file is served over HTTPS
- the content type is `application/json`
- the file is accessible without redirects
- the JSON associates the correct package name and certificate fingerprints
- the relation includes `delegate_permission/common.handle_all_urls` where full App Link handling is intended

Flag as a concern when:
- the file is missing from one host
- redirects are relied upon
- fingerprints are wrong or incomplete
- one host is configured while sibling hosts are forgotten

### 6. Dynamic App Links (Android 15+ only)

**This is a platform-version-gated feature.** Dynamic App Links are available only on Android 15+ (API 35+) and are not relevant for projects targeting lower minimum API levels. Treat this guidance as conditional on the project's min SDK target.

Dynamic App Links add server-side refinement on top of App Links.

Check whether:
- the project actually needs dynamic rules
- dynamic rules refine manifest-declared scope rather than trying to expand it
- path, fragment, and query matching are used intentionally
- exclusions are used carefully where some matching URLs should not open the app

Important review rule:
- dynamic rules cannot expand beyond the hosts and broad URL scope already declared in the manifest

Flag as a concern when:
- the design assumes server-side rules can claim new undeclared hosts
- dynamic rules and static rules contradict each other
- dynamic exclusions create confusing entry behavior

### 7. Shared-vs-platform boundary

Keep Android registration and verification concerns platform-specific.

Prefer:
- Android manifest, verification behavior, and `assetlinks.json` ownership handled at the Android/platform layer
- shared code only interpreting the incoming route and deciding in-app destination behavior

For non-Android KMP targets (web, desktop), deep-link handling is a different concern: Compose Multiplatform web targets can bind navigation state to browser URLs via the navigation library's web-target APIs. See the `kotlin-navigation-compose-multiplatform` skill for shared route and web-URL binding guidance. Android-specific App Link verification does not apply to those targets.

Flag as a concern when:
- shared business/navigation code owns Android verification concerns
- Android-specific registration details leak into `commonMain`
- route parsing is duplicated because platform and shared ownership are unclear
- Android App Link concepts are incorrectly applied to web or desktop KMP targets

### 8. Back-stack and navigation behavior

Deep links should take users directly to relevant content, and navigation after entry should still feel coherent.

Check whether:
- incoming links open the intended content directly
- the app avoids unnecessary interstitials before showing linked content
- back behavior is consistent with how users entered
- deep-link entry does not bypass critical architecture boundaries

Flag as a concern when:
- deep links land on generic home screens without reason
- users hit confusing back-stack behavior after external entry
- deep-link handling bypasses the normal state-holder/navigation pipeline

### 9. Testing and diagnostics

Check whether deep links and App Links can be validated operationally.

Prefer:
- adb-based intent testing for deep-link URIs
- explicit host-by-host verification checks
- tests or QA flows covering both app-installed and app-not-installed cases
- review of assetlinks hosting behavior in real environments

Flag as a concern when:
- correctness depends on manual hope rather than reproducible checks
- only custom-scheme tests exist while App Links are unverified
- host verification is assumed but never validated

---

## Severity framework

### High severity
Likely to cause broken ownership or broken user routing.

Examples:
- incorrect intent-filter structure
- accidental overmatching due to merged `<data>` elements
- missing or invalid `assetlinks.json`
- manifest scope and server-side rules contradicting each other
- Android-specific verification logic leaking into shared code

### Medium severity
Workable, but likely to create maintenance cost or inconsistent UX.

Examples:
- multi-host setup with weak operational ownership
- route interpretation split unclearly across layers
- deep-link back-stack behavior is plausible but confusing
- dynamic rules are used without a clear refinement strategy

### Low severity
Structurally acceptable but worth improving.

Examples:
- route naming could be clearer
- manifest scope is slightly too specific
- diagnostics/testing guidance is incomplete

---

## Required output format

When performing the review, respond with:

1. **Deep-linking summary**
   - link types in use
   - manifest scope
   - host verification model
   - assetlinks strategy
   - shared/platform boundary
   - navigation/back-stack behavior

2. **What is structurally sound**
   - concrete strengths only

3. **Issues by review dimension**
   - link-type choice
   - intent-filter correctness
   - verification model
   - manifest scope
   - assetlinks.json correctness
   - dynamic rules
   - shared/platform boundary
   - back-stack/navigation behavior
   - testing/diagnostics

4. **Severity for each issue**
   - high / medium / low

5. **Concrete recommendations**
   - exact manifest changes
   - host verification fixes
   - assetlinks changes
   - route-handling boundary fixes
   - navigation-entry fixes

6. **Suggested target structure**
   - proposed manifest / Android-entry / shared-route split if useful

7. **Open risks**
   - rollout risks
   - host-verification risks
   - Android-version behavior differences still to validate

---

## Tone

Be direct and practical.
Do not assume “link opens app” means the setup is correct.
If verification, scope, or routing is weak, say so clearly.

---

## Anti-patterns to flag aggressively

- using custom schemes where verified HTTPS App Links should be used
- multiple unrelated `<data>` declarations merged into one filter accidentally
- claiming domains the app does not operationally control
- assuming assetlinks dynamic rules can expand manifest-declared scope
- missing `assetlinks.json` on one of several supported hosts
- redirects on `/.well-known/assetlinks.json`
- Android verification concerns embedded in shared code
- deep-link entry that bypasses the normal navigation/state pipeline

---

## References

- Android Developers: About deep links — https://developer.android.com/training/app-links/about
- Android Developers: Create deep links to app content — https://developer.android.com/training/app-links/create-deeplinks
- Android Developers: Add Android App Links — https://developer.android.com/training/app-links/add-applinks
- Android Developers: Configure website associations and Dynamic App Links — https://developer.android.com/training/app-links/configure-assetlinks
- Android Developers: Verify Android App Links — https://developer.android.com/training/app-links/verify-site-associations